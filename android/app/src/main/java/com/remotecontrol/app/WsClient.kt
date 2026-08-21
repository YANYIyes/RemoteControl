package com.remotecontrol.app

import android.os.Build
import android.provider.Settings
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket 客户端: 负责连接远程控制服务器, 鉴权, 发送指令/接收响应
 */
class WsClient(private val context: Context) {

    interface Listener {
        fun onStatus(status: String, detail: String = "")
        fun onAuthResult(ok: Boolean, pending: Boolean, error: String)
        fun onResponse(cmd: String, payload: JSONObject)
        fun onScreenFrame(bytes: ByteArray) {}
    }

    var listener: Listener? = null

    /** v2.0: 服务端主动推送监听 (CPU 告警/进程消失/定时关机提醒), 由 MonitorService 注册 */
    var pushListener: ((JSONObject) -> Unit)? = null

    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var currentUrl: String? = null
    @Volatile
    private var userDisconnected = false
    private var reconnectJob: Job? = null

    @Volatile
    var connected = false
        private set

    private val pending = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private val pendingSeq = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private var seqCounter = 0L

    /**
     * 设备唯一指纹: ANDROID_ID + Build.FINGERPRINT, 匿名化(仅服务端可识别, App 内零凭据)
     */
    fun deviceSerial(): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val fp = Build.FINGERPRINT
        // 简短的稳定指纹: androidId + 固件指纹摘要
        return (androidId + fp).hashCode().toLong().let { java.lang.Long.toHexString(it) }
    }

    fun connect(serverUrl: String) {
        userDisconnected = false
        currentUrl = serverUrl
        _connect()
    }

    private fun _connect() {
        val serverUrl = currentUrl ?: return
        disconnectInternal()
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
        client = builder.build()

        val req = Request.Builder().url(serverUrl).build()
        listener?.onStatus("connecting")
        ws = client!!.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connected = true
                listener?.onStatus("connected")
                sendHello()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // 二进制帧 = 屏幕截图
                listener?.onScreenFrame(bytes.toByteArray())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(code, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connected = false
                listener?.onStatus("disconnected")
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                connected = false
                listener?.onStatus("disconnected", t.message ?: "连接失败")
                scheduleReconnect()
            }
        })
    }

    /** 断开当前底层连接 (不触发重连) */
    private fun disconnectInternal() {
        ws?.close(1000, "reconnect")
        ws = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
    }

    /** 非主动断开时自动重连 (指数退避) */
    private fun scheduleReconnect() {
        if (userDisconnected) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(5000)
            if (!userDisconnected && connected == false) {
                listener?.onStatus("connecting")
                _connect()
            }
        }
    }

    fun disconnect() {
        userDisconnected = true
        reconnectJob?.cancel()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        disconnectInternal()
        connected = false
    }

    private fun sendHello() {
        val msg = JSONObject().apply {
            put("type", "hello")
            put("serial", deviceSerial())
            put("deviceName", Build.MODEL)
            put("deviceModel", Build.MODEL + " / " + Build.MANUFACTURER)
            put("androidVersion", Build.VERSION.RELEASE)
        }
        ws?.send(msg.toString())
    }

    /**
     * 服务端采用扁平协议: 每条业务消息带可选 seq; 响应回带相同 type+seq
     */
    private fun sendFlat(seq: Long, type: String, params: JSONObject): Boolean {
        val msg = JSONObject()
        msg.put("type", type)
        msg.put("seq", seq)
        val it = params.keys()
        while (it.hasNext()) {
            val k = it.next()
            msg.put(k, params.get(k))
        }
        return ws?.send(msg.toString()) ?: false
    }

    private fun handleMessage(text: String) {
        try {
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "auth" -> {
                    val ok = msg.optBoolean("ok", false)
                    val pending = msg.optBoolean("pending", false)
                    val err = msg.optString("error", "")
                    listener?.onAuthResult(ok, pending, err)
                    if (ok) {
                        listener?.onStatus("connected", "已授权")
                    } else if (pending) {
                        listener?.onStatus("pending_auth", err)
                    } else {
                        listener?.onStatus("rejected", err)
                    }
                }
                "error" -> {
                    listener?.onStatus("error", msg.optString("error"))
                }
                else -> {
                    // v2.0: 服务端主动推送 (push.*), 转发给对应监听器
                    val ptype = msg.optString("type")
                    if (ptype.startsWith("push.")) {
                        // push.sysinfo 系统信息实时帧 → 单独转发给系统信息页
                        if (ptype == "push.sysinfo") {
                            sysInfoListener?.invoke(msg)
                        } else {
                            pushListener?.invoke(msg)
                        }
                        return
                    }
                    // 业务响应: 带 seq 则可精确匹配; 否则按 type 匹配
                    if (msg.has("seq")) {
                        val sKey = msg.optLong("seq").toString()
                        pendingSeq.remove(sKey)?.complete(msg)
                    } else {
                        val t = msg.optString("type")
                        pending.remove(t)?.complete(msg)
                    }
                }
            }
        } catch (e: Exception) {
            // ignore malformed
        }
    }

    /**
     * 发送扁平指令并等待响应(按 seq 精确匹配; 服务端不支持时回退按 type)
     */
    suspend fun request(type: String, params: JSONObject = JSONObject()): JSONObject {
        val deferred = CompletableDeferred<JSONObject>()
        val seq = ++seqCounter
        val sKey = seq.toString()
        pendingSeq[sKey] = deferred
        val sent = sendFlat(seq, type, params)
        if (!sent) {
            pendingSeq.remove(sKey)
            throw Exception("发送失败: 未连接")
        }
        val r = withTimeoutOrNull(45000) { deferred.await() } // v2.0: 公网隧道延迟高, 加大超时
        pendingSeq.remove(sKey)
        return r ?: throw Exception("请求超时: $type")
    }

    suspend fun listProcesses(search: String? = null): JSONObject {
        val params = JSONObject().apply {
            if (!search.isNullOrBlank()) put("search", search)
        }
        return request("process.list", params)
    }

    suspend fun killProcess(pid: Int): JSONObject {
        val params = JSONObject().apply { put("pid", pid) }
        return request("process.kill", params)
    }

    suspend fun shutdown(): JSONObject = request("shutdown")
    suspend fun reboot(): JSONObject = request("reboot")

    // ---------- 扩展功能 ----------
    suspend fun systemInfo(): JSONObject = request("system.info")

    suspend fun fileList(dir: String): JSONObject {
        val params = JSONObject().apply { put("path", dir) }
        return request("file.list", params)
    }

    /** 启动实时屏幕观看 (返回实际 fps), 截图帧通过 onScreenFrame 回调 */
    fun screenStart(fps: Int = 3) {
        sendFlat(++seqCounter, "screen.start", JSONObject().apply { put("fps", fps) })
    }

    fun screenStop() {
        sendFlat(++seqCounter, "screen.stop", JSONObject())
    }

    // ========== v2.0: 推送/监控/定时关机 ==========

    /** 同步告警配置到服务端 (CPU/进程/总开关) */
    suspend fun setPushConfig(cfg: JSONObject): JSONObject {
        return request("push.setConfig", JSONObject().put("config", cfg))
    }

    /** 设置定时关机: time = "HH:MM" (每天) 或 ISO 时间(一次性) */
    suspend fun addSchedule(time: String): JSONObject {
        return request("push.addSchedule", JSONObject().put("time", time))
    }

    /** 查看定时关机 */
    suspend fun listSchedules(): JSONObject {
        return request("push.listSchedules")
    }

    /** 取消定时关机 */
    suspend fun cancelSchedule(): JSONObject {
        return request("push.cancelSchedule")
    }

    // v2.0: 系统信息实时推送 (服务端每秒推 push.sysinfo)
    suspend fun startSysInfoPush(): JSONObject {
        return request("push.startSysInfo")
    }
    suspend fun stopSysInfoPush(): JSONObject {
        return request("push.stopSysInfo")
    }
    var sysInfoListener: ((JSONObject) -> Unit)? = null
}

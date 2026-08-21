package com.remotecontrol.app

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/**
 * v2.0 - 系统信息 (实时)
 * 页面打开时请求服务端每秒推送 push.sysinfo 帧, 收到即刷新, 真正实时
 */
class SystemInfoActivity : AppCompatActivity() {

    private lateinit var tvInfo: TextView
    private val ws = RemoteControlApp.ws

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.apply(this) // v2.0: 应用配色
        setContentView(R.layout.activity_sysinfo)

        tvInfo = findViewById(R.id.tvSysInfo)
        val btnRefresh = findViewById<Button>(R.id.btnSysRefresh)
        btnRefresh.setOnClickListener { load() }
        load()
    }

    override fun onResume() {
        super.onResume()
        // v2.0: 注册实时帧监听 + 通知服务端开始每秒推送
        ws.sysInfoListener = this::onSysInfoFrame
        if (ws.connected) {
            lifecycleScope.launch {
                try { ws.startSysInfoPush() } catch (_: Exception) {}
            }
        }
    }

    override fun onPause() {
        // 离开页面: 停止服务端推送 + 注销监听
        ws.sysInfoListener = null
        if (ws.connected) {
            lifecycleScope.launch {
                try { ws.stopSysInfoPush() } catch (_: Exception) {}
            }
        }
        super.onPause()
    }

    // 服务端每秒推来的实时系统信息帧
    private fun onSysInfoFrame(msg: JSONObject) {
        val info = msg.optJSONObject("info") ?: return
        runOnUiThread { render(info) }
    }

    private fun load() {
        if (!ws.connected) { toast("未连接"); return }
        tvInfo.text = "加载中…"
        lifecycleScope.launch {
            try {
                val r = ws.systemInfo()
                if (r.optBoolean("ok", false)) { render(r); }
                else toast(r.optString("error", "获取失败"))
            } catch (e: Exception) { toast(e.message ?: "失败") }
        }
    }

    private fun render(r: JSONObject) {
        // 开机时长: sysinfo.ps1 返回平铺字段 upDays/upHours/upMins
        val upDays = r.optInt("upDays", 0)
        val upHours = r.optInt("upHours", 0)
        val upMins = r.optInt("upMins", 0)
        val memTotal = r.optDouble("totalMem", 0.0)
        val memFree = r.optDouble("freeMem", 0.0)
        val memUsed = memTotal - memFree
        val sb = StringBuilder()
        sb.append("🖥 操作系统\n  ").append(r.optString("osName", "?")).append('\n')
        sb.append("🏷 主机名\n  ").append(r.optString("hostName", "?")).append('\n')
        sb.append("⚙️ CPU\n  ").append(r.optString("cpuName", "?")).append('\n')
        sb.append("📊 CPU负载\n  ").append(r.optDouble("cpuLoad", 0.0)).append("%\n")
        sb.append("⏱ 开机时长\n  ").append(upDays).append("天 ")
            .append(upHours).append("小时 ").append(upMins).append("分\n")
        sb.append("💾 内存占用\n  ").append(fmt(memUsed)).append(" / ").append(fmt(memTotal))
            .append("  (已用 ").append(if (memTotal > 0) ((memUsed / memTotal * 100).toInt()) else 0).append("%)\n")
        val disks = r.optJSONArray("disks")
        if (disks != null && disks.length() > 0) {
            sb.append("💽 磁盘\n")
            for (i in 0 until disks.length()) {
                val d = disks.getJSONObject(i)
                // sysinfo.ps1 返回大写字段(Drive/Total/Free), 兼容小写
                val drive = d.optString("Drive", d.optString("drive", "?"))
                val total = d.optDouble("Total", d.optDouble("total", 0.0))
                val free  = d.optDouble("Free",  d.optDouble("free", 0.0))
                sb.append("  ").append(drive).append("  已用 ")
                    .append(fmt(total - free))
                    .append(" / ").append(fmt(total)).append('\n')
            }
        }
        tvInfo.text = sb.toString().trimEnd()
    }

    private fun fmt(bytes: Double): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
}
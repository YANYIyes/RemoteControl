package com.remotecontrol.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * 屏幕实时观看 (仅观看, 不可操作)
 * 通过 WebSocket 二进制帧接收服务端截图, 实时显示
 */
class ScreenActivity : AppCompatActivity(), WsClient.Listener {

    private lateinit var imageView: ImageView
    private lateinit var tvInfo: TextView
    private lateinit var btnToggle: Button
    private lateinit var spinner: ProgressBar

    private val ws = RemoteControlApp.ws
    private var viewing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)

        imageView = findViewById(R.id.ivScreen)
        tvInfo = findViewById(R.id.tvScreenInfo)
        btnToggle = findViewById(R.id.btnScreenToggle)
        spinner = findViewById(R.id.spinner)

        ws.listener = this
        updateUi(refreshing = false)

        btnToggle.setOnClickListener {
            if (!ws.connected) { toast("未连接"); return@setOnClickListener }
            if (viewing) { ws.screenStop(); viewing = false; updateUi(); }
            else { ws.screenStart(3); viewing = true; updateUi(refreshing = true); }
        }
    }

    override fun onDestroy() {
        if (viewing) ws.screenStop()
        if (ws.listener === this) ws.listener = null
        super.onDestroy()
    }

    private fun updateUi(refreshing: Boolean = false) {
        btnToggle.text = if (viewing) "停止观看" else "开始实时观看"
        spinner.visibility = if (refreshing) View.VISIBLE else View.GONE
        tvInfo.text = if (viewing) "● 实时观看中 (仅观看)" else "未观看"
    }

    override fun onScreenFrame(bytes: ByteArray) {
        // 二进制帧: header[0..4] = SCRN\x01, 之后是 JPEG
        if (bytes.size <= 5) return
        val bmp = BitmapFactory.decodeByteArray(bytes, 5, bytes.size - 5)
        if (bmp != null) {
            runOnUiThread {
                imageView.setImageBitmap(bmp)
                spinner.visibility = View.GONE
            }
        }
    }

    override fun onStatus(status: String, detail: String) {
        runOnUiThread {
            if (viewing && status != "connected") { viewing = false; updateUi(); }
        }
    }
    override fun onAuthResult(ok: Boolean, pending: Boolean, error: String) {}
    override fun onResponse(cmd: String, payload: org.json.JSONObject) {}

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
}

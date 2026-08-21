package com.remotecontrol.app

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

class FileBrowserActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var tvPath: TextView
    private lateinit var btnUp: Button

    private val ws = RemoteControlApp.ws
    private var currentPath = "C:/"
    private val adapter = FileAdapter { item ->
        if (item.isDir) enter(item.name) else toast(item.name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.apply(this) // v2.0: 应用配色
        setContentView(R.layout.activity_filebrowser)

        rv = findViewById(R.id.rvFiles)
        tvPath = findViewById(R.id.tvFilePath)
        btnUp = findViewById(R.id.btnFileUp)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnUp.setOnClickListener { goUp() }
        load(currentPath)
    }

    private fun enter(dir: String) {
        currentPath = if (currentPath.endsWith("/") || currentPath.endsWith("\\"))
            currentPath + dir else currentPath + "/" + dir
        load(currentPath)
    }

    private fun goUp() {
        val p = currentPath.replace('\\', '/')
        if (!p.contains('/')) return
        val idx = p.lastIndexOf('/')
        if (idx <= 0) { currentPath = "C:/"; load(currentPath); return }
        currentPath = p.substring(0, idx)
        if (currentPath.length == 2 && currentPath[1] == ':') currentPath += "/"
        load(currentPath)
    }

    private fun load(dir: String) {
        tvPath.text = dir
        lifecycleScope.launch {
            try {
                val r = ws.fileList(dir)
                if (r.optBoolean("ok", false)) {
                    val items = r.optJSONArray("items") ?: org.json.JSONArray()
                    val list = (0 until items.length()).map { FileAdapter.Item.fromJSON(items.getJSONObject(it)) }
                    adapter.submit(list)
                } else toast(r.optString("error", "读取失败"))
            } catch (e: Exception) { toast(e.message ?: "读取失败") }
        }
    }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
}

class FileAdapter(private val onClick: (FileAdapter.Item) -> Unit) :
    RecyclerView.Adapter<FileAdapter.VH>() {

    data class Item(val name: String, val isDir: Boolean, val size: Long) {
        companion object {
            fun fromJSON(j: JSONObject) =
                Item(j.optString("name", "?"), j.optBoolean("isDir", false), j.optLong("size", 0))
        }
    }

    private val items = mutableListOf<Item>()
    fun submit(list: List<Item>) { items.clear(); items.addAll(list); notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        holder.tvName.text = if (it.isDir) "📁 " + it.name else it.name
        holder.tvSize.text = if (it.isDir) "目录" else fmtSize(it.size)
        holder.itemView.setOnClickListener { onClick(items[position]) }
    }

    override fun getItemCount() = items.size

    private fun fmtSize(s: Long): String {
        if (s <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = s.toDouble(); var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvFileName)
        val tvSize: TextView = v.findViewById(R.id.tvFileSize)
    }
}

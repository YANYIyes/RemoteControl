package com.remotecontrol.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class ProcessAdapter(
    private val onKill: (Int) -> Unit
) : RecyclerView.Adapter<ProcessAdapter.VH>() {

    private val items = mutableListOf<JSONObject>()

    fun submit(list: List<JSONObject>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_process, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvName.text = item.optString("name", "?")
        holder.tvPid.text = "PID ${item.optInt("pid")}"
        holder.tvCmd.text = item.optString("cmd", "").ifBlank { "—" }
        holder.itemView.setOnClickListener {
            onKill(item.optInt("pid"))
        }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvPid: TextView = v.findViewById(R.id.tvPid)
        val tvCmd: TextView = v.findViewById(R.id.tvCmd)
    }
}

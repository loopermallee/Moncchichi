package com.loopermallee.moncchichi.hub.ui.developer

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.loopermallee.moncchichi.hub.R

class DeveloperConsoleAdapter :
    ListAdapter<DeveloperConsoleAdapter.ConsoleItem, DeveloperConsoleAdapter.ConsoleViewHolder>(DiffCallback) {

    data class ConsoleItem(
        val entry: DeveloperViewModel.ConsoleEntry,
        val highlightTimestamp: Boolean,
    )

    class ConsoleViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val timestampView: TextView = view.findViewById(R.id.text_log_timestamp)
        val messageView: TextView = view.findViewById(R.id.text_log_message)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConsoleViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = inflater.inflate(R.layout.item_developer_console_line, parent, false)
        return ConsoleViewHolder(view)
    }

    override fun onBindViewHolder(holder: ConsoleViewHolder, position: Int) {
        val item = getItem(position)
        val entry = item.entry
        val timestamp = entry.timestamp
        if (item.highlightTimestamp) {
            holder.timestampView.text = holder.timestampView.context.getString(
                R.string.developer_console_timestamp_highlight,
                timestamp,
            )
            holder.timestampView.setTextColor(HIGHLIGHT_COLOR)
        } else {
            holder.timestampView.text = timestamp
            holder.timestampView.setTextColor(TIMESTAMP_COLOR)
        }

        holder.messageView.text = entry.message
        holder.messageView.setTextColor(
            when (entry.severity) {
                DeveloperViewModel.ConsoleSeverity.NORMAL -> NORMAL_COLOR
                DeveloperViewModel.ConsoleSeverity.SUCCESS -> SUCCESS_COLOR
                DeveloperViewModel.ConsoleSeverity.WARNING -> WARNING_COLOR
                DeveloperViewModel.ConsoleSeverity.ERROR -> ERROR_COLOR
            },
        )
    }

    companion object {
        private val TIMESTAMP_COLOR = Color.parseColor("#8A8F9C")
        private val HIGHLIGHT_COLOR = Color.parseColor("#FFD400")
        private val NORMAL_COLOR = Color.parseColor("#CCCCCC")
        private val SUCCESS_COLOR = Color.parseColor("#00FF88")
        private val WARNING_COLOR = Color.parseColor("#FFD400")
        private val ERROR_COLOR = Color.parseColor("#FF4D4D")

        private val DiffCallback = object : DiffUtil.ItemCallback<ConsoleItem>() {
            override fun areItemsTheSame(oldItem: ConsoleItem, newItem: ConsoleItem): Boolean {
                return oldItem.entry.id == newItem.entry.id
            }

            override fun areContentsTheSame(oldItem: ConsoleItem, newItem: ConsoleItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}

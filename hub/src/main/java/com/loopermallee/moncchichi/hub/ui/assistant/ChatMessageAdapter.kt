package com.loopermallee.moncchichi.hub.ui.assistant

import android.content.Context
import android.graphics.Rect
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.loopermallee.moncchichi.core.model.ChatMessage
import com.loopermallee.moncchichi.core.model.MessageOrigin
import com.loopermallee.moncchichi.core.model.MessageSource
import com.loopermallee.moncchichi.hub.R
import java.util.Date

class ChatMessageAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        return if (message.source == MessageSource.USER) {
            TYPE_USER
        } else {
            when (message.origin) {
                MessageOrigin.LLM -> TYPE_ASSISTANT_ONLINE
                MessageOrigin.OFFLINE -> TYPE_ASSISTANT_OFFLINE
                MessageOrigin.DEVICE -> TYPE_ASSISTANT_DEVICE
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserMessageViewHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
            TYPE_ASSISTANT_OFFLINE -> AssistantMessageViewHolder(
                inflater.inflate(R.layout.item_chat_assistant_offline, parent, false)
            )
            TYPE_ASSISTANT_DEVICE -> AssistantMessageViewHolder(
                inflater.inflate(R.layout.item_chat_assistant_device, parent, false)
            )
            else -> AssistantMessageViewHolder(
                inflater.inflate(R.layout.item_chat_assistant_online, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is UserMessageViewHolder -> holder.bind(message)
            is AssistantMessageViewHolder -> holder.bind(message)
        }
    }

    private open class BaseMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val messageView: TextView = view.findViewById(R.id.text_message)
        private val timestampView: TextView = view.findViewById(R.id.text_timestamp)

        open fun bindContent(message: ChatMessage) {
            messageView.text = message.text
            timestampView.text = DateFormat.getTimeFormat(itemView.context).format(Date(message.timestamp))
        }
    }

    private class UserMessageViewHolder(view: View) : BaseMessageViewHolder(view) {
        private val labelView: TextView = view.findViewById(R.id.text_label)

        fun bind(message: ChatMessage) {
            bindContent(message)
            labelView.text = itemView.context.getString(R.string.chat_label_you)
        }
    }

    private class AssistantMessageViewHolder(view: View) : BaseMessageViewHolder(view) {
        private val labelView: TextView = view.findViewById(R.id.text_label)

        fun bind(message: ChatMessage) {
            bindContent(message)
            labelView.text = resolveAssistantLabel(itemView.context, message)
        }
    }

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT_ONLINE = 1
        private const val TYPE_ASSISTANT_OFFLINE = 2
        private const val TYPE_ASSISTANT_DEVICE = 3

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
                return oldItem.timestamp == newItem.timestamp &&
                    oldItem.source == newItem.source &&
                    oldItem.origin == newItem.origin
            }

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
                return oldItem == newItem
            }
        }

        private fun resolveAssistantLabel(context: Context, message: ChatMessage): String {
            val tags = mutableListOf<String>()
            val trimmed = message.text.trimStart()
            if (trimmed.startsWith("🛑")) {
                tags += context.getString(R.string.chat_label_offline)
            }
            when (message.origin) {
                MessageOrigin.LLM -> tags += context.getString(R.string.chat_label_chatgpt)
                MessageOrigin.OFFLINE -> tags += context.getString(R.string.chat_label_offline)
                MessageOrigin.DEVICE -> tags += context.getString(R.string.chat_label_device)
            }
            if (tags.isEmpty()) {
                tags += context.getString(R.string.chat_label_chatgpt)
            }
            return tags.distinct().joinToString(separator = " · ")
        }
    }
}

class ChatBubbleSpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) {
            return
        }
        if (position == 0) {
            outRect.top = spacing
        }
        outRect.bottom = spacing
    }
}

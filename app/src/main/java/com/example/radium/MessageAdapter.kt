package com.example.radium

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.radium.databinding.ItemBubbleReceivedBinding
import com.example.radium.databinding.ItemBubbleSentBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter : ListAdapter<MessageAdapter.ChatItem, RecyclerView.ViewHolder>(DIFF) {

    data class ChatItem(val text: String, val isSent: Boolean, val status: Int, val timestamp: Long)

    class SentVH(val b: ItemBubbleSentBinding) : RecyclerView.ViewHolder(b.root)
    class RecvVH(val b: ItemBubbleReceivedBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(pos: Int) = if (getItem(pos).isSent) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 1) SentVH(ItemBubbleSentBinding.inflate(inflater, parent, false))
        else RecvVH(ItemBubbleReceivedBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val item = getItem(pos)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestamp))
        when (holder) {
            is SentVH -> {
                holder.b.bubbleText.text = item.text
                holder.b.bubbleTime.text = time
                holder.b.statusTick.setImageResource(when (item.status) {
                    1 -> R.drawable.ic_tick_single   // RIL accepted
                    2 -> R.drawable.ic_tick_double   // Delivered
                    -1 -> R.drawable.ic_tick_failed  // Failed
                    else -> R.drawable.ic_tick_single
                })
            }
            is RecvVH -> {
                holder.b.bubbleText.text = item.text
                holder.b.bubbleTime.text = time
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ChatItem>() {
            override fun areItemsTheSame(a: ChatItem, b: ChatItem) = a.timestamp == b.timestamp && a.isSent == b.isSent
            override fun areContentsTheSame(a: ChatItem, b: ChatItem) = a == b
        }
    }
}

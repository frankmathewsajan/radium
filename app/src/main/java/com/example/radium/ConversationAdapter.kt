package com.example.radium

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.radium.data.ConversationEntity
import com.example.radium.databinding.ItemConversationBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversationAdapter(
    private val onClick: (ConversationEntity) -> Unit
) : ListAdapter<ConversationEntity, ConversationAdapter.VH>(DIFF) {

    class VH(val b: ItemConversationBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val conv = getItem(position)
        val displayName = conv.contactName.ifEmpty { conv.phoneNumber }
        holder.b.avatarLetter.text = displayName.first().uppercase()
        holder.b.contactName.text = displayName
        holder.b.lastMessage.text = conv.phoneNumber
        holder.b.timestamp.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(conv.lastTimestamp))
        holder.b.root.setOnClickListener { onClick(conv) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ConversationEntity>() {
            override fun areItemsTheSame(a: ConversationEntity, b: ConversationEntity) = a.phoneNumber == b.phoneNumber
            override fun areContentsTheSame(a: ConversationEntity, b: ConversationEntity) = a == b
        }
    }
}

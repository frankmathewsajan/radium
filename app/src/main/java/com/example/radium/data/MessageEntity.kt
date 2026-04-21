package com.example.radium.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val encryptedBlob: ByteArray,
    val isSent: Boolean,
    val status: Int = 0,  // 0=pending, 1=sent(RIL accepted), 2=delivered, -1=failed
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?) = other is MessageEntity && id == other.id
    override fun hashCode() = id.hashCode()
}

package com.example.radium.data

import androidx.room.*

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY timestamp ASC")
    fun getMessagesForConversation(convId: String): List<MessageEntity>

    @Insert
    fun insertMessage(msg: MessageEntity): Long

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    fun updateStatus(id: Long, status: Int)

    // --- THE NEW AI HOOK ---
    // Required to dynamically remove the "Thinking..." bubble once Llama 3.2 replies
    @Delete
    fun deleteMessage(msg: MessageEntity)

    @Query("SELECT * FROM conversations ORDER BY lastTimestamp DESC")
    fun getAllConversations(): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertConversation(conv: ConversationEntity)

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY timestamp DESC LIMIT 1")
    fun getLastMessage(convId: String): MessageEntity?
}
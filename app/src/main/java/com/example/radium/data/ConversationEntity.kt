package com.example.radium.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val phoneNumber: String,
    val contactName: String = "",
    val lastTimestamp: Long = System.currentTimeMillis()
)

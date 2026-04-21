package com.example.radium.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MessageEntity::class, ConversationEntity::class], version = 1)
abstract class RadiumDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var instance: RadiumDatabase? = null

        fun get(ctx: Context): RadiumDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    ctx.applicationContext, RadiumDatabase::class.java, "radium.db"
                ).build().also { instance = it }
            }
    }
}

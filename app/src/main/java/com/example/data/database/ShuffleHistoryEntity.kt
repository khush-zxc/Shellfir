package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shuffle_history")
data class ShuffleHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val shuffledAppCount: Int,
    val triggerType: String, // "MANUAL" or "SCHEDULED"
    val strategy: String = "FULL_RANDOM",
    val scope: String = "HOME"
)

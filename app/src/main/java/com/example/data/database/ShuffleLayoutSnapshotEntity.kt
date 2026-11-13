package com.example.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shuffle_layout_snapshots",
    indices = [Index(value = ["historyId"])]
)
data class ShuffleLayoutSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val snapshotId: Long = 0,
    val historyId: Long? = null,
    val timestamp: Long,
    val snapshotJson: String
)

package com.example.data.database

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "folder_members",
    primaryKeys = ["folderId", "packageName", "activityName"],
    indices = [
        Index(value = ["folderId"]),
        Index(value = ["packageName", "activityName"])
    ]
)
data class FolderMemberEntity(
    val folderId: String,
    val packageName: String,
    val activityName: String,
    val rank: Int,
    val isFixed: Boolean = false,
    val isShuffled: Boolean = false
)

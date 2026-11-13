package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Normalized folder metadata entity.
 * Home screen placement of a folder lives in [WorkspaceItemEntity] (`itemType = "FOLDER"`).
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val folderId: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

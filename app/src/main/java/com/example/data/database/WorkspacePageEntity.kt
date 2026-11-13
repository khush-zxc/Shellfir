package com.example.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workspace_pages",
    indices = [Index(value = ["pageIndex"], unique = true)]
)
data class WorkspacePageEntity(
    @PrimaryKey val pageId: String,
    val pageIndex: Int,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

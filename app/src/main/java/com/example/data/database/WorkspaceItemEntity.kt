package com.example.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object WorkspaceItemTypes {
    const val APP = "APP"
    const val FOLDER = "FOLDER"
    const val WIDGET = "WIDGET"
}

object WorkspaceContainers {
    const val HOME = "HOME"
    const val DOCK = "DOCK"
}

@Entity(
    tableName = "workspace_items",
    indices = [
        Index(value = ["pageId"]),
        Index(value = ["folderId"]),
        Index(value = ["packageName", "activityName"]),
        Index(value = ["containerType", "pageId", "rank"])
    ]
)
data class WorkspaceItemEntity(
    @PrimaryKey val itemId: String,
    val itemType: String,
    val containerType: String,
    val pageId: String? = null,
    val packageName: String? = null,
    val activityName: String? = null,
    val folderId: String? = null,
    val appWidgetId: Int? = null,
    val columnSpan: Int = 1,
    val rowSpan: Int = 1,
    val rank: Int,
    val isFixed: Boolean = false,
    val isShuffled: Boolean = false,
    val isHidden: Boolean = false
)

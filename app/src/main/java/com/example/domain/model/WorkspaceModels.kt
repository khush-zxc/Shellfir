package com.example.domain.model

data class WorkspacePage(
    val pageId: String,
    val pageIndex: Int,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class WorkspaceItemPlacement(
    val itemId: String,
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

data class FolderMember(
    val folderId: String,
    val packageName: String,
    val activityName: String,
    val rank: Int,
    val isFixed: Boolean = false,
    val isShuffled: Boolean = false
)

data class DrawerLayoutConfig(
    val packageName: String,
    val activityName: String,
    val drawerRank: Int = -1,
    val isDrawerShuffled: Boolean = false,
    val isHidden: Boolean = false,
    val isFixed: Boolean = false
)

data class FocusAppConfig(
    val packageName: String,
    val activityName: String,
    val isVisibleInFocus: Boolean = false,
    val isEssentialInFocus: Boolean = false,
    val customOrder: Int = 0
)

fun componentIdentityKey(packageName: String, activityName: String): String =
    "$packageName/$activityName"

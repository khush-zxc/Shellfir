package com.example.domain.model

/**
 * UI & ShuffleEngine domain projection of a launchable application component in Shellfri launcher,
 * synthesized from normalized workspace tables and keyed canonically by (packageName, activityName).
 */
data class AppItem(
    val packageName: String,
    val activityName: String,
    val label: String,
    val isShuffled: Boolean = false,
    val isFixed: Boolean = false,
    val slotIndex: Int = -1,
    val pageIndex: Int = 0,
    val folderId: String? = null,
    val isPinnedToDock: Boolean = false,
    val isOnHomeScreen: Boolean = true,
    val isVisibleInFocus: Boolean = false,
    val isEssentialInFocus: Boolean = false,
    val isHidden: Boolean = false,
    val isSystemApp: Boolean = false,
    val customCategory: String = "Default",
    val lastUsedTimestamp: Long = 0L,
    val drawerSlotIndex: Int = -1,
    val isDrawerShuffled: Boolean = false,
    val dockRank: Int = -1,
    val homeItemId: String? = null,
    val dockItemId: String? = null,
    val isInstalled: Boolean = true,
    val folderRank: Int = -1
) {
    val componentKey: String
        get() = "$packageName/$activityName"
}

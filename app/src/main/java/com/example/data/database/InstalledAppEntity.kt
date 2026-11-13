package com.example.data.database

import androidx.room.Entity

/**
 * Normalized installed launcher activity record keyed by canonical component identity:
 * (packageName, activityName).
 */
@Entity(
    tableName = "installed_apps",
    primaryKeys = ["packageName", "activityName"]
)
data class InstalledAppEntity(
    val packageName: String,
    val activityName: String,
    val label: String,
    val isSystemApp: Boolean = false,
    val customCategory: String = "Default",
    val lastUsedTimestamp: Long = 0L,
    val isInstalled: Boolean = true,
    val archivedPlacementJson: String? = null
)

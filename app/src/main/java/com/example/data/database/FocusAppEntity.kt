package com.example.data.database

import androidx.room.Entity

@Entity(
    tableName = "focus_apps",
    primaryKeys = ["packageName", "activityName"]
)
data class FocusAppEntity(
    val packageName: String,
    val activityName: String,
    val isVisibleInFocus: Boolean = false,
    val isEssentialInFocus: Boolean = false,
    val customOrder: Int = 0
)

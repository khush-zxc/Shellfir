package com.example.data.database

import androidx.room.Entity

@Entity(
    tableName = "drawer_layout",
    primaryKeys = ["packageName", "activityName"]
)
data class DrawerLayoutEntity(
    val packageName: String,
    val activityName: String,
    val drawerRank: Int = -1,
    val isDrawerShuffled: Boolean = false,
    val isHidden: Boolean = false,
    val isFixed: Boolean = false
)

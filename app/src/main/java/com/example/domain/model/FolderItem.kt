package com.example.domain.model

data class FolderItem(
    val id: String,
    val name: String,
    val pageIndex: Int = 0,
    val slotIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

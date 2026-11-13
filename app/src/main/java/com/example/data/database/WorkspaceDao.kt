package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {
    // --- Pages ---
    @Query("SELECT * FROM workspace_pages ORDER BY pageIndex ASC")
    fun getPages(): Flow<List<WorkspacePageEntity>>

    @Query("SELECT * FROM workspace_pages ORDER BY pageIndex ASC")
    suspend fun getPagesList(): List<WorkspacePageEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPages(pages: List<WorkspacePageEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPage(page: WorkspacePageEntity)

    @Query("UPDATE workspace_pages SET pageIndex = :pageIndex, isDefault = :isDefault WHERE pageId = :pageId")
    suspend fun updatePageMetadata(pageId: String, pageIndex: Int, isDefault: Boolean)

    @Upsert
    suspend fun upsertPages(pages: List<WorkspacePageEntity>)

    @Upsert
    suspend fun upsertPage(page: WorkspacePageEntity)

    @Query("DELETE FROM workspace_pages WHERE pageId = :pageId")
    suspend fun deletePage(pageId: String)

    @Query("DELETE FROM workspace_pages")
    suspend fun deleteAllPages()

    // --- Workspace Items (HOME & DOCK) ---
    @Query("SELECT * FROM workspace_items ORDER BY containerType ASC, pageId ASC, rank ASC")
    fun getWorkspaceItems(): Flow<List<WorkspaceItemEntity>>

    @Query("SELECT * FROM workspace_items ORDER BY containerType ASC, pageId ASC, rank ASC")
    suspend fun getWorkspaceItemsList(): List<WorkspaceItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkspaceItems(items: List<WorkspaceItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkspaceItem(item: WorkspaceItemEntity)

    @Query("DELETE FROM workspace_items WHERE itemId = :itemId")
    suspend fun deleteWorkspaceItemById(itemId: String)

    @Query("DELETE FROM workspace_items WHERE packageName = :packageName AND activityName = :activityName")
    suspend fun deleteWorkspaceItemsForComponent(packageName: String, activityName: String)

    @Query("DELETE FROM workspace_items WHERE packageName = :packageName AND activityName = :activityName AND containerType = :containerType")
    suspend fun deleteWorkspaceItemsForComponentInContainer(
        packageName: String,
        activityName: String,
        containerType: String
    )

    @Query("DELETE FROM workspace_items WHERE folderId = :folderId")
    suspend fun deleteWorkspaceItemsForFolder(folderId: String)

    @Query("DELETE FROM workspace_items")
    suspend fun deleteAllWorkspaceItems()

    // --- Folders ---
    @Query("SELECT * FROM folders ORDER BY createdAt ASC")
    fun getFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY createdAt ASC")
    suspend fun getFoldersList(): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolder(folder: FolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolders(folders: List<FolderEntity>)

    @Query("DELETE FROM folders WHERE folderId = :folderId")
    suspend fun deleteFolder(folderId: String)

    @Query("DELETE FROM folders")
    suspend fun deleteAllFolders()

    // --- Folder Members ---
    @Query("SELECT * FROM folder_members ORDER BY folderId ASC, rank ASC")
    fun getFolderMembers(): Flow<List<FolderMemberEntity>>

    @Query("SELECT * FROM folder_members ORDER BY folderId ASC, rank ASC")
    suspend fun getFolderMembersList(): List<FolderMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolderMembers(members: List<FolderMemberEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolderMember(member: FolderMemberEntity)

    @Query("DELETE FROM folder_members WHERE folderId = :folderId")
    suspend fun deleteFolderMembersByFolder(folderId: String)

    @Query("DELETE FROM folder_members WHERE folderId = :folderId AND packageName = :packageName AND activityName = :activityName")
    suspend fun deleteFolderMember(folderId: String, packageName: String, activityName: String)

    @Query("DELETE FROM folder_members WHERE packageName = :packageName AND activityName = :activityName")
    suspend fun deleteFolderMembersForComponent(packageName: String, activityName: String)

    @Query("DELETE FROM folder_members")
    suspend fun deleteAllFolderMembers()

    // --- Drawer Layout ---
    @Query("SELECT * FROM drawer_layout ORDER BY drawerRank ASC, packageName ASC, activityName ASC")
    fun getDrawerLayouts(): Flow<List<DrawerLayoutEntity>>

    @Query("SELECT * FROM drawer_layout")
    suspend fun getDrawerLayoutsList(): List<DrawerLayoutEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDrawerLayouts(layouts: List<DrawerLayoutEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDrawerLayout(layout: DrawerLayoutEntity)

    @Query("DELETE FROM drawer_layout WHERE packageName = :packageName AND activityName = :activityName")
    suspend fun deleteDrawerLayoutForComponent(packageName: String, activityName: String)

    @Query("DELETE FROM drawer_layout")
    suspend fun deleteAllDrawerLayouts()

    // --- Focus Apps ---
    @Query("SELECT * FROM focus_apps ORDER BY customOrder ASC, packageName ASC, activityName ASC")
    fun getFocusApps(): Flow<List<FocusAppEntity>>

    @Query("SELECT * FROM focus_apps")
    suspend fun getFocusAppsList(): List<FocusAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFocusApps(apps: List<FocusAppEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFocusApp(app: FocusAppEntity)

    @Query("DELETE FROM focus_apps WHERE packageName = :packageName AND activityName = :activityName")
    suspend fun deleteFocusAppForComponent(packageName: String, activityName: String)

    @Query("DELETE FROM focus_apps")
    suspend fun deleteAllFocusApps()
}

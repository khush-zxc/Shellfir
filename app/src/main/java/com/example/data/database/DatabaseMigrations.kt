package com.example.data.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit Room database migrations for Shellfri.
 * Preserves all existing user data without destructive table drops.
 */
object DatabaseMigrations {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `folders` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `pageIndex` INTEGER NOT NULL DEFAULT 0,
                    `slotIndex` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `shuffle_history` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `shuffledAppCount` INTEGER NOT NULL,
                    `triggerType` TEXT NOT NULL
                )
                """.trimIndent()
            )
            addColumnIfMissing(db, "apps", "pageIndex", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "apps", "folderId", "TEXT")
            addColumnIfMissing(db, "apps", "isPinnedToDock", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "apps", "isOnHomeScreen", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing(db, "apps", "isVisibleInFocus", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "apps", "isEssentialInFocus", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "apps", "isHidden", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "apps", "isSystemApp", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "apps", "customCategory", "TEXT NOT NULL DEFAULT 'Default'")
            addColumnIfMissing(db, "apps", "lastUsedTimestamp", "INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addColumnIfMissing(db, "apps", "drawerSlotIndex", "INTEGER NOT NULL DEFAULT -1")
            addColumnIfMissing(db, "apps", "isDrawerShuffled", "INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val hasFolders = tableExists(db, "folders")
            val hasHistory = tableExists(db, "shuffle_history")
            val hasApps = tableExists(db, "apps")

            if (hasFolders) {
                db.execSQL("ALTER TABLE `folders` RENAME TO `folders_legacy`")
            }
            if (hasHistory) {
                db.execSQL("ALTER TABLE `shuffle_history` RENAME TO `shuffle_history_legacy`")
            }

            // Create normalized v4 tables matching Room's schema definition exactly
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `installed_apps` (
                    `packageName` TEXT NOT NULL,
                    `activityName` TEXT NOT NULL,
                    `label` TEXT NOT NULL,
                    `isSystemApp` INTEGER NOT NULL,
                    `customCategory` TEXT NOT NULL,
                    `lastUsedTimestamp` INTEGER NOT NULL,
                    `isInstalled` INTEGER NOT NULL,
                    `archivedPlacementJson` TEXT,
                    PRIMARY KEY(`packageName`, `activityName`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `workspace_pages` (
                    `pageId` TEXT NOT NULL,
                    `pageIndex` INTEGER NOT NULL,
                    `isDefault` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`pageId`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_pages_pageIndex` ON `workspace_pages` (`pageIndex`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `workspace_items` (
                    `itemId` TEXT NOT NULL,
                    `itemType` TEXT NOT NULL,
                    `containerType` TEXT NOT NULL,
                    `pageId` TEXT,
                    `packageName` TEXT,
                    `activityName` TEXT,
                    `folderId` TEXT,
                    `appWidgetId` INTEGER,
                    `columnSpan` INTEGER NOT NULL,
                    `rowSpan` INTEGER NOT NULL,
                    `rank` INTEGER NOT NULL,
                    `isFixed` INTEGER NOT NULL,
                    `isShuffled` INTEGER NOT NULL,
                    `isHidden` INTEGER NOT NULL,
                    PRIMARY KEY(`itemId`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_items_pageId` ON `workspace_items` (`pageId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_items_folderId` ON `workspace_items` (`folderId`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_workspace_items_packageName_activityName` ON `workspace_items` (`packageName`, `activityName`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_workspace_items_containerType_pageId_rank` ON `workspace_items` (`containerType`, `pageId`, `rank`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `folders` (
                    `folderId` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`folderId`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `folder_members` (
                    `folderId` TEXT NOT NULL,
                    `packageName` TEXT NOT NULL,
                    `activityName` TEXT NOT NULL,
                    `rank` INTEGER NOT NULL,
                    `isFixed` INTEGER NOT NULL,
                    `isShuffled` INTEGER NOT NULL,
                    PRIMARY KEY(`folderId`, `packageName`, `activityName`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_folder_members_folderId` ON `folder_members` (`folderId`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_folder_members_packageName_activityName` ON `folder_members` (`packageName`, `activityName`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `drawer_layout` (
                    `packageName` TEXT NOT NULL,
                    `activityName` TEXT NOT NULL,
                    `drawerRank` INTEGER NOT NULL,
                    `isDrawerShuffled` INTEGER NOT NULL,
                    `isHidden` INTEGER NOT NULL,
                    `isFixed` INTEGER NOT NULL,
                    PRIMARY KEY(`packageName`, `activityName`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `focus_apps` (
                    `packageName` TEXT NOT NULL,
                    `activityName` TEXT NOT NULL,
                    `isVisibleInFocus` INTEGER NOT NULL,
                    `isEssentialInFocus` INTEGER NOT NULL,
                    `customOrder` INTEGER NOT NULL,
                    PRIMARY KEY(`packageName`, `activityName`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `shuffle_history` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `shuffledAppCount` INTEGER NOT NULL,
                    `triggerType` TEXT NOT NULL,
                    `strategy` TEXT NOT NULL,
                    `scope` TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `shuffle_layout_snapshots` (
                    `snapshotId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `historyId` INTEGER,
                    `timestamp` INTEGER NOT NULL,
                    `snapshotJson` TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_shuffle_layout_snapshots_historyId` ON `shuffle_layout_snapshots` (`historyId`)"
            )

            // Read legacy data into memory (safe across all Android SQLite versions, no window functions needed)
            val legacyFolders = mutableListOf<LegacyFolderRow>()
            if (hasFolders) {
                db.query("SELECT `id`, `name`, `pageIndex`, `slotIndex`, `createdAt` FROM `folders_legacy` ORDER BY `pageIndex` ASC, `slotIndex` ASC, `id` ASC").use { cursor ->
                    while (cursor.moveToNext()) {
                        legacyFolders.add(
                            LegacyFolderRow(
                                id = cursor.getString(0),
                                name = cursor.getString(1),
                                pageIndex = cursor.getInt(2).coerceAtLeast(0),
                                slotIndex = cursor.getInt(3).coerceAtLeast(0),
                                createdAt = cursor.getLong(4)
                            )
                        )
                    }
                }
            }

            val legacyApps = mutableListOf<LegacyAppRow>()
            if (hasApps) {
                db.query(
                    """
                    SELECT `packageName`, `activityName`, `label`, `isShuffled`, `isFixed`, `slotIndex`,
                           `pageIndex`, `folderId`, `isPinnedToDock`, `isOnHomeScreen`, `isVisibleInFocus`,
                           `isEssentialInFocus`, `isHidden`, `isSystemApp`, `customCategory`,
                           `lastUsedTimestamp`, `drawerSlotIndex`, `isDrawerShuffled`
                    FROM `apps`
                    ORDER BY `pageIndex` ASC, `slotIndex` ASC, `packageName` ASC, `activityName` ASC
                    """.trimIndent()
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        legacyApps.add(
                            LegacyAppRow(
                                packageName = cursor.getString(0),
                                activityName = cursor.getString(1),
                                label = cursor.getString(2),
                                isShuffled = cursor.getInt(3) != 0,
                                isFixed = cursor.getInt(4) != 0,
                                slotIndex = cursor.getInt(5),
                                pageIndex = cursor.getInt(6),
                                folderId = if (cursor.isNull(7)) null else cursor.getString(7),
                                isPinnedToDock = cursor.getInt(8) != 0,
                                isOnHomeScreen = cursor.getInt(9) != 0,
                                isVisibleInFocus = cursor.getInt(10) != 0,
                                isEssentialInFocus = cursor.getInt(11) != 0,
                                isHidden = cursor.getInt(12) != 0,
                                isSystemApp = cursor.getInt(13) != 0,
                                customCategory = cursor.getString(14),
                                lastUsedTimestamp = cursor.getLong(15),
                                drawerSlotIndex = cursor.getInt(16),
                                isDrawerShuffled = cursor.getInt(17) != 0
                            )
                        )
                    }
                }
            }

            // Compute page count needed to cover all migrated folder & home app pages
            val maxFolderPage = legacyFolders.maxOfOrNull { it.pageIndex } ?: 0
            val maxAppPage = legacyApps
                .filter { !it.isPinnedToDock && it.folderId == null && it.isOnHomeScreen }
                .maxOfOrNull { it.pageIndex.coerceAtLeast(0) } ?: 0
            val totalPages = maxOf(1, maxFolderPage + 1, maxAppPage + 1)
            val migrationNow = System.currentTimeMillis()

            for (pageIdx in 0 until totalPages) {
                db.insert(
                    "workspace_pages",
                    SQLiteDatabase.CONFLICT_REPLACE,
                    ContentValues().apply {
                        put("pageId", "page_$pageIdx")
                        put("pageIndex", pageIdx)
                        put("isDefault", if (pageIdx == 0) 1 else 0)
                        put("createdAt", migrationNow)
                    }
                )
            }

            // Track occupied ranks per page to guarantee zero rank collisions in workspace_items
            val occupiedHomeRanksByPage = mutableMapOf<Int, MutableSet<Int>>()
            fun allocateHomeRank(pageIdx: Int, preferredRank: Int): Int {
                val occupied = occupiedHomeRanksByPage.getOrPut(pageIdx) { mutableSetOf() }
                var candidate = preferredRank.coerceAtLeast(0)
                while (candidate in occupied) {
                    candidate++
                }
                occupied.add(candidate)
                return candidate
            }

            // Migrate folders -> folders + workspace_items(FOLDER)
            val validFolderIds = mutableSetOf<String>()
            for (folder in legacyFolders) {
                validFolderIds.add(folder.id)
                db.insert(
                    "folders",
                    SQLiteDatabase.CONFLICT_REPLACE,
                    ContentValues().apply {
                        put("folderId", folder.id)
                        put("name", folder.name)
                        put("createdAt", folder.createdAt)
                    }
                )
                val folderRank = allocateHomeRank(folder.pageIndex, folder.slotIndex)
                db.insert(
                    "workspace_items",
                    SQLiteDatabase.CONFLICT_REPLACE,
                    ContentValues().apply {
                        put("itemId", "folder_${folder.id}")
                        put("itemType", WorkspaceItemTypes.FOLDER)
                        put("containerType", WorkspaceContainers.HOME)
                        put("pageId", "page_${folder.pageIndex}")
                        putNull("packageName")
                        putNull("activityName")
                        put("folderId", folder.id)
                        putNull("appWidgetId")
                        put("columnSpan", 1)
                        put("rowSpan", 1)
                        put("rank", folderRank)
                        put("isFixed", 0)
                        put("isShuffled", 0)
                        put("isHidden", 0)
                    }
                )
            }

            // Migrate apps -> installed_apps, drawer_layout, focus_apps, folder_members, workspace_items
            val folderMemberRanks = mutableMapOf<String, Int>()
            val dockCandidates = mutableListOf<LegacyAppRow>()

            for (app in legacyApps) {
                // Preserve exact packageName + activityName without inventing missing activity names
                db.insert(
                    "installed_apps",
                    SQLiteDatabase.CONFLICT_REPLACE,
                    ContentValues().apply {
                        put("packageName", app.packageName)
                        put("activityName", app.activityName)
                        put("label", app.label)
                        put("isSystemApp", if (app.isSystemApp) 1 else 0)
                        put("customCategory", app.customCategory)
                        put("lastUsedTimestamp", app.lastUsedTimestamp)
                        put("isInstalled", 1)
                        putNull("archivedPlacementJson")
                    }
                )

                db.insert(
                    "drawer_layout",
                    SQLiteDatabase.CONFLICT_REPLACE,
                    ContentValues().apply {
                        put("packageName", app.packageName)
                        put("activityName", app.activityName)
                        put("drawerRank", app.drawerSlotIndex)
                        put("isDrawerShuffled", if (app.isDrawerShuffled) 1 else 0)
                        put("isHidden", if (app.isHidden) 1 else 0)
                        put("isFixed", if (app.isFixed) 1 else 0)
                    }
                )

                db.insert(
                    "focus_apps",
                    SQLiteDatabase.CONFLICT_REPLACE,
                    ContentValues().apply {
                        put("packageName", app.packageName)
                        put("activityName", app.activityName)
                        put("isVisibleInFocus", if (app.isVisibleInFocus) 1 else 0)
                        put("isEssentialInFocus", if (app.isEssentialInFocus) 1 else 0)
                        put("customOrder", 0)
                    }
                )

                val activeFolderId = app.folderId?.takeIf { it in validFolderIds }
                if (activeFolderId != null) {
                    val memberRank = folderMemberRanks.getOrDefault(activeFolderId, 0)
                    folderMemberRanks[activeFolderId] = memberRank + 1
                    db.insert(
                        "folder_members",
                        SQLiteDatabase.CONFLICT_REPLACE,
                        ContentValues().apply {
                            put("folderId", activeFolderId)
                            put("packageName", app.packageName)
                            put("activityName", app.activityName)
                            put("rank", memberRank)
                            put("isFixed", if (app.isFixed) 1 else 0)
                            put("isShuffled", if (app.isShuffled) 1 else 0)
                        }
                    )
                } else if (app.isPinnedToDock) {
                    // In v3, dock apps also retained isOnHomeScreen = 1 while UI filtered !isPinnedToDock.
                    // Migrate exclusively to independent DOCK container without duplicating onto HOME.
                    dockCandidates.add(app)
                } else if (app.isOnHomeScreen) {
                    val safePage = app.pageIndex.coerceAtLeast(0)
                    val assignedRank = allocateHomeRank(safePage, app.slotIndex)
                    db.insert(
                        "workspace_items",
                        SQLiteDatabase.CONFLICT_REPLACE,
                        ContentValues().apply {
                            put("itemId", "home_${app.packageName}_${app.activityName}")
                            put("itemType", WorkspaceItemTypes.APP)
                            put("containerType", WorkspaceContainers.HOME)
                            put("pageId", "page_$safePage")
                            put("packageName", app.packageName)
                            put("activityName", app.activityName)
                            putNull("folderId")
                            putNull("appWidgetId")
                            put("columnSpan", 1)
                            put("rowSpan", 1)
                            put("rank", assignedRank)
                            put("isFixed", if (app.isFixed) 1 else 0)
                            put("isShuffled", if (app.isShuffled && !app.isFixed) 1 else 0)
                            put("isHidden", if (app.isHidden) 1 else 0)
                        }
                    )
                }
            }

            // Assign independent sequential DOCK ranks ordered by legacy slotIndex ASC, packageName ASC
            val orderedDock = dockCandidates.sortedWith(
                compareBy<LegacyAppRow> { it.slotIndex.coerceAtLeast(0) }
                    .thenBy { it.packageName }
                    .thenBy { it.activityName }
            )
            orderedDock.forEachIndexed { dockRank, app ->
                db.insert(
                    "workspace_items",
                    SQLiteDatabase.CONFLICT_REPLACE,
                    ContentValues().apply {
                        put("itemId", "dock_${app.packageName}_${app.activityName}")
                        put("itemType", WorkspaceItemTypes.APP)
                        put("containerType", WorkspaceContainers.DOCK)
                        putNull("pageId")
                        put("packageName", app.packageName)
                        put("activityName", app.activityName)
                        putNull("folderId")
                        putNull("appWidgetId")
                        put("columnSpan", 1)
                        put("rowSpan", 1)
                        put("rank", dockRank)
                        put("isFixed", if (app.isFixed) 1 else 0)
                        put("isShuffled", if (app.isShuffled && !app.isFixed) 1 else 0)
                        put("isHidden", if (app.isHidden) 1 else 0)
                    }
                )
            }

            // Migrate shuffle history
            if (hasHistory) {
                db.query("SELECT `id`, `timestamp`, `shuffledAppCount`, `triggerType` FROM `shuffle_history_legacy` ORDER BY `id` ASC").use { cursor ->
                    while (cursor.moveToNext()) {
                        db.insert(
                            "shuffle_history",
                            SQLiteDatabase.CONFLICT_REPLACE,
                            ContentValues().apply {
                                put("id", cursor.getLong(0))
                                put("timestamp", cursor.getLong(1))
                                put("shuffledAppCount", cursor.getInt(2))
                                put("triggerType", cursor.getString(3))
                                put("strategy", "FULL_RANDOM")
                                put("scope", "HOME")
                            }
                        )
                    }
                }
            }

            if (hasApps) db.execSQL("DROP TABLE `apps`")
            if (hasFolders) db.execSQL("DROP TABLE `folders_legacy`")
            if (hasHistory) db.execSQL("DROP TABLE `shuffle_history_legacy`")
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4
    )

    private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean {
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun addColumnIfMissing(
        db: SupportSQLiteDatabase,
        tableName: String,
        columnName: String,
        columnDefinition: String
    ) {
        val existingColumns = mutableSetOf<String>()
        db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex >= 0) {
                while (cursor.moveToNext()) {
                    existingColumns.add(cursor.getString(nameIndex))
                }
            }
        }
        if (!existingColumns.contains(columnName)) {
            db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $columnDefinition")
        }
    }

    private data class LegacyFolderRow(
        val id: String,
        val name: String,
        val pageIndex: Int,
        val slotIndex: Int,
        val createdAt: Long
    )

    private data class LegacyAppRow(
        val packageName: String,
        val activityName: String,
        val label: String,
        val isShuffled: Boolean,
        val isFixed: Boolean,
        val slotIndex: Int,
        val pageIndex: Int,
        val folderId: String?,
        val isPinnedToDock: Boolean,
        val isOnHomeScreen: Boolean,
        val isVisibleInFocus: Boolean,
        val isEssentialInFocus: Boolean,
        val isHidden: Boolean,
        val isSystemApp: Boolean,
        val customCategory: String,
        val lastUsedTimestamp: Long,
        val drawerSlotIndex: Int,
        val isDrawerShuffled: Boolean
    )
}

package com.example.data.backup

import androidx.room.withTransaction
import com.example.data.database.DrawerLayoutEntity
import com.example.data.database.FocusAppEntity
import com.example.data.database.FolderEntity
import com.example.data.database.FolderMemberEntity
import com.example.data.database.InstalledAppDao
import com.example.data.database.InstalledAppEntity
import com.example.data.database.ShellfriDatabase
import com.example.data.database.ShuffleHistoryDao
import com.example.data.database.ShuffleLayoutSnapshotEntity
import com.example.data.database.WorkspaceContainers
import com.example.data.database.WorkspaceDao
import com.example.data.database.WorkspaceItemEntity
import com.example.data.database.WorkspaceItemTypes
import com.example.data.database.WorkspacePageEntity
import com.example.data.preferences.BackupPreferencesConfig
import com.example.data.preferences.ShellfriPreferences
import com.example.domain.concurrency.WorkspaceMutationCoordinator
import com.example.domain.model.AppItem
import com.example.domain.model.BlurLevel
import com.example.domain.model.FolderItem
import com.example.domain.model.GridSpacing
import com.example.domain.model.HomeMode
import com.example.domain.model.OccupancyOption
import com.example.domain.model.ShuffleInterval
import com.example.domain.model.ShuffleStrategy
import com.example.domain.validation.WorkspaceValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupSummary(
    val version: Int,
    val timestamp: Long,
    val appCount: Int,
    val folderCount: Int,
    val themeMode: String,
    val gridColumns: Int,
    val pageCount: Int = 2,
    val dockCount: Int = 5
)

data class BackupRestoreReport(
    val sourceVersion: Int,
    val restoredAppCount: Int,
    val restoredFolderCount: Int,
    val restoredPageCount: Int,
    val restoredWidgetCount: Int,
    val skippedInvalidWidgets: List<Int> = emptyList(),
    val skippedUninstalledComponents: Int = 0,
    val clearedPreRestoreShuffleSnapshots: Boolean = true
)

class BackupRestoreRollbackException(
    val primaryError: Throwable,
    val rollbackError: Throwable
) : IllegalStateException(
    "Backup restore failed (${primaryError.message ?: primaryError::class.java.simpleName}); " +
        "rollback also failed (${rollbackError.message ?: rollbackError::class.java.simpleName})",
    primaryError
) {
    init {
        addSuppressed(rollbackError)
    }
}

private data class ParsedV1BackupApp(
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
    val drawerSlotIndex: Int,
    val isDrawerShuffled: Boolean
) {
    fun toDomain(): AppItem = AppItem(
        packageName = packageName,
        activityName = activityName,
        label = label,
        isShuffled = isShuffled,
        isFixed = isFixed,
        slotIndex = slotIndex,
        pageIndex = pageIndex,
        folderId = folderId,
        isPinnedToDock = isPinnedToDock,
        isOnHomeScreen = isOnHomeScreen && !isPinnedToDock,
        isVisibleInFocus = isVisibleInFocus,
        isEssentialInFocus = isEssentialInFocus,
        isHidden = isHidden,
        drawerSlotIndex = drawerSlotIndex,
        isDrawerShuffled = isDrawerShuffled
    )
}

private sealed class ParsedBackupPayload {
    abstract val summary: BackupSummary
    abstract val preferences: BackupPreferencesConfig

    data class V1Legacy(
        override val summary: BackupSummary,
        override val preferences: BackupPreferencesConfig,
        val apps: List<ParsedV1BackupApp>,
        val folders: List<FolderItem>
    ) : ParsedBackupPayload()

    data class V2Normalized(
        override val summary: BackupSummary,
        override val preferences: BackupPreferencesConfig,
        val pages: List<WorkspacePageEntity>,
        val folders: List<FolderEntity>,
        val workspaceItems: List<WorkspaceItemEntity>,
        val folderMembers: List<FolderMemberEntity>,
        val drawerLayout: List<DrawerLayoutEntity>,
        val focusApps: List<FocusAppEntity>
    ) : ParsedBackupPayload()
}

private data class RestoredWorkspaceBundle(
    val pages: List<WorkspacePageEntity>,
    val folders: List<FolderEntity>,
    val workspaceItems: List<WorkspaceItemEntity>,
    val folderMembers: List<FolderMemberEntity>,
    val drawerLayout: List<DrawerLayoutEntity>,
    val focusApps: List<FocusAppEntity>,
    val skippedInvalidWidgets: List<Int> = emptyList(),
    val skippedUninstalledComponents: Int = 0
)

/**
 * Backup and restore helper supporting normalized v2 workspace backups and deterministic
 * legacy v1 import conversion.
 *
 * Durability & Failure-Recovery Boundary:
 * - Room and DataStore are separate stores and cannot share a single hardware transaction.
 * - Export captures all Room tables inside a single read transaction under [mutationCoordinator].
 * - Restore validates payload & workspace invariants up front, filters invalid cross-device
 *   widget IDs, commits all Room tables + clears pre-restore shuffle snapshots in one Room
 *   transaction, then writes DataStore. If DataStore or rollback fails, both original and
 *   rollback errors are explicitly surfaced via [BackupRestoreRollbackException].
 */
class BackupRestoreHelper(
    private val installedAppDao: InstalledAppDao,
    private val workspaceDao: WorkspaceDao,
    private val preferences: ShellfriPreferences,
    private val database: ShellfriDatabase? = null,
    private val workspaceValidator: WorkspaceValidator = WorkspaceValidator(),
    private val mutationCoordinator: WorkspaceMutationCoordinator = WorkspaceMutationCoordinator.forDatabase(database),
    private val shuffleHistoryDao: ShuffleHistoryDao? = database?.shuffleHistoryDao(),
    private val isWidgetIdValid: (Int) -> Boolean = { false }
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        private val VALID_THEMES = setOf("DARK", "LIGHT", "SYSTEM")
        private val VALID_ICON_SIZES = setOf("SMALL", "MEDIUM", "LARGE")
        private val VALID_ITEM_TYPES = setOf(WorkspaceItemTypes.APP, WorkspaceItemTypes.FOLDER, WorkspaceItemTypes.WIDGET)
        private val VALID_CONTAINERS = setOf(WorkspaceContainers.HOME, WorkspaceContainers.DOCK)
    }

    private suspend fun <T> runInTransaction(block: suspend () -> T): T {
        return if (database != null) {
            database.withTransaction { block() }
        } else {
            block()
        }
    }

    suspend fun generateBackupJson(): String = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            generateBackupJsonLocked()
        }
    }

    private suspend fun generateBackupJsonLocked(validateExport: Boolean = true): String {
        val prefsConfig = preferences.getCurrentBackupConfig()

        data class RoomExportSnapshot(
            val pages: List<WorkspacePageEntity>,
            val folders: List<FolderEntity>,
            val items: List<WorkspaceItemEntity>,
            val members: List<FolderMemberEntity>,
            val drawers: List<DrawerLayoutEntity>,
            val focusApps: List<FocusAppEntity>
        )

        val roomSnapshot = runInTransaction {
            RoomExportSnapshot(
                pages = workspaceDao.getPagesList().sortedBy { it.pageIndex },
                folders = workspaceDao.getFoldersList(),
                items = workspaceDao.getWorkspaceItemsList(),
                members = workspaceDao.getFolderMembersList(),
                drawers = workspaceDao.getDrawerLayoutsList(),
                focusApps = workspaceDao.getFocusAppsList()
            )
        }

        val effectivePageCount = if (roomSnapshot.pages.isNotEmpty()) {
            roomSnapshot.pages.size
        } else {
            prefsConfig.pageCount.coerceAtLeast(1)
        }
        val effectiveDefaultPageIndex = roomSnapshot.pages
            .firstOrNull { it.isDefault }?.pageIndex
            ?: prefsConfig.defaultPageIndex.coerceIn(0, (effectivePageCount - 1).coerceAtLeast(0))

        val root = JSONObject()
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION)
        root.put("timestamp", System.currentTimeMillis())
        root.put("dateString", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))

        val prefsObj = JSONObject().apply {
            put("homeMode", prefsConfig.homeMode.name)
            put("shuffleInterval", prefsConfig.shuffleInterval.minutes)
            put("shuffleEnabled", prefsConfig.shuffleEnabled)
            put("shufflePaused", prefsConfig.shufflePaused)
            put("shuffleStrategy", prefsConfig.shuffleStrategy.name)
            put("occupancyOption", prefsConfig.occupancyOption.name)
            put("drawerShuffleEnabled", prefsConfig.drawerShuffleEnabled)
            put("themeMode", prefsConfig.themeMode)
            put("gridColumns", prefsConfig.gridColumns)
            put("gridSpacing", prefsConfig.gridSpacing.name)
            put("iconSize", prefsConfig.iconSize)
            put("showLabels", prefsConfig.showLabels)
            put("blurLevel", prefsConfig.blurLevel.name)
            put("blurOnShuffle", prefsConfig.blurOnShuffle)
            put("blurOnDistracting", prefsConfig.blurOnDistracting)
            put("blurOnFocus", prefsConfig.blurOnFocus)
            put("pageCount", effectivePageCount)
            put("defaultPageIndex", effectiveDefaultPageIndex)
            put("crossPageShuffle", prefsConfig.crossPageShuffle)
            put("dockCount", prefsConfig.dockCount)
            put("dockShowBackground", prefsConfig.dockShowBackground)
            put("dockShowLabels", prefsConfig.dockShowLabels)
            put("hideDistractingInFocus", prefsConfig.hideDistractingInFocus)
            put("focusSearchEnabled", prefsConfig.focusSearchEnabled)
            put("gestureSwipeUp", prefsConfig.gestureSwipeUp)
            put("gestureSwipeDown", prefsConfig.gestureSwipeDown)
            put("gestureDoubleTap", prefsConfig.gestureDoubleTap)
        }
        root.put("preferences", prefsObj)

        val pagesArr = JSONArray()
        for (page in roomSnapshot.pages) {
            pagesArr.put(
                JSONObject().apply {
                    put("pageId", page.pageId)
                    put("pageIndex", page.pageIndex)
                    put("isDefault", page.isDefault)
                    put("createdAt", page.createdAt)
                }
            )
        }
        root.put("pages", pagesArr)

        val foldersArr = JSONArray()
        for (folder in roomSnapshot.folders) {
            foldersArr.put(
                JSONObject().apply {
                    put("folderId", folder.folderId)
                    put("name", folder.name)
                    put("createdAt", folder.createdAt)
                }
            )
        }
        root.put("folders", foldersArr)

        val itemsArr = JSONArray()
        for (item in roomSnapshot.items) {
            itemsArr.put(
                JSONObject().apply {
                    put("itemId", item.itemId)
                    put("itemType", item.itemType)
                    put("containerType", item.containerType)
                    put("pageId", item.pageId ?: JSONObject.NULL)
                    put("packageName", item.packageName ?: JSONObject.NULL)
                    put("activityName", item.activityName ?: JSONObject.NULL)
                    put("folderId", item.folderId ?: JSONObject.NULL)
                    put("appWidgetId", item.appWidgetId ?: JSONObject.NULL)
                    put("columnSpan", item.columnSpan)
                    put("rowSpan", item.rowSpan)
                    put("rank", item.rank)
                    put("isFixed", item.isFixed)
                    put("isShuffled", item.isShuffled)
                    put("isHidden", item.isHidden)
                }
            )
        }
        root.put("workspaceItems", itemsArr)

        val membersArr = JSONArray()
        for (member in roomSnapshot.members) {
            membersArr.put(
                JSONObject().apply {
                    put("folderId", member.folderId)
                    put("packageName", member.packageName)
                    put("activityName", member.activityName)
                    put("rank", member.rank)
                    put("isFixed", member.isFixed)
                    put("isShuffled", member.isShuffled)
                }
            )
        }
        root.put("folderMembers", membersArr)

        val drawersArr = JSONArray()
        for (drawer in roomSnapshot.drawers) {
            drawersArr.put(
                JSONObject().apply {
                    put("packageName", drawer.packageName)
                    put("activityName", drawer.activityName)
                    put("drawerRank", drawer.drawerRank)
                    put("isDrawerShuffled", drawer.isDrawerShuffled)
                    put("isHidden", drawer.isHidden)
                    put("isFixed", drawer.isFixed)
                }
            )
        }
        root.put("drawerLayout", drawersArr)

        val focusArr = JSONArray()
        for (focus in roomSnapshot.focusApps) {
            focusArr.put(
                JSONObject().apply {
                    put("packageName", focus.packageName)
                    put("activityName", focus.activityName)
                    put("isVisibleInFocus", focus.isVisibleInFocus)
                    put("isEssentialInFocus", focus.isEssentialInFocus)
                    put("customOrder", focus.customOrder)
                }
            )
        }
        root.put("focusApps", focusArr)

        val json = root.toString(2)
        if (validateExport && roomSnapshot.pages.isNotEmpty()) {
            parseAndValidateBackup(json)
        }
        return json
    }

    suspend fun parseBackupSummary(jsonString: String): Result<BackupSummary> = withContext(Dispatchers.Default) {
        try {
            Result.success(parseAndValidateBackup(jsonString).summary)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun restoreBackup(jsonString: String): Result<Boolean> =
        restoreBackupDetailed(jsonString).map { true }

    suspend fun restoreBackupDetailed(jsonString: String): Result<BackupRestoreReport> = withContext(Dispatchers.IO) {
        try {
            val parsed = parseAndValidateBackup(jsonString)

            val report = mutationCoordinator.withMutationLock {
                data class PreRestoreSnapshot(
                    val installed: List<InstalledAppEntity>,
                    val pages: List<WorkspacePageEntity>,
                    val folders: List<FolderEntity>,
                    val items: List<WorkspaceItemEntity>,
                    val members: List<FolderMemberEntity>,
                    val drawers: List<DrawerLayoutEntity>,
                    val focusApps: List<FocusAppEntity>,
                    val shuffleSnapshots: List<ShuffleLayoutSnapshotEntity>
                )

                val preSnapshot = runInTransaction {
                    PreRestoreSnapshot(
                        installed = installedAppDao.getAllAppsList(),
                        pages = workspaceDao.getPagesList(),
                        folders = workspaceDao.getFoldersList(),
                        items = workspaceDao.getWorkspaceItemsList(),
                        members = workspaceDao.getFolderMembersList(),
                        drawers = workspaceDao.getDrawerLayoutsList(),
                        focusApps = workspaceDao.getFocusAppsList(),
                        shuffleSnapshots = shuffleHistoryDao?.getSnapshotsList().orEmpty()
                    )
                }
                val previousPrefsConfig = preferences.getCurrentBackupConfig()
                val previousJsonBackup = if (preSnapshot.pages.isNotEmpty()) {
                    runCatching { generateBackupJsonLocked(validateExport = false) }.getOrNull()
                } else {
                    null
                }

                val installedActive = preSnapshot.installed.filter { it.isInstalled }
                val bundle = when (parsed) {
                    is ParsedBackupPayload.V1Legacy -> buildNormalizedBundleFromV1(
                        payload = parsed,
                        installedApps = installedActive,
                        previousItems = preSnapshot.items
                    )
                    is ParsedBackupPayload.V2Normalized -> buildNormalizedBundleFromV2(
                        payload = parsed,
                        installedApps = installedActive,
                        previousItems = preSnapshot.items,
                        previousDrawers = preSnapshot.drawers,
                        previousFocus = preSnapshot.focusApps
                    )
                }

                val projectedValidation = workspaceValidator.validateNormalizedWorkspace(
                    installedApps = preSnapshot.installed,
                    pages = bundle.pages,
                    workspaceItems = bundle.workspaceItems,
                    folders = bundle.folders,
                    folderMembers = bundle.folderMembers,
                    drawerLayouts = bundle.drawerLayout,
                    focusApps = bundle.focusApps,
                    dockCount = parsed.preferences.dockCount,
                    drawerShuffleEnabled = parsed.preferences.drawerShuffleEnabled
                )
                require(projectedValidation.isValid) {
                    "Restored workspace failed validation: ${projectedValidation.errors.joinToString("; ")}"
                }

                runInTransaction {
                    workspaceDao.deleteAllFolderMembers()
                    workspaceDao.deleteAllWorkspaceItems()
                    workspaceDao.deleteAllFolders()
                    workspaceDao.deleteAllPages()
                    workspaceDao.deleteAllDrawerLayouts()
                    workspaceDao.deleteAllFocusApps()
                    // Invalidate pre-restore undo snapshots so post-restore undo cannot corrupt restored layout
                    shuffleHistoryDao?.clearSnapshots()

                    if (bundle.pages.isNotEmpty()) workspaceDao.insertPages(bundle.pages)
                    if (bundle.folders.isNotEmpty()) workspaceDao.upsertFolders(bundle.folders)
                    if (bundle.workspaceItems.isNotEmpty()) workspaceDao.upsertWorkspaceItems(bundle.workspaceItems)
                    if (bundle.folderMembers.isNotEmpty()) workspaceDao.upsertFolderMembers(bundle.folderMembers)
                    if (bundle.drawerLayout.isNotEmpty()) workspaceDao.upsertDrawerLayouts(bundle.drawerLayout)
                    if (bundle.focusApps.isNotEmpty()) workspaceDao.upsertFocusApps(bundle.focusApps)

                    val committedValidation = workspaceValidator.validateNormalizedWorkspace(
                        installedApps = installedAppDao.getAllAppsList(),
                        pages = workspaceDao.getPagesList(),
                        workspaceItems = workspaceDao.getWorkspaceItemsList(),
                        folders = workspaceDao.getFoldersList(),
                        folderMembers = workspaceDao.getFolderMembersList(),
                        drawerLayouts = workspaceDao.getDrawerLayoutsList(),
                        focusApps = workspaceDao.getFocusAppsList(),
                        dockCount = parsed.preferences.dockCount,
                        drawerShuffleEnabled = parsed.preferences.drawerShuffleEnabled
                    )
                    require(committedValidation.isValid) {
                        "Committed workspace failed invariant verification: ${committedValidation.errors.joinToString("; ")}"
                    }
                }

                try {
                    preferences.applyBackupConfig(
                        config = parsed.preferences,
                        previousLayoutBackupJson = previousJsonBackup
                    )
                } catch (prefError: Throwable) {
                    withContext(NonCancellable) {
                        val rollbackOutcome = runCatching {
                            runInTransaction {
                                workspaceDao.deleteAllFolderMembers()
                                workspaceDao.deleteAllWorkspaceItems()
                                workspaceDao.deleteAllFolders()
                                workspaceDao.deleteAllPages()
                                workspaceDao.deleteAllDrawerLayouts()
                                workspaceDao.deleteAllFocusApps()
                                shuffleHistoryDao?.clearSnapshots()

                                if (preSnapshot.pages.isNotEmpty()) workspaceDao.insertPages(preSnapshot.pages)
                                if (preSnapshot.folders.isNotEmpty()) workspaceDao.upsertFolders(preSnapshot.folders)
                                if (preSnapshot.items.isNotEmpty()) workspaceDao.upsertWorkspaceItems(preSnapshot.items)
                                if (preSnapshot.members.isNotEmpty()) workspaceDao.upsertFolderMembers(preSnapshot.members)
                                if (preSnapshot.drawers.isNotEmpty()) workspaceDao.upsertDrawerLayouts(preSnapshot.drawers)
                                if (preSnapshot.focusApps.isNotEmpty()) workspaceDao.upsertFocusApps(preSnapshot.focusApps)
                                for (snap in preSnapshot.shuffleSnapshots) {
                                    shuffleHistoryDao?.insertSnapshot(snap)
                                }
                            }
                            preferences.applyBackupConfig(previousPrefsConfig)
                        }
                        rollbackOutcome.exceptionOrNull()?.let { rollbackError ->
                            throw BackupRestoreRollbackException(
                                primaryError = prefError,
                                rollbackError = rollbackError
                            )
                        }
                    }
                    throw prefError
                }

                BackupRestoreReport(
                    sourceVersion = parsed.summary.version,
                    restoredAppCount = installedActive.size,
                    restoredFolderCount = bundle.folders.size,
                    restoredPageCount = bundle.pages.size,
                    restoredWidgetCount = bundle.workspaceItems.count { it.itemType == WorkspaceItemTypes.WIDGET },
                    skippedInvalidWidgets = bundle.skippedInvalidWidgets,
                    skippedUninstalledComponents = bundle.skippedUninstalledComponents,
                    clearedPreRestoreShuffleSnapshots = true
                )
            }

            Result.success(report)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun buildNormalizedBundleFromV1(
        payload: ParsedBackupPayload.V1Legacy,
        installedApps: List<InstalledAppEntity>,
        previousItems: List<WorkspaceItemEntity>
    ): RestoredWorkspaceBundle {
        val pageCount = payload.preferences.pageCount.coerceAtLeast(1)
        val defaultPageIndex = payload.preferences.defaultPageIndex.coerceIn(0, pageCount - 1)
        val now = System.currentTimeMillis()
        val pages = (0 until pageCount).map { idx ->
            WorkspacePageEntity(
                pageId = "page_$idx",
                pageIndex = idx,
                isDefault = idx == defaultPageIndex,
                createdAt = now
            )
        }
        val pageIdByIndex = pages.associate { it.pageIndex to it.pageId }

        val folders = mutableListOf<FolderEntity>()
        val items = mutableListOf<WorkspaceItemEntity>()
        val occupiedHomeByPageId = mutableMapOf<String, MutableSet<Int>>()

        fun allocateHomeRank(pageId: String, preferred: Int): Int {
            val occupied = occupiedHomeByPageId.getOrPut(pageId) { mutableSetOf() }
            var candidate = preferred.coerceAtLeast(0)
            while (candidate in occupied) candidate++
            occupied.add(candidate)
            return candidate
        }

        for (folder in payload.folders) {
            folders.add(FolderEntity(folderId = folder.id, name = folder.name, createdAt = folder.createdAt))
            val pageId = pageIdByIndex[folder.pageIndex.coerceIn(0, pageCount - 1)] ?: pages.first().pageId
            val rank = allocateHomeRank(pageId, folder.slotIndex)
            items.add(
                WorkspaceItemEntity(
                    itemId = "folder_${folder.id}",
                    itemType = WorkspaceItemTypes.FOLDER,
                    containerType = WorkspaceContainers.HOME,
                    pageId = pageId,
                    folderId = folder.id,
                    rank = rank
                )
            )
        }

        // Match v1 entries deterministically to installed launcher activities:
        // 1) exact (packageName, activityName) match if activityName is non-blank
        // 2) deterministic primary activity sorted by activityName ASC when v1 omitted or changed activityName
        val installedByExact = installedApps.associateBy { it.packageName to it.activityName }
        val installedByPackageSorted = installedApps.groupBy { it.packageName }
            .mapValues { (_, list) -> list.sortedBy { it.activityName } }

        val matchedComponents = mutableSetOf<Pair<String, String>>()
        val folderMemberRanks = mutableMapOf<String, Int>()
        val dockApps = mutableListOf<Pair<InstalledAppEntity, ParsedV1BackupApp>>()
        val members = mutableListOf<FolderMemberEntity>()
        val drawers = mutableListOf<DrawerLayoutEntity>()
        val focusList = mutableListOf<FocusAppEntity>()
        var skippedUninstalled = 0

        for (v1App in payload.apps) {
            val resolved = if (v1App.activityName.isNotBlank()) {
                installedByExact[v1App.packageName to v1App.activityName]
                    ?: installedByPackageSorted[v1App.packageName]?.firstOrNull { (it.packageName to it.activityName) !in matchedComponents }
            } else {
                installedByPackageSorted[v1App.packageName]?.firstOrNull { (it.packageName to it.activityName) !in matchedComponents }
            }
            if (resolved == null) {
                skippedUninstalled++
                continue
            }

            val comp = resolved.packageName to resolved.activityName
            if (!matchedComponents.add(comp)) continue

            drawers.add(
                DrawerLayoutEntity(
                    packageName = resolved.packageName,
                    activityName = resolved.activityName,
                    drawerRank = v1App.drawerSlotIndex,
                    isDrawerShuffled = v1App.isDrawerShuffled,
                    isHidden = v1App.isHidden,
                    isFixed = v1App.isFixed
                )
            )
            focusList.add(
                FocusAppEntity(
                    packageName = resolved.packageName,
                    activityName = resolved.activityName,
                    isVisibleInFocus = v1App.isVisibleInFocus,
                    isEssentialInFocus = v1App.isEssentialInFocus,
                    customOrder = 0
                )
            )

            if (v1App.folderId != null && folders.any { it.folderId == v1App.folderId }) {
                val mRank = folderMemberRanks.getOrDefault(v1App.folderId, 0)
                folderMemberRanks[v1App.folderId] = mRank + 1
                members.add(
                    FolderMemberEntity(
                        folderId = v1App.folderId,
                        packageName = resolved.packageName,
                        activityName = resolved.activityName,
                        rank = mRank,
                        isFixed = v1App.isFixed,
                        isShuffled = v1App.isShuffled && !v1App.isFixed
                    )
                )
            } else {
                if (v1App.isPinnedToDock) {
                    dockApps.add(resolved to v1App)
                }
                if (v1App.isOnHomeScreen && !v1App.isPinnedToDock) {
                    val pageId = pageIdByIndex[v1App.pageIndex.coerceIn(0, pageCount - 1)] ?: pages.first().pageId
                    val rank = allocateHomeRank(pageId, v1App.slotIndex)
                    items.add(
                        WorkspaceItemEntity(
                            itemId = "home_${resolved.packageName}_${resolved.activityName}",
                            itemType = WorkspaceItemTypes.APP,
                            containerType = WorkspaceContainers.HOME,
                            pageId = pageId,
                            packageName = resolved.packageName,
                            activityName = resolved.activityName,
                            rank = rank,
                            isFixed = v1App.isFixed,
                            isShuffled = v1App.isShuffled && !v1App.isFixed,
                            isHidden = v1App.isHidden
                        )
                    )
                }
            }
        }

        dockApps.sortedWith(compareBy<Pair<InstalledAppEntity, ParsedV1BackupApp>> { it.second.slotIndex.coerceAtLeast(0) }.thenBy { it.first.packageName })
            .take(payload.preferences.dockCount)
            .forEachIndexed { dockRank, (resolved, v1App) ->
                items.add(
                    WorkspaceItemEntity(
                        itemId = "dock_${resolved.packageName}_${resolved.activityName}",
                        itemType = WorkspaceItemTypes.APP,
                        containerType = WorkspaceContainers.DOCK,
                        pageId = null,
                        packageName = resolved.packageName,
                        activityName = resolved.activityName,
                        rank = dockRank,
                        isFixed = v1App.isFixed,
                        isShuffled = v1App.isShuffled && !v1App.isFixed,
                        isHidden = v1App.isHidden
                    )
                )
            }

        // Reconcile device-installed apps not present in v1 backup
        val prevHomeByComp = previousItems
            .filter { it.containerType == WorkspaceContainers.HOME && it.itemType == WorkspaceItemTypes.APP && it.packageName != null }
            .associateBy { it.packageName!! to (it.activityName ?: "") }

        for (extra in installedApps) {
            val comp = extra.packageName to extra.activityName
            if (comp in matchedComponents) continue
            drawers.add(DrawerLayoutEntity(extra.packageName, extra.activityName))
            focusList.add(FocusAppEntity(extra.packageName, extra.activityName))
            val prevHome = prevHomeByComp[comp]
            if (prevHome != null) {
                val pageId = pages.first().pageId
                val safeRank = allocateHomeRank(pageId, prevHome.rank)
                items.add(
                    prevHome.copy(
                        pageId = pageId,
                        rank = safeRank
                    )
                )
            }
        }

        return RestoredWorkspaceBundle(
            pages = pages,
            folders = folders,
            workspaceItems = items,
            folderMembers = members,
            drawerLayout = drawers,
            focusApps = focusList,
            skippedUninstalledComponents = skippedUninstalled
        )
    }

    private fun buildNormalizedBundleFromV2(
        payload: ParsedBackupPayload.V2Normalized,
        installedApps: List<InstalledAppEntity>,
        previousItems: List<WorkspaceItemEntity>,
        previousDrawers: List<DrawerLayoutEntity>,
        previousFocus: List<FocusAppEntity>
    ): RestoredWorkspaceBundle {
        val installedSet = installedApps.map { it.packageName to it.activityName }.toSet()
        val pages = payload.pages.sortedBy { it.pageIndex }
        val folders = payload.folders
        val validFolderIds = folders.map { it.folderId }.toSet()

        val restoredItems = mutableListOf<WorkspaceItemEntity>()
        val backedComponents = mutableSetOf<Pair<String, String>>()
        val skippedUninstalledComps = mutableSetOf<Pair<String, String>>()
        val skippedWidgets = mutableListOf<Int>()
        val occupiedHomeByPageId = mutableMapOf<String, MutableSet<Int>>()

        for (item in payload.workspaceItems) {
            when (item.itemType) {
                WorkspaceItemTypes.APP -> {
                    val pkg = item.packageName ?: continue
                    val act = item.activityName ?: ""
                    val comp = pkg to act
                    if (comp !in installedSet) {
                        skippedUninstalledComps.add(comp)
                        continue
                    }
                    backedComponents.add(comp)
                }
                WorkspaceItemTypes.WIDGET -> {
                    val widgetId = item.appWidgetId ?: continue
                    if (!isWidgetIdValid(widgetId)) {
                        skippedWidgets.add(widgetId)
                        continue
                    }
                }
            }
            restoredItems.add(item)
            if (item.containerType == WorkspaceContainers.HOME && item.pageId != null) {
                val set = occupiedHomeByPageId.getOrPut(item.pageId) { mutableSetOf() }
                val span = item.columnSpan.coerceAtLeast(1) * item.rowSpan.coerceAtLeast(1)
                for (offset in 0 until span) set.add(item.rank + offset)
            }
        }

        val nonDockItems = restoredItems.toMutableList()

        val restoredMembers = payload.folderMembers.filter { member ->
            val comp = member.packageName to member.activityName
            val keep = member.folderId in validFolderIds && comp in installedSet
            if (keep) {
                backedComponents.add(comp)
            } else if (comp !in installedSet) {
                skippedUninstalledComps.add(comp)
            }
            keep
        }

        val restoredDrawers = payload.drawerLayout.filter { (it.packageName to it.activityName) in installedSet }.toMutableList()
        val drawerBackedSet = restoredDrawers.map { it.packageName to it.activityName }.toSet()
        val prevDrawerByComp = previousDrawers.associateBy { it.packageName to it.activityName }

        val restoredFocus = payload.focusApps.filter { (it.packageName to it.activityName) in installedSet }.toMutableList()
        val focusBackedSet = restoredFocus.map { it.packageName to it.activityName }.toSet()
        val prevFocusByComp = previousFocus.associateBy { it.packageName to it.activityName }

        // Reconcile device-installed apps added after backup was exported
        val prevHomeByComp = previousItems
            .filter { it.containerType == WorkspaceContainers.HOME && it.itemType == WorkspaceItemTypes.APP && it.packageName != null }
            .associateBy { it.packageName!! to (it.activityName ?: "") }

        val fallbackPageId = pages.first().pageId
        val validPageIds = pages.map { it.pageId }.toSet()

        fun allocateHomeRank(pageId: String, preferred: Int): Int {
            val occupied = occupiedHomeByPageId.getOrPut(pageId) { mutableSetOf() }
            var candidate = preferred.coerceAtLeast(0)
            while (candidate in occupied) candidate++
            occupied.add(candidate)
            return candidate
        }

        for (extra in installedApps) {
            val comp = extra.packageName to extra.activityName
            if (comp !in drawerBackedSet) {
                restoredDrawers.add(
                    prevDrawerByComp[comp]?.copy(drawerRank = -1) ?: DrawerLayoutEntity(extra.packageName, extra.activityName)
                )
            }
            if (comp !in focusBackedSet) {
                restoredFocus.add(
                    prevFocusByComp[comp] ?: FocusAppEntity(extra.packageName, extra.activityName)
                )
            }
            if (comp !in backedComponents) {
                val prevHome = prevHomeByComp[comp]
                if (prevHome != null) {
                    val safePageId = prevHome.pageId?.takeIf { it in validPageIds } ?: fallbackPageId
                    val safeRank = allocateHomeRank(safePageId, prevHome.rank)
                    nonDockItems.add(
                        prevHome.copy(
                            pageId = safePageId,
                            rank = safeRank
                        )
                    )
                }
            }
        }

        return RestoredWorkspaceBundle(
            pages = pages,
            folders = folders,
            workspaceItems = nonDockItems,
            folderMembers = restoredMembers,
            drawerLayout = restoredDrawers,
            focusApps = restoredFocus,
            skippedInvalidWidgets = skippedWidgets,
            skippedUninstalledComponents = skippedUninstalledComps.size
        )
    }

    private fun parseAndValidateBackup(jsonString: String): ParsedBackupPayload {
        require(jsonString.isNotBlank()) { "Backup JSON is empty" }
        val root = JSONObject(jsonString)

        require(root.has("schemaVersion")) { "Missing required 'schemaVersion' field" }
        val versionRaw = root.get("schemaVersion")
        require(versionRaw is Int || versionRaw is Long) {
            "Invalid 'schemaVersion' type: $versionRaw"
        }
        val version = (versionRaw as Number).toInt()
        require(version in 1..CURRENT_SCHEMA_VERSION) {
            "Unsupported backup schemaVersion $version (supported: 1..$CURRENT_SCHEMA_VERSION)"
        }

        val timestamp = if (root.has("timestamp")) root.getLong("timestamp") else System.currentTimeMillis()
        require(timestamp >= 0L) { "Invalid backup timestamp: $timestamp" }

        require(root.has("preferences") && root.get("preferences") is JSONObject) {
            "Missing or invalid 'preferences' object"
        }
        val prefsConfig = parseAndValidatePreferences(root.getJSONObject("preferences"))

        return if (version == 1) {
            parseAndValidateV1(root, timestamp, prefsConfig)
        } else {
            parseAndValidateV2(root, timestamp, prefsConfig)
        }
    }

    private fun parseAndValidateV1(
        root: JSONObject,
        timestamp: Long,
        prefsConfig: BackupPreferencesConfig
    ): ParsedBackupPayload.V1Legacy {
        require(root.has("apps") && root.get("apps") is JSONArray) {
            "Missing or invalid 'apps' array"
        }
        val parsedApps = parseV1AppsArray(root.getJSONArray("apps"))

        val parsedFolders = if (root.has("folders") && !root.isNull("folders")) {
            require(root.get("folders") is JSONArray) { "Invalid 'folders' array" }
            parseV1FoldersArray(root.getJSONArray("folders"))
        } else {
            emptyList()
        }

        val validation = workspaceValidator.validateWorkspace(
            apps = parsedApps.map { it.toDomain() },
            folders = parsedFolders,
            pageCount = prefsConfig.pageCount,
            dockCount = prefsConfig.dockCount,
            drawerShuffleEnabled = prefsConfig.drawerShuffleEnabled
        )
        require(validation.isValid) {
            "Backup layout validation failed: ${validation.errors.joinToString("; ")}"
        }

        val summary = BackupSummary(
            version = 1,
            timestamp = timestamp,
            appCount = parsedApps.size,
            folderCount = parsedFolders.size,
            themeMode = prefsConfig.themeMode,
            gridColumns = prefsConfig.gridColumns,
            pageCount = prefsConfig.pageCount,
            dockCount = prefsConfig.dockCount
        )
        return ParsedBackupPayload.V1Legacy(
            summary = summary,
            preferences = prefsConfig,
            apps = parsedApps,
            folders = parsedFolders
        )
    }

    private fun parseAndValidateV2(
        root: JSONObject,
        timestamp: Long,
        prefsConfig: BackupPreferencesConfig
    ): ParsedBackupPayload.V2Normalized {
        require(root.has("pages") && root.get("pages") is JSONArray) { "Missing or invalid 'pages' array" }
        require(root.has("folders") && root.get("folders") is JSONArray) { "Missing or invalid 'folders' array" }
        require(root.has("workspaceItems") && root.get("workspaceItems") is JSONArray) { "Missing or invalid 'workspaceItems' array" }
        require(root.has("folderMembers") && root.get("folderMembers") is JSONArray) { "Missing or invalid 'folderMembers' array" }
        require(root.has("drawerLayout") && root.get("drawerLayout") is JSONArray) { "Missing or invalid 'drawerLayout' array" }
        require(root.has("focusApps") && root.get("focusApps") is JSONArray) { "Missing or invalid 'focusApps' array" }

        val pages = parseV2PagesArray(root.getJSONArray("pages"))
        require(pages.isNotEmpty()) { "Backup v2 must contain at least one page" }
        require(pages.size == prefsConfig.pageCount) {
            "Backup v2 pages count (${pages.size}) does not match preferences.pageCount (${prefsConfig.pageCount})"
        }
        val expectedIndices = pages.indices.toSet()
        require(pages.map { it.pageIndex }.toSet() == expectedIndices) {
            "Backup v2 pageIndex values must be contiguous 0..${pages.size - 1}"
        }
        val defaultPages = pages.filter { it.isDefault }
        require(defaultPages.size <= 1) {
            "Backup v2 contains multiple default pages (${defaultPages.map { it.pageId }})"
        }
        if (defaultPages.size == 1) {
            require(defaultPages.single().pageIndex == prefsConfig.defaultPageIndex) {
                "Backup v2 default page index (${defaultPages.single().pageIndex}) disagrees with preferences.defaultPageIndex (${prefsConfig.defaultPageIndex})"
            }
        }

        val folders = parseV2FoldersArray(root.getJSONArray("folders"))
        val items = parseV2WorkspaceItemsArray(root.getJSONArray("workspaceItems"))
        val members = parseV2FolderMembersArray(root.getJSONArray("folderMembers"))
        val drawers = parseV2DrawerLayoutArray(root.getJSONArray("drawerLayout"))
        val focusApps = parseV2FocusAppsArray(root.getJSONArray("focusApps"))

        // Validate normalized graph structurally before touching storage
        val pageIds = pages.map { it.pageId }.toSet()
        val folderIds = folders.map { it.folderId }.toSet()
        val occupiedHomeCells = mutableSetOf<Pair<String, Int>>()
        val occupiedDockRanks = mutableSetOf<Int>()
        val homeComponents = mutableSetOf<Pair<String, String>>()
        val dockComponents = mutableSetOf<Pair<String, String>>()
        val placedFolders = mutableSetOf<String>()
        val seenWidgetIds = mutableSetOf<Int>()
        var dockCount = 0

        for (item in items) {
            require(item.rank >= 0) { "WorkspaceItem ${item.itemId} has negative rank ${item.rank}" }
            require(!(item.isFixed && item.isShuffled)) { "WorkspaceItem ${item.itemId} cannot be both fixed and shuffled" }
            when (item.containerType) {
                WorkspaceContainers.HOME -> {
                    val pId = item.pageId
                    require(pId != null && pId in pageIds) { "WorkspaceItem ${item.itemId} references missing pageId=$pId" }
                    require(item.columnSpan >= 1 && item.rowSpan >= 1) { "Invalid widget/item span on ${item.itemId}" }
                    val cells = item.columnSpan * item.rowSpan
                    for (offset in 0 until cells) {
                        require(occupiedHomeCells.add(pId to (item.rank + offset))) {
                            "Duplicate or overlapping HOME placement at page=$pId rank=${item.rank + offset}"
                        }
                    }
                }
                WorkspaceContainers.DOCK -> {
                    dockCount++
                    require(item.pageId == null) { "DOCK item ${item.itemId} must have null pageId" }
                    require(occupiedDockRanks.add(item.rank)) { "Duplicate DOCK rank ${item.rank}" }
                }
            }
            when (item.itemType) {
                WorkspaceItemTypes.APP -> {
                    val pkg = item.packageName
                    val act = item.activityName
                    require(!pkg.isNullOrBlank() && act != null) { "APP item ${item.itemId} missing component identity" }
                    val comp = pkg to act
                    if (item.containerType == WorkspaceContainers.HOME) {
                        require(homeComponents.add(comp)) { "Duplicate HOME item for component $pkg/$act" }
                    } else {
                        require(dockComponents.add(comp)) { "Duplicate DOCK item for component $pkg/$act" }
                    }
                }
                WorkspaceItemTypes.FOLDER -> {
                    val fId = item.folderId
                    require(!fId.isNullOrBlank() && fId in folderIds) {
                        "FOLDER item ${item.itemId} references missing folderId=$fId"
                    }
                    require(placedFolders.add(fId)) { "Duplicate placement for folder $fId" }
                }
                WorkspaceItemTypes.WIDGET -> {
                    val wId = item.appWidgetId
                    require(wId != null && wId > 0) { "Invalid appWidgetId on ${item.itemId}" }
                    require(seenWidgetIds.add(wId)) { "Duplicate appWidgetId $wId" }
                }
            }
        }
        require(dockCount <= prefsConfig.dockCount) {
            "Backup v2 DOCK item count ($dockCount) exceeds dockCount (${prefsConfig.dockCount})"
        }

        val memberRanks = mutableMapOf<String, MutableSet<Int>>()
        for (member in members) {
            require(member.folderId in folderIds) { "FolderMember references missing folderId=${member.folderId}" }
            require(member.rank >= 0) { "FolderMember has negative rank ${member.rank}" }
            val comp = member.packageName to member.activityName
            require(comp !in homeComponents) {
                "Component ${member.packageName}/${member.activityName} cannot be both in folder ${member.folderId} and on HOME"
            }
            val ranks = memberRanks.getOrPut(member.folderId) { mutableSetOf() }
            require(ranks.add(member.rank)) { "Duplicate member rank ${member.rank} in folder ${member.folderId}" }
        }

        val occupiedDrawerRanks = mutableSetOf<Int>()
        for (drawer in drawers) {
            require(drawer.drawerRank >= -1) { "Invalid drawerRank ${drawer.drawerRank}" }
            if (prefsConfig.drawerShuffleEnabled && drawer.isDrawerShuffled && !drawer.isFixed && drawer.drawerRank >= 0) {
                require(occupiedDrawerRanks.add(drawer.drawerRank)) {
                    "Duplicate drawerRank ${drawer.drawerRank} in drawerLayout"
                }
            }
        }

        val distinctComponents = buildSet {
            items.filter { it.itemType == WorkspaceItemTypes.APP && it.packageName != null }
                .forEach { add(it.packageName!! to (it.activityName ?: "")) }
            members.forEach { add(it.packageName to it.activityName) }
            drawers.forEach { add(it.packageName to it.activityName) }
            focusApps.forEach { add(it.packageName to it.activityName) }
        }

        val summary = BackupSummary(
            version = 2,
            timestamp = timestamp,
            appCount = distinctComponents.size,
            folderCount = folders.size,
            themeMode = prefsConfig.themeMode,
            gridColumns = prefsConfig.gridColumns,
            pageCount = pages.size,
            dockCount = prefsConfig.dockCount
        )

        return ParsedBackupPayload.V2Normalized(
            summary = summary,
            preferences = prefsConfig,
            pages = pages,
            folders = folders,
            workspaceItems = items,
            folderMembers = members,
            drawerLayout = drawers,
            focusApps = focusApps
        )
    }

    private fun parseAndValidatePreferences(prefsObj: JSONObject): BackupPreferencesConfig {
        require(prefsObj.has("themeMode") && !prefsObj.isNull("themeMode")) { "Missing required preferences.themeMode" }
        require(prefsObj.has("gridColumns") && !prefsObj.isNull("gridColumns")) { "Missing required preferences.gridColumns" }
        require(prefsObj.has("pageCount") && !prefsObj.isNull("pageCount")) { "Missing required preferences.pageCount" }
        require(prefsObj.has("dockCount") && !prefsObj.isNull("dockCount")) { "Missing required preferences.dockCount" }

        val homeModeName = prefsObj.optString("homeMode", HomeMode.NORMAL.name)
        val homeMode = runCatching { HomeMode.valueOf(homeModeName) }
            .getOrElse { throw IllegalArgumentException("Invalid homeMode in backup: $homeModeName") }

        val intervalMinutes = prefsObj.optInt("shuffleInterval", ShuffleInterval.MIN_30.minutes)
        require(ShuffleInterval.entries.any { it.minutes == intervalMinutes }) {
            "Invalid shuffleInterval in backup: $intervalMinutes"
        }
        val shuffleInterval = ShuffleInterval.fromMinutes(intervalMinutes)

        val strategyName = prefsObj.optString("shuffleStrategy", ShuffleStrategy.FULL_RANDOM.name)
        val shuffleStrategy = runCatching { ShuffleStrategy.valueOf(strategyName) }
            .getOrElse { throw IllegalArgumentException("Invalid shuffleStrategy in backup: $strategyName") }

        val occupancyName = prefsObj.optString("occupancyOption", OccupancyOption.PRESERVE_EMPTY_SPACES.name)
        val occupancyOption = runCatching { OccupancyOption.valueOf(occupancyName) }
            .getOrElse { throw IllegalArgumentException("Invalid occupancyOption in backup: $occupancyName") }

        val gridSpacingName = prefsObj.optString("gridSpacing", GridSpacing.COMFORTABLE.name)
        val gridSpacing = runCatching { GridSpacing.valueOf(gridSpacingName) }
            .getOrElse { throw IllegalArgumentException("Invalid gridSpacing in backup: $gridSpacingName") }

        val blurLevelName = prefsObj.optString("blurLevel", BlurLevel.DISABLED.name)
        val blurLevel = runCatching { BlurLevel.valueOf(blurLevelName) }
            .getOrElse { throw IllegalArgumentException("Invalid blurLevel in backup: $blurLevelName") }

        val themeMode = prefsObj.getString("themeMode")
        require(themeMode in VALID_THEMES) { "Invalid themeMode in backup: $themeMode" }

        val gridColumns = prefsObj.getInt("gridColumns")
        require(gridColumns in 3..6) { "Invalid gridColumns in backup: $gridColumns" }

        val iconSize = prefsObj.optString("iconSize", "MEDIUM")
        require(iconSize in VALID_ICON_SIZES) { "Invalid iconSize in backup: $iconSize" }

        val pageCount = prefsObj.getInt("pageCount")
        require(pageCount >= 1) { "Invalid pageCount in backup: $pageCount" }

        val defaultPageIndex = prefsObj.optInt("defaultPageIndex", 0)
        require(defaultPageIndex in 0 until pageCount) {
            "Invalid defaultPageIndex $defaultPageIndex for pageCount $pageCount"
        }

        val dockCount = prefsObj.getInt("dockCount")
        require(dockCount in 3..7) { "Invalid dockCount in backup: $dockCount" }

        return BackupPreferencesConfig(
            homeMode = homeMode,
            shuffleInterval = shuffleInterval,
            shuffleEnabled = prefsObj.optBoolean("shuffleEnabled", true),
            shufflePaused = prefsObj.optBoolean("shufflePaused", false),
            shuffleStrategy = shuffleStrategy,
            occupancyOption = occupancyOption,
            drawerShuffleEnabled = prefsObj.optBoolean("drawerShuffleEnabled", false),
            themeMode = themeMode,
            gridColumns = gridColumns,
            gridSpacing = gridSpacing,
            iconSize = iconSize,
            showLabels = prefsObj.optBoolean("showLabels", true),
            blurLevel = blurLevel,
            blurOnShuffle = prefsObj.optBoolean("blurOnShuffle", true),
            blurOnDistracting = prefsObj.optBoolean("blurOnDistracting", false),
            blurOnFocus = prefsObj.optBoolean("blurOnFocus", false),
            pageCount = pageCount,
            defaultPageIndex = defaultPageIndex,
            crossPageShuffle = prefsObj.optBoolean("crossPageShuffle", true),
            dockCount = dockCount,
            dockShowBackground = prefsObj.optBoolean("dockShowBackground", true),
            dockShowLabels = prefsObj.optBoolean("dockShowLabels", false),
            hideDistractingInFocus = prefsObj.optBoolean("hideDistractingInFocus", false),
            focusSearchEnabled = prefsObj.optBoolean("focusSearchEnabled", true),
            gestureSwipeUp = prefsObj.optString("gestureSwipeUp", "DRAWER"),
            gestureSwipeDown = prefsObj.optString("gestureSwipeDown", "NOTIFICATIONS"),
            gestureDoubleTap = prefsObj.optString("gestureDoubleTap", "NONE")
        )
    }

    private fun parseV1AppsArray(appsArr: JSONArray): List<ParsedV1BackupApp> {
        val parsed = mutableListOf<ParsedV1BackupApp>()
        val seenComponents = mutableSetOf<Pair<String, String>>()

        for (i in 0 until appsArr.length()) {
            val item = appsArr.get(i)
            require(item is JSONObject) { "App entry at index $i is not a JSON object" }

            require(item.has("packageName") && !item.isNull("packageName")) {
                "App entry at index $i is missing packageName"
            }
            val pkg = item.getString("packageName").trim()
            require(pkg.isNotEmpty()) { "App entry at index $i has blank packageName" }

            val act = item.optString("activityName", "")
            require(seenComponents.add(pkg to act)) { "Duplicate app component in backup: $pkg/$act" }

            require(item.has("label") && !item.isNull("label")) {
                "App entry $pkg is missing label"
            }
            val label = item.getString("label").trim()
            require(label.isNotEmpty()) { "App entry $pkg has blank label" }

            val folderIdVal = if (item.isNull("folderId")) {
                null
            } else {
                item.optString("folderId", "").takeIf { it.isNotBlank() }
            }

            parsed.add(
                ParsedV1BackupApp(
                    packageName = pkg,
                    activityName = act,
                    label = label,
                    isShuffled = item.optBoolean("isShuffled", false),
                    isFixed = item.optBoolean("isFixed", false),
                    slotIndex = item.optInt("slotIndex", 0),
                    pageIndex = item.optInt("pageIndex", 0),
                    folderId = folderIdVal,
                    isPinnedToDock = item.optBoolean("isPinnedToDock", false),
                    isOnHomeScreen = item.optBoolean("isOnHomeScreen", true),
                    isVisibleInFocus = item.optBoolean("isVisibleInFocus", false),
                    isEssentialInFocus = item.optBoolean("isEssentialInFocus", false),
                    isHidden = item.optBoolean("isHidden", false),
                    drawerSlotIndex = item.optInt("drawerSlotIndex", -1),
                    isDrawerShuffled = item.optBoolean("isDrawerShuffled", false)
                )
            )
        }
        return parsed
    }

    private fun parseV1FoldersArray(foldersArr: JSONArray): List<FolderItem> {
        val parsed = mutableListOf<FolderItem>()
        val seenIds = mutableSetOf<String>()

        for (i in 0 until foldersArr.length()) {
            val item = foldersArr.get(i)
            require(item is JSONObject) { "Folder entry at index $i is not a JSON object" }

            require(item.has("id") && !item.isNull("id")) { "Folder at index $i is missing id" }
            val id = item.getString("id").trim()
            require(id.isNotEmpty()) { "Folder at index $i has blank id" }
            require(seenIds.add(id)) { "Duplicate folder id in backup: $id" }

            require(item.has("name") && !item.isNull("name")) { "Folder $id is missing name" }
            val name = item.getString("name").trim()
            require(name.isNotEmpty()) { "Folder $id has blank name" }

            parsed.add(
                FolderItem(
                    id = id,
                    name = name,
                    pageIndex = item.optInt("pageIndex", 0),
                    slotIndex = item.optInt("slotIndex", 0),
                    createdAt = item.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }
        return parsed
    }

    private fun parseV2PagesArray(arr: JSONArray): List<WorkspacePageEntity> {
        val list = mutableListOf<WorkspacePageEntity>()
        val seenIds = mutableSetOf<String>()
        val seenIndices = mutableSetOf<Int>()
        for (i in 0 until arr.length()) {
            val obj = arr.get(i) as? JSONObject ?: throw IllegalArgumentException("Page at $i is not an object")
            val pageId = obj.optString("pageId", "").trim()
            require(pageId.isNotEmpty()) { "Page at $i has blank pageId" }
            require(seenIds.add(pageId)) { "Duplicate pageId: $pageId" }
            require(obj.has("pageIndex") && !obj.isNull("pageIndex")) { "Page $pageId missing pageIndex" }
            val pageIndex = obj.getInt("pageIndex")
            require(pageIndex >= 0) { "Page $pageId has negative pageIndex $pageIndex" }
            require(seenIndices.add(pageIndex)) { "Duplicate pageIndex $pageIndex" }
            list.add(
                WorkspacePageEntity(
                    pageId = pageId,
                    pageIndex = pageIndex,
                    isDefault = obj.optBoolean("isDefault", pageIndex == 0),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }
        return list
    }

    private fun parseV2FoldersArray(arr: JSONArray): List<FolderEntity> {
        val list = mutableListOf<FolderEntity>()
        val seenIds = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.get(i) as? JSONObject ?: throw IllegalArgumentException("Folder at $i is not an object")
            val folderId = obj.optString("folderId", "").trim()
            require(folderId.isNotEmpty()) { "Folder at $i has blank folderId" }
            require(seenIds.add(folderId)) { "Duplicate folderId: $folderId" }
            val name = obj.optString("name", "").trim()
            require(name.isNotEmpty()) { "Folder $folderId has blank name" }
            list.add(
                FolderEntity(
                    folderId = folderId,
                    name = name,
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }
        return list
    }

    private fun parseV2WorkspaceItemsArray(arr: JSONArray): List<WorkspaceItemEntity> {
        val list = mutableListOf<WorkspaceItemEntity>()
        val seenIds = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.get(i) as? JSONObject ?: throw IllegalArgumentException("WorkspaceItem at $i is not an object")
            val itemId = obj.optString("itemId", "").trim()
            require(itemId.isNotEmpty()) { "WorkspaceItem at $i has blank itemId" }
            require(seenIds.add(itemId)) { "Duplicate itemId: $itemId" }
            val itemType = obj.optString("itemType", "")
            require(itemType in VALID_ITEM_TYPES) { "Invalid itemType '$itemType' on $itemId" }
            val containerType = obj.optString("containerType", "")
            require(containerType in VALID_CONTAINERS) { "Invalid containerType '$containerType' on $itemId" }
            require(obj.has("rank") && !obj.isNull("rank")) { "WorkspaceItem $itemId missing rank" }

            list.add(
                WorkspaceItemEntity(
                    itemId = itemId,
                    itemType = itemType,
                    containerType = containerType,
                    pageId = if (obj.isNull("pageId")) null else obj.optString("pageId", "").takeIf { it.isNotBlank() },
                    packageName = if (obj.isNull("packageName")) null else obj.optString("packageName", "").takeIf { it.isNotBlank() },
                    activityName = if (obj.isNull("activityName")) null else obj.optString("activityName", ""),
                    folderId = if (obj.isNull("folderId")) null else obj.optString("folderId", "").takeIf { it.isNotBlank() },
                    appWidgetId = if (obj.isNull("appWidgetId")) null else obj.getInt("appWidgetId"),
                    columnSpan = obj.optInt("columnSpan", 1),
                    rowSpan = obj.optInt("rowSpan", 1),
                    rank = obj.getInt("rank"),
                    isFixed = obj.optBoolean("isFixed", false),
                    isShuffled = obj.optBoolean("isShuffled", false),
                    isHidden = obj.optBoolean("isHidden", false)
                )
            )
        }
        return list
    }

    private fun parseV2FolderMembersArray(arr: JSONArray): List<FolderMemberEntity> {
        val list = mutableListOf<FolderMemberEntity>()
        val seen = mutableSetOf<Triple<String, String, String>>()
        for (i in 0 until arr.length()) {
            val obj = arr.get(i) as? JSONObject ?: throw IllegalArgumentException("FolderMember at $i is not an object")
            val folderId = obj.optString("folderId", "").trim()
            val pkg = obj.optString("packageName", "").trim()
            require(folderId.isNotEmpty() && pkg.isNotEmpty()) { "FolderMember at $i missing folderId or packageName" }
            require(obj.has("activityName") && !obj.isNull("activityName")) { "FolderMember $pkg missing activityName" }
            val act = obj.getString("activityName")
            require(seen.add(Triple(folderId, pkg, act))) { "Duplicate FolderMember $folderId/$pkg/$act" }
            require(obj.has("rank") && !obj.isNull("rank")) { "FolderMember $pkg/$act missing rank" }

            list.add(
                FolderMemberEntity(
                    folderId = folderId,
                    packageName = pkg,
                    activityName = act,
                    rank = obj.getInt("rank"),
                    isFixed = obj.optBoolean("isFixed", false),
                    isShuffled = obj.optBoolean("isShuffled", false)
                )
            )
        }
        return list
    }

    private fun parseV2DrawerLayoutArray(arr: JSONArray): List<DrawerLayoutEntity> {
        val list = mutableListOf<DrawerLayoutEntity>()
        val seen = mutableSetOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val obj = arr.get(i) as? JSONObject ?: throw IllegalArgumentException("DrawerLayout at $i is not an object")
            val pkg = obj.optString("packageName", "").trim()
            require(pkg.isNotEmpty()) { "DrawerLayout at $i has blank packageName" }
            require(obj.has("activityName") && !obj.isNull("activityName")) { "DrawerLayout $pkg missing activityName" }
            val act = obj.getString("activityName")
            require(seen.add(pkg to act)) { "Duplicate drawerLayout component: $pkg/$act" }
            list.add(
                DrawerLayoutEntity(
                    packageName = pkg,
                    activityName = act,
                    drawerRank = obj.optInt("drawerRank", -1),
                    isDrawerShuffled = obj.optBoolean("isDrawerShuffled", false),
                    isHidden = obj.optBoolean("isHidden", false),
                    isFixed = obj.optBoolean("isFixed", false)
                )
            )
        }
        return list
    }

    private fun parseV2FocusAppsArray(arr: JSONArray): List<FocusAppEntity> {
        val list = mutableListOf<FocusAppEntity>()
        val seen = mutableSetOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val obj = arr.get(i) as? JSONObject ?: throw IllegalArgumentException("FocusApp at $i is not an object")
            val pkg = obj.optString("packageName", "").trim()
            require(pkg.isNotEmpty()) { "FocusApp at $i has blank packageName" }
            require(obj.has("activityName") && !obj.isNull("activityName")) { "FocusApp $pkg missing activityName" }
            val act = obj.getString("activityName")
            require(seen.add(pkg to act)) { "Duplicate focusApps component: $pkg/$act" }
            list.add(
                FocusAppEntity(
                    packageName = pkg,
                    activityName = act,
                    isVisibleInFocus = obj.optBoolean("isVisibleInFocus", false),
                    isEssentialInFocus = obj.optBoolean("isEssentialInFocus", false),
                    customOrder = obj.optInt("customOrder", 0)
                )
            )
        }
        return list
    }
}

package com.example.domain.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.room.withTransaction
import com.example.data.database.DrawerLayoutEntity
import com.example.data.database.FocusAppEntity
import com.example.data.database.FolderEntity
import com.example.data.database.FolderMemberEntity
import com.example.data.database.InstalledAppDao
import com.example.data.database.InstalledAppEntity
import com.example.data.database.ShellfriDatabase
import com.example.data.database.ShuffleHistoryDao
import com.example.data.database.ShuffleHistoryEntity
import com.example.data.database.ShuffleLayoutSnapshotEntity
import com.example.data.database.WorkspaceContainers
import com.example.data.database.WorkspaceDao
import com.example.data.database.WorkspaceItemEntity
import com.example.data.database.WorkspaceItemTypes
import com.example.data.database.WorkspacePageEntity
import com.example.data.preferences.ShellfriPreferences
import com.example.domain.concurrency.WorkspaceMutationCoordinator
import com.example.domain.model.AppItem
import com.example.domain.model.FolderItem
import com.example.domain.model.HomeMode
import com.example.domain.model.ShuffleInterval
import com.example.domain.model.WorkspacePage
import com.example.domain.model.componentIdentityKey
import com.example.domain.shuffle.HomeSlotCoordinate
import com.example.domain.shuffle.ShuffleDeltaEntry
import com.example.domain.shuffle.ShuffleDeltaSnapshot
import com.example.domain.shuffle.ShuffleEngine
import com.example.domain.shuffle.ShuffleRequest
import com.example.domain.shuffle.UndoShuffleResult
import com.example.domain.shuffle.UndoShuffleStatus
import com.example.domain.placement.FolderMoveRequest
import com.example.domain.placement.PlacementMoveRequest
import com.example.domain.placement.WorkspacePlacementPlanner
import com.example.domain.validation.WidgetPlacement
import com.example.domain.validation.WorkspaceValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AppRepository(
    private val context: Context,
    private val installedAppDao: InstalledAppDao,
    private val workspaceDao: WorkspaceDao,
    private val shuffleHistoryDao: ShuffleHistoryDao,
    private val preferences: ShellfriPreferences,
    private val shuffleEngine: ShuffleEngine = ShuffleEngine(),
    private val database: ShellfriDatabase? = null,
    private val iconRepository: IconRepository? = null,
    private val workspaceValidator: WorkspaceValidator = WorkspaceValidator(),
    private val mutationCoordinator: WorkspaceMutationCoordinator = WorkspaceMutationCoordinator.forDatabase(database),
    private val onPackageManagerDiscoveryQuery: (() -> Unit)? = null
) {
    private val packageManager: PackageManager = context.packageManager
    private val recentShufflePositions = mutableMapOf<String, ArrayDeque<Pair<Int, Int>>>()
    private val placementPlanner = WorkspacePlacementPlanner(workspaceValidator)

    data class MoveWorkspaceAppResult(
        val success: Boolean,
        val failureReason: String? = null,
        val movedCount: Int = 0
    )

    data class FolderMutationResult(
        val success: Boolean,
        val folderId: String? = null,
        val failureReason: String? = null
    )

    private data class PackageDiscoverySnapshot(
        val discovered: List<AppItem>,
        val resolveInfosByPkg: Map<String, List<ResolveInfo>>,
        val infoMatchesByPkg: Map<String, List<String>>,
        val launchComponentByPkg: Map<String, ComponentName?>,
        val defaultResolvedByPkg: Map<String, String?>
    )

    private suspend fun <T> runInTransaction(block: suspend () -> T): T {
        return if (database != null) {
            database.withTransaction { block() }
        } else {
            block()
        }
    }

    val pagesFlow: Flow<List<WorkspacePage>> = workspaceDao.getPages().map { list ->
        list.sortedBy { it.pageIndex }.map {
            WorkspacePage(
                pageId = it.pageId,
                pageIndex = it.pageIndex,
                isDefault = it.isDefault,
                createdAt = it.createdAt
            )
        }
    }

    val allAppsFlow: Flow<List<AppItem>> = combine(
        installedAppDao.getInstalledApps(),
        workspaceDao.getPages(),
        workspaceDao.getWorkspaceItems(),
        workspaceDao.getFolderMembers(),
        workspaceDao.getDrawerLayouts(),
        workspaceDao.getFocusApps()
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val installed = values[0] as List<InstalledAppEntity>
        @Suppress("UNCHECKED_CAST")
        val pages = values[1] as List<WorkspacePageEntity>
        @Suppress("UNCHECKED_CAST")
        val items = values[2] as List<WorkspaceItemEntity>
        @Suppress("UNCHECKED_CAST")
        val members = values[3] as List<FolderMemberEntity>
        @Suppress("UNCHECKED_CAST")
        val drawers = values[4] as List<DrawerLayoutEntity>
        @Suppress("UNCHECKED_CAST")
        val focusList = values[5] as List<FocusAppEntity>

        projectAppItems(installed, pages, items, members, drawers, focusList)
    }

    val dockAppsFlow: Flow<List<AppItem>> = allAppsFlow.map { apps ->
        apps.filter { it.isPinnedToDock }
            .sortedWith(compareBy<AppItem> { if (it.dockRank >= 0) it.dockRank else Int.MAX_VALUE }.thenBy { it.label.lowercase() })
    }

    val foldersFlow: Flow<List<FolderItem>> = combine(
        workspaceDao.getFolders(),
        workspaceDao.getWorkspaceItems(),
        workspaceDao.getPages()
    ) { folders, items, pages ->
        projectFolders(folders, items, pages)
    }

    val widgetsFlow: Flow<List<WidgetPlacement>> = combine(
        workspaceDao.getWorkspaceItems(),
        workspaceDao.getPages()
    ) { items, pages ->
        projectWidgets(items, pages)
    }

    val shuffleHistoryFlow: Flow<List<ShuffleHistoryEntity>> = shuffleHistoryDao.getRecentHistory()

    val homeModeFlow: Flow<HomeMode> = preferences.homeModeFlow
    val shuffleIntervalFlow: Flow<ShuffleInterval> = preferences.shuffleIntervalFlow
    val isShuffleEnabledFlow: Flow<Boolean> = preferences.isShuffleEnabledFlow
    val themeModeFlow: Flow<String> = preferences.themeModeFlow
    val gridColumnsFlow: Flow<Int> = preferences.gridColumnsFlow
    val iconSizeFlow: Flow<String> = preferences.iconSizeFlow
    val showLabelsFlow: Flow<Boolean> = preferences.showLabelsFlow
    val pageCountFlow: Flow<Int> = preferences.pageCountFlow
    val defaultPageIndexFlow: Flow<Int> = preferences.defaultPageIndexFlow
    val crossPageShuffleFlow: Flow<Boolean> = preferences.crossPageShuffleFlow
    val dockCountFlow: Flow<Int> = preferences.dockCountFlow
    val dockShowBackgroundFlow: Flow<Boolean> = preferences.dockShowBackgroundFlow
    val dockShowLabelsFlow: Flow<Boolean> = preferences.dockShowLabelsFlow
    val onboardingCompletedFlow: Flow<Boolean> = preferences.onboardingCompletedFlow
    val lastShuffleTimeFlow: Flow<Long> = preferences.lastShuffleTimeFlow
    val hideDistractingInFocusFlow: Flow<Boolean> = preferences.hideDistractingInFocusFlow
    val focusSearchEnabledFlow: Flow<Boolean> = preferences.focusSearchEnabledFlow
    val gestureSwipeUpFlow: Flow<String> = preferences.gestureSwipeUpFlow
    val gestureSwipeDownFlow: Flow<String> = preferences.gestureSwipeDownFlow
    val gestureDoubleTapFlow: Flow<String> = preferences.gestureDoubleTapFlow

    /**
     * Discovers all launchable activity components using PackageManager and synchronizes them
     * with normalized Room tables using canonical identity (packageName, activityName).
     * Preserves existing placements, Focus config, and Drawer layout; archives uninstalled
     * components while removing invalid active workspace references; restores exact-component
     * placements on reinstall.
     */
    suspend fun discoverAndSyncApps() = withContext(Dispatchers.IO) {
        // Phase 1 (lock-free): Perform all expensive PackageManager IPC outside coordinator lock and DB transaction
        val discoverySnapshot = queryLauncherComponentsFromPackageManager()

        // Phase 2 (coordinated + transactional): Reconcile discovered components against current DB state
        val syncPrefPageCount: Int? = mutationCoordinator.withMutationLock {
            val prefPageCount = preferences.pageCountFlow.first().coerceAtLeast(1)
            val maxDockCount = preferences.dockCountFlow.first().coerceIn(3, 7)
            val drawerShuffleEnabled = preferences.drawerShuffleEnabledFlow.first()

            runInTransaction {
                reconcileDiscoveredAppsInTransaction(
                    snapshot = discoverySnapshot,
                    prefPageCount = prefPageCount,
                    maxDockCount = maxDockCount,
                    drawerShuffleEnabled = drawerShuffleEnabled
                )
            }?.also { newPageCount ->
                preferences.setPageCount(newPageCount)
            }
        }
        @Suppress("UNUSED_VARIABLE")
        val unused = syncPrefPageCount

        // Phase 3 (lock-free): Preload icons asynchronously outside DB transaction/locks
        iconRepository?.preloadIcons(discoverySnapshot.discovered)
    }

    private fun queryLauncherComponentsFromPackageManager(): PackageDiscoverySnapshot {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = packageManager.queryIntentActivities(launcherIntent, 0)
        onPackageManagerDiscoveryQuery?.invoke()
        val ownPackage = context.packageName

        val discovered = mutableListOf<AppItem>()
        val seenDiscovered = mutableSetOf<Pair<String, String>>()
        val resolveInfosByPkg = mutableMapOf<String, MutableList<ResolveInfo>>()

        for (resolveInfo in resolveInfos) {
            val actInfo = resolveInfo.activityInfo ?: continue
            val pkg = actInfo.packageName ?: continue
            if (pkg == ownPackage) continue
            val act = actInfo.name ?: ""
            if (!seenDiscovered.add(pkg to act)) continue
            resolveInfosByPkg.getOrPut(pkg) { mutableListOf() }.add(resolveInfo)

            val label = runCatching {
                resolveInfo.loadLabel(packageManager).toString()
            }.getOrDefault(pkg)

            val isSystem = try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (_: Exception) {
                false
            }

            discovered.add(
                AppItem(
                    packageName = pkg,
                    activityName = act,
                    label = label,
                    isSystemApp = isSystem
                )
            )
        }

        val multiActivityPackages = resolveInfosByPkg.filterValues { it.size > 1 }.keys
        val infoMatchesByPkg = mutableMapOf<String, List<String>>()
        val launchComponentByPkg = mutableMapOf<String, ComponentName?>()
        val defaultResolvedByPkg = mutableMapOf<String, String?>()

        for (pkg in multiActivityPackages) {
            infoMatchesByPkg[pkg] = runCatching {
                packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO).setPackage(pkg),
                    0
                )
            }.getOrDefault(emptyList())
                .mapNotNull { it.activityInfo?.takeIf { ai -> ai.packageName == pkg && !ai.name.isNullOrBlank() }?.name }
                .distinct()

            launchComponentByPkg[pkg] = runCatching {
                packageManager.getLaunchIntentForPackage(pkg)?.component
            }.getOrNull()

            val resolvedDefault = runCatching {
                packageManager.resolveActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg),
                    PackageManager.MATCH_DEFAULT_ONLY
                )?.activityInfo
            }.getOrNull()
            defaultResolvedByPkg[pkg] = resolvedDefault
                ?.takeIf { it.packageName == pkg && !it.name.isNullOrBlank() }
                ?.name
        }

        return PackageDiscoverySnapshot(
            discovered = discovered,
            resolveInfosByPkg = resolveInfosByPkg,
            infoMatchesByPkg = infoMatchesByPkg,
            launchComponentByPkg = launchComponentByPkg,
            defaultResolvedByPkg = defaultResolvedByPkg
        )
    }

    private suspend fun reconcileDiscoveredAppsInTransaction(
        snapshot: PackageDiscoverySnapshot,
        prefPageCount: Int,
        maxDockCount: Int,
        drawerShuffleEnabled: Boolean
    ): Int? {
        val discovered = snapshot.discovered
        val defaultDistractionPackages = setOf(
            "com.instagram.android",
            "com.google.android.youtube",
            "com.reddit.frontpage",
            "com.reddit",
            "com.twitter.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.facebook.katana"
        )

        val existingInstalled = installedAppDao.getAllAppsList()
        val rawPages = workspaceDao.getPagesList()
        val hadNoPages = rawPages.isEmpty()
        val existingPages = ensurePagesList(rawPages, prefPageCount)
        val existingItems = workspaceDao.getWorkspaceItemsList().toMutableList()
        val existingFolders = workspaceDao.getFoldersList()
        val existingMembers = workspaceDao.getFolderMembersList().toMutableList()
        val existingDrawers = workspaceDao.getDrawerLayoutsList().associateBy { it.packageName to it.activityName }.toMutableMap()
        val existingFocus = workspaceDao.getFocusAppsList().associateBy { it.packageName to it.activityName }.toMutableMap()

        val validFolderIds = existingFolders.map { it.folderId }.toMutableSet()
        val validPageIds = existingPages.map { it.pageId }.toSet()
        val existingByComponent = existingInstalled.associateBy { it.packageName to it.activityName }
        val discoveredSet = discovered.map { it.packageName to it.activityName }.toSet()
        val discoveredByPkg = discovered.groupBy { it.packageName }

        // Resolve unambiguous legacy blank-activity records before Step 1 so live placements & ranks
        // are transferred directly and cannot be stolen by unrelated newly discovered apps.
        val legacyReconciliations = mutableMapOf<Pair<String, String>, InstalledAppEntity>()
        val reconciledLegacyKeys = mutableSetOf<Pair<String, String>>()
        for ((compKey, legacyRecord) in existingByComponent) {
            if (compKey.second.isNotEmpty()) continue
            val pkgDiscovered = discoveredByPkg[compKey.first].orEmpty()
            if (pkgDiscovered.isEmpty()) continue
            val target = resolveUnambiguousLegacyTarget(
                pkg = compKey.first,
                packageDiscovered = pkgDiscovered,
                snapshot = snapshot,
                existingByComponent = existingByComponent
            ) ?: continue
            val targetComp = target.packageName to target.activityName
            if (targetComp in existingByComponent) continue
            legacyReconciliations[targetComp] = legacyRecord
            reconciledLegacyKeys.add(compKey)
        }

        val pageIdToIndex = existingPages.associate { it.pageId to it.pageIndex }
        val page0Id = existingPages.first { it.pageIndex == 0 }.pageId

        val updatedApps = mutableListOf<InstalledAppEntity>()
        val itemsToUpsert = mutableListOf<WorkspaceItemEntity>()
        val itemsToDelete = mutableListOf<String>()
        val membersToUpsert = mutableListOf<FolderMemberEntity>()
        val membersToDelete = mutableListOf<FolderMemberEntity>()
        val foldersToDelete = mutableSetOf<String>()
        val drawersToUpsert = mutableListOf<DrawerLayoutEntity>()
        val focusToUpsert = mutableListOf<FocusAppEntity>()

        val legacyItemsToDelete = mutableListOf<String>()
        val legacyMembersToDelete = mutableListOf<FolderMemberEntity>()
        val legacyDrawersToDelete = mutableListOf<Pair<String, String>>()
        val legacyFocusToDelete = mutableListOf<Pair<String, String>>()
        val legacyAppsToDelete = mutableListOf<Pair<String, String>>()

        // Track occupied cells across HOME and DOCK
        val occupiedHomeByPageId = mutableMapOf<String, MutableSet<Int>>()
        for (item in existingItems) {
            if (item.containerType == WorkspaceContainers.HOME && item.pageId != null) {
                val set = occupiedHomeByPageId.getOrPut(item.pageId) { mutableSetOf() }
                val span = (item.columnSpan.coerceAtLeast(1) * item.rowSpan.coerceAtLeast(1))
                for (offset in 0 until span) set.add(item.rank + offset)
            }
        }
        val occupiedDockRanks = existingItems
            .filter { it.containerType == WorkspaceContainers.DOCK }
            .map { it.rank }
            .toMutableSet()
        var currentDockSize = existingItems.count { it.containerType == WorkspaceContainers.DOCK }

        fun allocateHomeRank(pageId: String, preferred: Int = 0): Int {
            val occupied = occupiedHomeByPageId.getOrPut(pageId) { mutableSetOf() }
            var candidate = preferred.coerceAtLeast(0)
            while (candidate in occupied) candidate++
            occupied.add(candidate)
            return candidate
        }

        fun allocateDockRank(preferred: Int = 0): Int {
            var candidate = preferred.coerceAtLeast(0)
            while (candidate in occupiedDockRanks) candidate++
            occupiedDockRanks.add(candidate)
            return candidate
        }

        // Snapshot folder Home placements prior to Step 1 uninstall modifications
        val preUninstallFolderHomeById = existingItems
            .filter { it.itemType == WorkspaceItemTypes.FOLDER && it.folderId != null }
            .associateBy { it.folderId!! }
        val affectedFolderIdsAfterUninstall = mutableSetOf<String>()

        // 1. Handle uninstalled components: archive active placement & strip invalid active references.
        // Skip legacy blank-activity records being reconciled directly in Step 2A.
        for (record in existingInstalled) {
            val comp = record.packageName to record.activityName
            if (comp in reconciledLegacyKeys) continue
            if (comp !in discoveredSet) {
                val activeItems = existingItems.filter { it.packageName == record.packageName && it.activityName == record.activityName }
                val activeMember = existingMembers.find { it.packageName == record.packageName && it.activityName == record.activityName }
                val activeFolderHome = activeMember?.folderId?.let { preUninstallFolderHomeById[it] }

                val archivedJson = if (record.isInstalled && (activeItems.isNotEmpty() || activeMember != null)) {
                    serializeArchivedPlacement(activeItems, activeMember, pageIdToIndex, activeFolderHome)
                } else {
                    record.archivedPlacementJson
                }

                updatedApps.add(
                    record.copy(
                        isInstalled = false,
                        archivedPlacementJson = archivedJson
                    )
                )

                for (item in activeItems) {
                    itemsToDelete.add(item.itemId)
                    if (item.containerType == WorkspaceContainers.HOME && item.pageId != null) {
                        val span = (item.columnSpan.coerceAtLeast(1) * item.rowSpan.coerceAtLeast(1))
                        for (offset in 0 until span) occupiedHomeByPageId[item.pageId]?.remove(item.rank + offset)
                    } else if (item.containerType == WorkspaceContainers.DOCK) {
                        occupiedDockRanks.remove(item.rank)
                        currentDockSize = (currentDockSize - 1).coerceAtLeast(0)
                    }
                }
                existingItems.removeAll { it.packageName == record.packageName && it.activityName == record.activityName }

                if (activeMember != null) {
                    membersToDelete.add(activeMember)
                    existingMembers.remove(activeMember)
                    affectedFolderIdsAfterUninstall.add(activeMember.folderId)
                }
            }
        }

        // 1B. Reconcile folders affected by member uninstall before Step 2A reinstall restoration:
        // - >= 2 remaining: repack contiguous ranks 0..N-1
        // - 1 remaining: dissolve folder and unpack survivor to folder's Home slot (preserving Dock)
        // - 0 remaining: delete empty folder & its Home item
        for (fId in affectedFolderIdsAfterUninstall.sorted()) {
            val remaining = existingMembers
                .filter { it.folderId == fId }
                .sortedWith(compareBy<FolderMemberEntity> { it.rank }.thenBy { it.packageName }.thenBy { it.activityName })
            if (remaining.size >= 2) {
                val repacked = remaining.mapIndexed { idx, m -> m.copy(rank = idx) }
                existingMembers.removeAll { it.folderId == fId }
                existingMembers.addAll(repacked)
                membersToUpsert.addAll(repacked)
            } else {
                validFolderIds.remove(fId)
                foldersToDelete.add(fId)
                val folderHomeItem = existingItems.firstOrNull {
                    it.itemType == WorkspaceItemTypes.FOLDER && it.folderId == fId
                }
                if (folderHomeItem != null) {
                    itemsToDelete.add(folderHomeItem.itemId)
                    if (folderHomeItem.containerType == WorkspaceContainers.HOME && folderHomeItem.pageId != null) {
                        val span = (folderHomeItem.columnSpan.coerceAtLeast(1) * folderHomeItem.rowSpan.coerceAtLeast(1))
                        for (offset in 0 until span) occupiedHomeByPageId[folderHomeItem.pageId]?.remove(folderHomeItem.rank + offset)
                    }
                    existingItems.removeAll { it.itemId == folderHomeItem.itemId }
                }

                val survivor = remaining.singleOrNull()
                if (survivor != null) {
                    membersToDelete.add(survivor)
                    existingMembers.remove(survivor)

                    val staleSurvivorHome = existingItems.filter {
                        it.containerType == WorkspaceContainers.HOME &&
                            it.packageName == survivor.packageName &&
                            it.activityName == survivor.activityName
                    }
                    for (stale in staleSurvivorHome) {
                        itemsToDelete.add(stale.itemId)
                        if (stale.pageId != null) {
                            occupiedHomeByPageId[stale.pageId]?.remove(stale.rank)
                        }
                    }
                    existingItems.removeAll {
                        it.containerType == WorkspaceContainers.HOME &&
                            it.packageName == survivor.packageName &&
                            it.activityName == survivor.activityName
                    }

                    val targetPageId = folderHomeItem?.pageId?.takeIf { it in validPageIds } ?: page0Id
                    val preferredRank = folderHomeItem?.rank ?: 0
                    val restoredRank = allocateHomeRank(targetPageId, preferredRank)
                    val dockHidden = existingItems.firstOrNull {
                        it.containerType == WorkspaceContainers.DOCK &&
                            it.packageName == survivor.packageName &&
                            it.activityName == survivor.activityName
                    }?.isHidden ?: false

                    val restoredHome = WorkspaceItemEntity(
                        itemId = homeItemId(survivor.packageName, survivor.activityName),
                        itemType = WorkspaceItemTypes.APP,
                        containerType = WorkspaceContainers.HOME,
                        pageId = targetPageId,
                        packageName = survivor.packageName,
                        activityName = survivor.activityName,
                        rank = restoredRank,
                        isFixed = survivor.isFixed,
                        isShuffled = !survivor.isFixed && survivor.isShuffled,
                        isHidden = dockHidden
                    )
                    itemsToUpsert.add(restoredHome)
                    existingItems.add(restoredHome)
                }
            }
        }

        // 2A. Handle exact existing components & unambiguous legacy blank-activity reconciliations first
        // so brand-new apps cannot claim their existing or archived slots.
        val isFirstRun = existingInstalled.isEmpty()
        var distractionCount = 0
        val brandNewCandidates = mutableListOf<AppItem>()

        for (item in discovered) {
            val comp = item.packageName to item.activityName
            val existing = existingByComponent[comp]
            val legacyRecord = legacyReconciliations[comp]

            if (existing != null) {
                if (!existing.isInstalled && existing.archivedPlacementJson != null) {
                    // Exact-component reinstall -> restore placement if valid & free
                    restoreArchivedPlacement(
                        archivedJson = existing.archivedPlacementJson,
                        pkg = item.packageName,
                        act = item.activityName,
                        pages = existingPages,
                        validFolderIds = validFolderIds,
                        existingItems = existingItems,
                        existingMembers = existingMembers,
                        currentDockSize = currentDockSize,
                        maxDockCount = maxDockCount,
                        allocateHomeRank = ::allocateHomeRank,
                        allocateDockRank = ::allocateDockRank,
                        onRestoreItem = { restoredItem ->
                            itemsToUpsert.add(restoredItem)
                            existingItems.add(restoredItem)
                            if (restoredItem.containerType == WorkspaceContainers.DOCK) currentDockSize++
                        },
                        onRestoreMember = { restoredMember ->
                            membersToUpsert.add(restoredMember)
                            existingMembers.add(restoredMember)
                        }
                    )
                }
                updatedApps.add(
                    existing.copy(
                        label = item.label,
                        isSystemApp = item.isSystemApp,
                        isInstalled = true,
                        archivedPlacementJson = null
                    )
                )
                if (comp !in existingDrawers) {
                    drawersToUpsert.add(DrawerLayoutEntity(item.packageName, item.activityName))
                }
                if (comp !in existingFocus) {
                    focusToUpsert.add(FocusAppEntity(item.packageName, item.activityName))
                }
            } else if (legacyRecord != null) {
                val stagedItems = mutableListOf<WorkspaceItemEntity>()
                val stagedMembers = mutableListOf<FolderMemberEntity>()
                val stagedLegacyItemsToDelete = mutableListOf<String>()
                val stagedLegacyMembersToDelete = mutableListOf<FolderMemberEntity>()
                var stagedDockDelta = 0
                var archivedRestoreSucceeded = true

                if (legacyRecord.archivedPlacementJson != null) {
                    archivedRestoreSucceeded = restoreArchivedPlacement(
                        archivedJson = legacyRecord.archivedPlacementJson,
                        pkg = item.packageName,
                        act = item.activityName,
                        pages = existingPages,
                        validFolderIds = validFolderIds,
                        existingItems = existingItems,
                        existingMembers = existingMembers,
                        currentDockSize = currentDockSize,
                        maxDockCount = maxDockCount,
                        allocateHomeRank = ::allocateHomeRank,
                        allocateDockRank = ::allocateDockRank,
                        onRestoreItem = { restoredItem ->
                            stagedItems.add(restoredItem)
                            if (restoredItem.containerType == WorkspaceContainers.DOCK) stagedDockDelta++
                        },
                        onRestoreMember = { restoredMember ->
                            stagedMembers.add(restoredMember)
                        }
                    )
                }

                if (!archivedRestoreSucceeded) {
                    // Failed reconciliation: preserve the legacy record and its archived placement intact.
                    continue
                }

                val legacyLiveItems = existingItems.filter {
                    it.packageName == legacyRecord.packageName && it.activityName == ""
                }
                for (legacyItem in legacyLiveItems) {
                    stagedLegacyItemsToDelete.add(legacyItem.itemId)
                    if (legacyItem.containerType == WorkspaceContainers.DOCK) {
                        val alreadyHasDock = (existingItems + stagedItems).any {
                            it.containerType == WorkspaceContainers.DOCK &&
                                it.packageName == item.packageName &&
                                it.activityName == item.activityName
                        }
                        if (!alreadyHasDock) {
                            stagedItems.add(
                                legacyItem.copy(
                                    itemId = dockItemId(item.packageName, item.activityName),
                                    pageId = null,
                                    packageName = item.packageName,
                                    activityName = item.activityName
                                )
                            )
                        }
                    } else {
                        val alreadyHasHome = (existingItems + stagedItems).any {
                            it.containerType == WorkspaceContainers.HOME &&
                                it.packageName == item.packageName &&
                                it.activityName == item.activityName
                        }
                        if (!alreadyHasHome) {
                            val preservedPageId = legacyItem.pageId?.takeIf { it in validPageIds } ?: page0Id
                            val preservedRank = if (preservedPageId == legacyItem.pageId) {
                                val span = legacyItem.columnSpan.coerceAtLeast(1) * legacyItem.rowSpan.coerceAtLeast(1)
                                val collidedWithOther = existingItems.any { other ->
                                    other.itemId != legacyItem.itemId &&
                                        other.containerType == WorkspaceContainers.HOME &&
                                        other.pageId == preservedPageId &&
                                        run {
                                            val otherSpan = other.columnSpan.coerceAtLeast(1) * other.rowSpan.coerceAtLeast(1)
                                            val otherRange = other.rank until (other.rank + otherSpan)
                                            (legacyItem.rank until (legacyItem.rank + span)).any { cell -> cell in otherRange }
                                        }
                                }
                                if (!collidedWithOther) legacyItem.rank else allocateHomeRank(preservedPageId, legacyItem.rank)
                            } else {
                                allocateHomeRank(preservedPageId, legacyItem.rank)
                            }
                            stagedItems.add(
                                legacyItem.copy(
                                    itemId = homeItemId(item.packageName, item.activityName),
                                    pageId = preservedPageId,
                                    packageName = item.packageName,
                                    activityName = item.activityName,
                                    rank = preservedRank
                                )
                            )
                        }
                    }
                }

                val legacyLiveMembers = existingMembers.filter {
                    it.packageName == legacyRecord.packageName && it.activityName == ""
                }
                for (legacyMember in legacyLiveMembers) {
                    stagedLegacyMembersToDelete.add(legacyMember)
                    if (legacyMember.folderId in validFolderIds) {
                        val alreadyMember = (existingMembers + stagedMembers).any {
                            it.folderId == legacyMember.folderId &&
                                it.packageName == item.packageName &&
                                it.activityName == item.activityName
                        }
                        if (!alreadyMember) {
                            stagedMembers.add(
                                legacyMember.copy(
                                    packageName = item.packageName,
                                    activityName = item.activityName
                                )
                            )
                        }
                    }
                }

                // Commit staged reconciliation records in memory and schedule legacy deletion after upserts
                currentDockSize += stagedDockDelta
                existingItems.removeAll { it.itemId in stagedLegacyItemsToDelete }
                existingItems.addAll(stagedItems)
                itemsToUpsert.addAll(stagedItems)
                legacyItemsToDelete.addAll(stagedLegacyItemsToDelete)

                existingMembers.removeAll { it in stagedLegacyMembersToDelete }
                existingMembers.addAll(stagedMembers)
                membersToUpsert.addAll(stagedMembers)
                legacyMembersToDelete.addAll(stagedLegacyMembersToDelete)

                val legacyKey = legacyRecord.packageName to ""
                val legacyDrawer = existingDrawers.remove(legacyKey)
                val targetDrawer = legacyDrawer?.copy(
                    packageName = item.packageName,
                    activityName = item.activityName
                ) ?: DrawerLayoutEntity(item.packageName, item.activityName)
                existingDrawers[comp] = targetDrawer
                drawersToUpsert.add(targetDrawer)
                if (legacyDrawer != null) {
                    legacyDrawersToDelete.add(legacyKey)
                }

                val legacyFocus = existingFocus.remove(legacyKey)
                val targetFocus = legacyFocus?.copy(
                    packageName = item.packageName,
                    activityName = item.activityName
                ) ?: FocusAppEntity(item.packageName, item.activityName)
                existingFocus[comp] = targetFocus
                focusToUpsert.add(targetFocus)
                if (legacyFocus != null) {
                    legacyFocusToDelete.add(legacyKey)
                }

                updatedApps.add(
                    legacyRecord.copy(
                        activityName = item.activityName,
                        label = item.label.ifBlank { legacyRecord.label },
                        isSystemApp = item.isSystemApp,
                        isInstalled = true,
                        archivedPlacementJson = null
                    )
                )
                legacyAppsToDelete.add(legacyKey)
            } else {
                brandNewCandidates.add(item)
            }
        }

        // 2A-post. Normalize any remaining pre-existing/migrated folders with < 2 members so committed DB adheres to strict invariant.
        for (fId in validFolderIds.toList().sorted()) {
            val remaining = existingMembers
                .filter { it.folderId == fId }
                .sortedWith(compareBy<FolderMemberEntity> { it.rank }.thenBy { it.packageName }.thenBy { it.activityName })
            if (remaining.size >= 2) {
                val needsRepack = remaining.withIndex().any { (idx, m) -> m.rank != idx }
                if (needsRepack) {
                    val repacked = remaining.mapIndexed { idx, m -> m.copy(rank = idx) }
                    existingMembers.removeAll { it.folderId == fId }
                    existingMembers.addAll(repacked)
                    membersToUpsert.removeAll { it.folderId == fId }
                    membersToUpsert.addAll(repacked)
                }
            } else {
                validFolderIds.remove(fId)
                foldersToDelete.add(fId)
                val folderHomeItem = existingItems.firstOrNull {
                    it.itemType == WorkspaceItemTypes.FOLDER && it.folderId == fId
                }
                if (folderHomeItem != null) {
                    itemsToDelete.add(folderHomeItem.itemId)
                    if (folderHomeItem.containerType == WorkspaceContainers.HOME && folderHomeItem.pageId != null) {
                        val span = (folderHomeItem.columnSpan.coerceAtLeast(1) * folderHomeItem.rowSpan.coerceAtLeast(1))
                        for (offset in 0 until span) occupiedHomeByPageId[folderHomeItem.pageId]?.remove(folderHomeItem.rank + offset)
                    }
                    existingItems.removeAll { it.itemId == folderHomeItem.itemId }
                }

                val survivor = remaining.singleOrNull()
                if (survivor != null) {
                    membersToDelete.add(survivor)
                    membersToUpsert.removeAll { it.folderId == fId }
                    existingMembers.remove(survivor)

                    val staleSurvivorHome = existingItems.filter {
                        it.containerType == WorkspaceContainers.HOME &&
                            it.packageName == survivor.packageName &&
                            it.activityName == survivor.activityName
                    }
                    for (stale in staleSurvivorHome) {
                        itemsToDelete.add(stale.itemId)
                        if (stale.pageId != null) {
                            occupiedHomeByPageId[stale.pageId]?.remove(stale.rank)
                        }
                    }
                    existingItems.removeAll {
                        it.containerType == WorkspaceContainers.HOME &&
                            it.packageName == survivor.packageName &&
                            it.activityName == survivor.activityName
                    }

                    val targetPageId = folderHomeItem?.pageId?.takeIf { it in validPageIds } ?: page0Id
                    val preferredRank = folderHomeItem?.rank ?: 0
                    val restoredRank = allocateHomeRank(targetPageId, preferredRank)
                    val dockHidden = existingItems.firstOrNull {
                        it.containerType == WorkspaceContainers.DOCK &&
                            it.packageName == survivor.packageName &&
                            it.activityName == survivor.activityName
                    }?.isHidden ?: false

                    val restoredHome = WorkspaceItemEntity(
                        itemId = homeItemId(survivor.packageName, survivor.activityName),
                        itemType = WorkspaceItemTypes.APP,
                        containerType = WorkspaceContainers.HOME,
                        pageId = targetPageId,
                        packageName = survivor.packageName,
                        activityName = survivor.activityName,
                        rank = restoredRank,
                        isFixed = survivor.isFixed,
                        isShuffled = !survivor.isFixed && survivor.isShuffled,
                        isHidden = dockHidden
                    )
                    itemsToUpsert.add(restoredHome)
                    existingItems.add(restoredHome)
                }
            }
        }

        // 2B. Handle brand-new components
        for (item in brandNewCandidates) {
            val isDefaultDistraction = defaultDistractionPackages.contains(item.packageName) ||
                (isFirstRun && !item.isSystemApp && distractionCount < 3)
            if (isDefaultDistraction) distractionCount++

            val isEssentialCandidate = item.label.contains("Phone", ignoreCase = true) ||
                item.label.contains("Message", ignoreCase = true) ||
                item.label.contains("Clock", ignoreCase = true) ||
                item.label.contains("Settings", ignoreCase = true)

            val appEntity = InstalledAppEntity(
                packageName = item.packageName,
                activityName = item.activityName,
                label = item.label,
                isSystemApp = item.isSystemApp,
                customCategory = if (isDefaultDistraction) "Social / Media" else "General",
                lastUsedTimestamp = 0L,
                isInstalled = true,
                archivedPlacementJson = null
            )
            updatedApps.add(appEntity)

            drawersToUpsert.add(
                DrawerLayoutEntity(
                    packageName = item.packageName,
                    activityName = item.activityName,
                    drawerRank = -1,
                    isDrawerShuffled = false,
                    isHidden = false,
                    isFixed = false
                )
            )
            focusToUpsert.add(
                FocusAppEntity(
                    packageName = item.packageName,
                    activityName = item.activityName,
                    isVisibleInFocus = isEssentialCandidate,
                    isEssentialInFocus = isEssentialCandidate,
                    customOrder = 0
                )
            )

            val labelLower = item.label.lowercase()
            val pkgLower = item.packageName.lowercase()
            val isCandidateDock = labelLower.contains("phone") ||
                labelLower.contains("dialer") ||
                labelLower.contains("message") ||
                labelLower.contains("chrome") ||
                labelLower.contains("browser") ||
                labelLower.contains("camera") ||
                pkgLower.contains("dialer")

            if (isFirstRun && isCandidateDock && currentDockSize < maxDockCount) {
                val dockItem = WorkspaceItemEntity(
                    itemId = dockItemId(item.packageName, item.activityName),
                    itemType = WorkspaceItemTypes.APP,
                    containerType = WorkspaceContainers.DOCK,
                    pageId = null,
                    packageName = item.packageName,
                    activityName = item.activityName,
                    rank = allocateDockRank(currentDockSize),
                    isFixed = false,
                    isShuffled = false,
                    isHidden = false
                )
                itemsToUpsert.add(dockItem)
                existingItems.add(dockItem)
                currentDockSize++
            } else {
                val rank = allocateHomeRank(page0Id, 0)
                val homeItem = WorkspaceItemEntity(
                    itemId = homeItemId(item.packageName, item.activityName),
                    itemType = WorkspaceItemTypes.APP,
                    containerType = WorkspaceContainers.HOME,
                    pageId = page0Id,
                    packageName = item.packageName,
                    activityName = item.activityName,
                    rank = rank,
                    isFixed = false,
                    isShuffled = isDefaultDistraction,
                    isHidden = false
                )
                itemsToUpsert.add(homeItem)
                existingItems.add(homeItem)
            }
        }

        if (hadNoPages) {
            workspaceDao.insertPages(existingPages)
        }
        for (itemId in itemsToDelete) {
            workspaceDao.deleteWorkspaceItemById(itemId)
        }
        for (member in membersToDelete) {
            workspaceDao.deleteFolderMember(member.folderId, member.packageName, member.activityName)
        }
        for (folderId in foldersToDelete) {
            workspaceDao.deleteWorkspaceItemsForFolder(folderId)
            workspaceDao.deleteFolderMembersByFolder(folderId)
            workspaceDao.deleteFolder(folderId)
        }
        if (updatedApps.isNotEmpty()) installedAppDao.upsertApps(updatedApps)
        if (itemsToUpsert.isNotEmpty()) workspaceDao.upsertWorkspaceItems(itemsToUpsert)
        if (membersToUpsert.isNotEmpty()) workspaceDao.upsertFolderMembers(membersToUpsert)
        if (drawersToUpsert.isNotEmpty()) workspaceDao.upsertDrawerLayouts(drawersToUpsert)
        if (focusToUpsert.isNotEmpty()) workspaceDao.upsertFocusApps(focusToUpsert)

        // Delete reconciled legacy blank-activity rows only after all target upserts succeed.
        for (itemId in legacyItemsToDelete) {
            workspaceDao.deleteWorkspaceItemById(itemId)
        }
        for (member in legacyMembersToDelete) {
            workspaceDao.deleteFolderMember(member.folderId, member.packageName, member.activityName)
        }
        for ((pkg, act) in legacyDrawersToDelete) {
            workspaceDao.deleteDrawerLayoutForComponent(pkg, act)
        }
        for ((pkg, act) in legacyFocusToDelete) {
            workspaceDao.deleteFocusAppForComponent(pkg, act)
        }
        for ((pkg, act) in legacyAppsToDelete) {
            installedAppDao.deleteByComponent(pkg, act)
        }

        validateNormalizedDbStateOrThrow(maxDockCount, drawerShuffleEnabled)
        return if (existingPages.size != prefPageCount) existingPages.size else null
    }

    private fun resolveUnambiguousLegacyTarget(
        pkg: String,
        packageDiscovered: List<AppItem>,
        snapshot: PackageDiscoverySnapshot,
        existingByComponent: Map<Pair<String, String>, InstalledAppEntity>
    ): AppItem? {
        val validCandidates = packageDiscovered.filter { it.activityName.isNotBlank() }
        if (validCandidates.isEmpty()) return null

        if (validCandidates.size == 1) {
            val single = validCandidates.single()
            return if ((single.packageName to single.activityName) !in existingByComponent) single else null
        }

        // Multiple launcher activities discovered: use precomputed PackageManager metadata
        val infoMatches = snapshot.infoMatchesByPkg[pkg].orEmpty()
        if (infoMatches.size == 1) {
            val infoTarget = validCandidates.find { it.activityName == infoMatches.single() }
            if (infoTarget != null && (infoTarget.packageName to infoTarget.activityName) !in existingByComponent) {
                return infoTarget
            }
        }

        val resolveInfosForPkg = snapshot.resolveInfosByPkg[pkg].orEmpty()
        if (resolveInfosForPkg.isNotEmpty()) {
            val hasDefault = resolveInfosForPkg.any { it.isDefault }
            val defaultFiltered = if (hasDefault) resolveInfosForPkg.filter { it.isDefault } else resolveInfosForPkg
            val maxPriority = defaultFiltered.maxOf { it.priority }
            val priorityFiltered = defaultFiltered.filter { it.priority == maxPriority }
            val maxPreferredOrder = priorityFiltered.maxOf { it.preferredOrder }
            val topCandidates = priorityFiltered.filter { it.preferredOrder == maxPreferredOrder }
            if (topCandidates.size == 1) {
                val topName = topCandidates.single().activityInfo?.name
                val candidate = validCandidates.find { it.activityName == topName }
                if (candidate != null && (candidate.packageName to candidate.activityName) !in existingByComponent) {
                    val launchComp = snapshot.launchComponentByPkg[pkg]
                    if (launchComp == null || (launchComp.packageName == pkg && launchComp.className == candidate.activityName)) {
                        return candidate
                    }
                }
            }
        }

        val resolvedDefaultName = snapshot.defaultResolvedByPkg[pkg]
        if (resolvedDefaultName != null) {
            val defaultCategoryCount = resolveInfosForPkg.count {
                it.filter?.hasCategory(Intent.CATEGORY_DEFAULT) == true
            }
            if (defaultCategoryCount <= 1) {
                val candidate = validCandidates.find { it.activityName == resolvedDefaultName }
                if (candidate != null && (candidate.packageName to candidate.activityName) !in existingByComponent) {
                    return candidate
                }
            }
        }

        return null
    }

    /**
     * Executes shuffle using explicit ShuffleRequest, persists normalized layout updates,
     * records history with strategy/scope, and stores a pre-shuffle layout snapshot for undo.
     */
    suspend fun performShuffle(triggerType: String = "MANUAL"): Boolean = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            performShuffleLocked(triggerType)
        }
    }

    private suspend fun performShuffleLocked(triggerType: String): Boolean {
        val drawerShuffleEnabled = preferences.drawerShuffleEnabledFlow.first()
        val strategy = preferences.shuffleStrategyFlow.first()
        val occupancy = preferences.occupancyOptionFlow.first()
        val crossPage = preferences.crossPageShuffleFlow.first()
        val prefPageCount = preferences.pageCountFlow.first()

        var executedTimestamp: Long? = null
        var executedPreviousApps: List<AppItem> = emptyList()

        val succeeded = runInTransaction {
            val installed = installedAppDao.getAllAppsList().filter { it.isInstalled }
            val pages = workspaceDao.getPagesList().sortedBy { it.pageIndex }
            val items = workspaceDao.getWorkspaceItemsList()
            val foldersEntities = workspaceDao.getFoldersList()
            val members = workspaceDao.getFolderMembersList()
            val drawers = workspaceDao.getDrawerLayoutsList()
            val focusList = workspaceDao.getFocusAppsList()

            val currentApps = projectAppItems(installed, pages, items, members, drawers, focusList)
            val folders = projectFolders(foldersEntities, items, pages)
            val widgets = projectWidgets(items, pages)

            val eligibleHomeCount = currentApps.count {
                it.isShuffled && it.isOnHomeScreen && it.folderId == null && !it.isPinnedToDock && !it.isFixed
            }
            val eligibleDrawerCount = if (drawerShuffleEnabled) {
                currentApps.count { it.isDrawerShuffled && !it.isFixed }
            } else 0

            if (eligibleHomeCount == 0 && eligibleDrawerCount == 0) return@runInTransaction false

            val pageCount = pages.size.coerceAtLeast(prefPageCount).coerceAtLeast(1)
            val historySnapshot = recentShufflePositions.mapValues { it.value.toList() }

            val request = ShuffleRequest(
                apps = currentApps,
                folders = folders,
                widgets = widgets,
                strategy = strategy,
                occupancy = occupancy,
                crossPageShuffle = crossPage,
                pageCount = pageCount,
                includeDrawerShuffle = drawerShuffleEnabled,
                recentPositions = historySnapshot
            )

            val result = shuffleEngine.executeShuffle(request)
            if (!result.isValid || (result.movedHomeCount == 0 && result.movedDrawerCount == 0)) {
                return@runInTransaction false
            }

            val pageIndexToId = pages.associate { it.pageIndex to it.pageId }
            val pageIdToIndex = pages.associate { it.pageId to it.pageIndex }
            val homeItemByComponent = items
                .filter { it.containerType == WorkspaceContainers.HOME && it.itemType == WorkspaceItemTypes.APP && it.packageName != null }
                .associateBy { it.packageName!! to (it.activityName ?: "") }
            val drawerByComponent = drawers.associateBy { it.packageName to it.activityName }

            val updatedHomeItems = mutableListOf<WorkspaceItemEntity>()
            val updatedDrawers = mutableListOf<DrawerLayoutEntity>()
            val deltaEntries = mutableListOf<ShuffleDeltaEntry>()

            for (app in result.apps) {
                val comp = app.packageName to app.activityName
                val existingHome = homeItemByComponent[comp]
                var origHomeCoord: HomeSlotCoordinate? = null
                var resHomeCoord: HomeSlotCoordinate? = null
                if (existingHome != null) {
                    val targetPageId = pageIndexToId[app.pageIndex] ?: existingHome.pageId
                    if (targetPageId != null && (existingHome.pageId != targetPageId || existingHome.rank != app.slotIndex)) {
                        updatedHomeItems.add(
                            existingHome.copy(
                                pageId = targetPageId,
                                rank = app.slotIndex
                            )
                        )
                        val origPageId = existingHome.pageId ?: targetPageId
                        origHomeCoord = HomeSlotCoordinate(
                            pageId = origPageId,
                            pageIndex = pageIdToIndex[origPageId] ?: 0,
                            rank = existingHome.rank
                        )
                        resHomeCoord = HomeSlotCoordinate(
                            pageId = targetPageId,
                            pageIndex = app.pageIndex,
                            rank = app.slotIndex
                        )
                    }
                }

                var origDrawerRank: Int? = null
                var resDrawerRank: Int? = null
                if (drawerShuffleEnabled) {
                    val existingDrawer = drawerByComponent[comp] ?: DrawerLayoutEntity(app.packageName, app.activityName)
                    if (existingDrawer.drawerRank != app.drawerSlotIndex) {
                        updatedDrawers.add(existingDrawer.copy(drawerRank = app.drawerSlotIndex))
                        origDrawerRank = existingDrawer.drawerRank
                        resDrawerRank = app.drawerSlotIndex
                    }
                }

                if (origHomeCoord != null || origDrawerRank != null) {
                    deltaEntries.add(
                        ShuffleDeltaEntry(
                            packageName = app.packageName,
                            activityName = app.activityName,
                            originalHome = origHomeCoord,
                            resultingHome = resHomeCoord,
                            originalDrawerRank = origDrawerRank,
                            resultingDrawerRank = resDrawerRank
                        )
                    )
                }
            }

            if (deltaEntries.isEmpty()) return@runInTransaction false

            val now = System.currentTimeMillis()
            val totalMoved = (result.movedHomeCount + result.movedDrawerCount).coerceAtLeast(eligibleHomeCount)
            val scope = when {
                drawerShuffleEnabled && crossPage -> "HOME_CROSS_PAGE+DRAWER"
                drawerShuffleEnabled -> "HOME+DRAWER"
                crossPage -> "HOME_CROSS_PAGE"
                else -> "HOME_SINGLE_PAGE"
            }

            if (updatedHomeItems.isNotEmpty()) {
                workspaceDao.upsertWorkspaceItems(updatedHomeItems)
            }
            if (updatedDrawers.isNotEmpty()) {
                workspaceDao.upsertDrawerLayouts(updatedDrawers)
            }
            val historyId = shuffleHistoryDao.insert(
                ShuffleHistoryEntity(
                    timestamp = now,
                    shuffledAppCount = totalMoved,
                    triggerType = triggerType,
                    strategy = strategy.name,
                    scope = scope
                )
            )
            val deltaSnapshotJson = ShuffleDeltaSnapshot(
                historyId = historyId,
                timestamp = now,
                strategy = strategy.name,
                occupancy = occupancy.name,
                crossPageShuffle = crossPage,
                drawerShuffleEnabled = drawerShuffleEnabled,
                pageCount = pageCount,
                entries = deltaEntries
            ).toJson()

            // New shuffle supersedes and invalidates any earlier undo snapshot
            shuffleHistoryDao.clearSnapshots()
            shuffleHistoryDao.insertSnapshot(
                ShuffleLayoutSnapshotEntity(
                    historyId = historyId,
                    timestamp = now,
                    snapshotJson = deltaSnapshotJson
                )
            )

            executedTimestamp = now
            executedPreviousApps = currentApps
            true
        }

        if (!succeeded) return false

        // Update in-memory anti-repeat memory keyed by canonical component identity
        for (prevApp in executedPreviousApps) {
            if (prevApp.isShuffled && !prevApp.isFixed && prevApp.folderId == null && !prevApp.isPinnedToDock) {
                val deque = recentShufflePositions.getOrPut(prevApp.componentKey) { ArrayDeque() }
                deque.addLast(prevApp.pageIndex to prevApp.slotIndex)
                while (deque.size > 5) deque.removeFirst()
            }
        }
        executedTimestamp?.let { preferences.setLastShuffleTime(it) }
        return true
    }

    suspend fun undoLastShuffle(): Boolean = undoLastShuffleDetailed().isAppliedOrPartial

    suspend fun undoLastShuffleDetailed(): UndoShuffleResult = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val dockCount = preferences.dockCountFlow.first().coerceIn(3, 7)
            val prefDrawerShuffleEnabled = preferences.drawerShuffleEnabledFlow.first()

            val latestSnapshot = shuffleHistoryDao.getLatestSnapshot()
                ?: return@withMutationLock UndoShuffleResult(
                    status = UndoShuffleStatus.NO_SNAPSHOT,
                    failureReason = "No previous shuffle snapshot available"
                )

            val deltaSnapshot = ShuffleDeltaSnapshot.fromJson(latestSnapshot.snapshotJson)
                ?: return@withMutationLock UndoShuffleResult(
                    status = UndoShuffleStatus.FAILED,
                    failureReason = "Invalid or incompatible shuffle snapshot"
                )

            try {
                runInTransaction {
                    val currentSnapshot = shuffleHistoryDao.getLatestSnapshot()
                        ?: return@runInTransaction UndoShuffleResult(
                            status = UndoShuffleStatus.NO_SNAPSHOT,
                            failureReason = "No previous shuffle snapshot available"
                        )
                    if (currentSnapshot.snapshotId != latestSnapshot.snapshotId) {
                        return@runInTransaction UndoShuffleResult(
                            status = UndoShuffleStatus.UNAVAILABLE,
                            failureReason = "Snapshot superseded by newer shuffle"
                        )
                    }

                    val latestHistory = shuffleHistoryDao.getRecentHistoryList().firstOrNull()
                    if (latestHistory != null && currentSnapshot.historyId != null && latestHistory.id != currentSnapshot.historyId) {
                        shuffleHistoryDao.deleteSnapshotById(currentSnapshot.snapshotId)
                        return@runInTransaction UndoShuffleResult(
                            status = UndoShuffleStatus.UNAVAILABLE,
                            failureReason = "A newer shuffle invalidated this undo snapshot"
                        )
                    }

                    val allInstalled = installedAppDao.getAllAppsList()
                    val pages = workspaceDao.getPagesList()
                    val items = workspaceDao.getWorkspaceItemsList()
                    val folders = workspaceDao.getFoldersList()
                    val members = workspaceDao.getFolderMembersList()
                    val drawers = workspaceDao.getDrawerLayoutsList()
                    val focusList = workspaceDao.getFocusAppsList()

                    val installedByComp = allInstalled.associateBy { it.packageName to it.activityName }
                    val pagesById = pages.associateBy { it.pageId }
                    val homeItemByComp = items
                        .filter {
                            it.containerType == WorkspaceContainers.HOME &&
                                it.itemType == WorkspaceItemTypes.APP &&
                                it.packageName != null
                        }
                        .associateBy { it.packageName!! to (it.activityName ?: "") }
                    val memberByComp = members.associateBy { it.packageName to it.activityName }
                    val drawerByComp = drawers.associateBy { it.packageName to it.activityName }

                    val skippedComponents = linkedSetOf<String>()

                    data class PendingHomeRestore(
                        val comp: Pair<String, String>,
                        val currentItem: WorkspaceItemEntity,
                        val targetPageId: String,
                        val targetRank: Int
                    )

                    data class PendingDrawerRestore(
                        val comp: Pair<String, String>,
                        val currentDrawer: DrawerLayoutEntity,
                        val targetRank: Int
                    )

                    val candidateHome = mutableListOf<PendingHomeRestore>()
                    val candidateDrawer = mutableListOf<PendingDrawerRestore>()

                    for (entry in deltaSnapshot.entries) {
                        val comp = entry.packageName to entry.activityName
                        val compKey = "${entry.packageName}/${entry.activityName}"
                        val installed = installedByComp[comp]
                        val isInstalled = installed != null && installed.isInstalled

                        if (entry.originalHome != null && entry.resultingHome != null) {
                            val homeItem = homeItemByComp[comp]
                            val inFolder = comp in memberByComp
                            val originPageExists = entry.originalHome.pageId in pagesById
                            val stillAtResultingHome = homeItem != null &&
                                homeItem.pageId == entry.resultingHome.pageId &&
                                homeItem.rank == entry.resultingHome.rank

                            if (!isInstalled || inFolder || homeItem == null || homeItem.isFixed || !stillAtResultingHome || !originPageExists) {
                                skippedComponents.add(compKey)
                            } else {
                                candidateHome.add(
                                    PendingHomeRestore(
                                        comp = comp,
                                        currentItem = homeItem,
                                        targetPageId = entry.originalHome.pageId,
                                        targetRank = entry.originalHome.rank
                                    )
                                )
                            }
                        }

                        if (deltaSnapshot.drawerShuffleEnabled && entry.originalDrawerRank != null && entry.resultingDrawerRank != null) {
                            val drawer = drawerByComp[comp]
                            val stillAtResultingDrawer = drawer != null && drawer.drawerRank == entry.resultingDrawerRank
                            if (!isInstalled || drawer == null || drawer.isFixed || !stillAtResultingDrawer) {
                                skippedComponents.add(compKey)
                            } else {
                                candidateDrawer.add(
                                    PendingDrawerRestore(
                                        comp = comp,
                                        currentDrawer = drawer,
                                        targetRank = entry.originalDrawerRank
                                    )
                                )
                            }
                        }
                    }

                    // Resolve slot collisions cleanly across simultaneous swap cycles and non-restorable items
                    val candidateHomeItemIds = candidateHome.map { it.currentItem.itemId }.toSet()
                    val staticOccupiedHome = mutableSetOf<Pair<String, Int>>()
                    for (item in items) {
                        if (item.containerType == WorkspaceContainers.HOME && item.pageId != null && item.itemId !in candidateHomeItemIds) {
                            val span = item.columnSpan.coerceAtLeast(1) * item.rowSpan.coerceAtLeast(1)
                            for (offset in 0 until span) {
                                staticOccupiedHome.add(item.pageId to (item.rank + offset))
                            }
                        }
                    }

                    do {
                        var pruned = false
                        val seenTargets = mutableSetOf<Pair<String, Int>>()
                        val iterator = candidateHome.iterator()
                        while (iterator.hasNext()) {
                            val cand = iterator.next()
                            val targetCell = cand.targetPageId to cand.targetRank
                            if (targetCell in staticOccupiedHome || !seenTargets.add(targetCell)) {
                                iterator.remove()
                                skippedComponents.add("${cand.comp.first}/${cand.comp.second}")
                                cand.currentItem.pageId?.let { curPage ->
                                    staticOccupiedHome.add(curPage to cand.currentItem.rank)
                                }
                                pruned = true
                            }
                        }
                    } while (pruned)

                    // Resolve drawer rank collisions across simultaneous swap cycles
                    val candidateDrawerComps = candidateDrawer.map { it.comp }.toSet()
                    val staticOccupiedDrawerRanks = mutableSetOf<Int>()
                    for (d in drawers) {
                        val comp = d.packageName to d.activityName
                        if (comp !in candidateDrawerComps && d.drawerRank >= 0 && d.isDrawerShuffled && !d.isFixed) {
                            staticOccupiedDrawerRanks.add(d.drawerRank)
                        }
                    }

                    do {
                        var pruned = false
                        val seenTargets = mutableSetOf<Int>()
                        val iterator = candidateDrawer.iterator()
                        while (iterator.hasNext()) {
                            val cand = iterator.next()
                            val targetRank = cand.targetRank
                            val collides = targetRank >= 0 &&
                                (targetRank in staticOccupiedDrawerRanks || !seenTargets.add(targetRank))
                            if (collides) {
                                iterator.remove()
                                skippedComponents.add("${cand.comp.first}/${cand.comp.second}")
                                val cur = cand.currentDrawer
                                if (cur.drawerRank >= 0 && cur.isDrawerShuffled && !cur.isFixed) {
                                    staticOccupiedDrawerRanks.add(cur.drawerRank)
                                }
                                pruned = true
                            }
                        }
                    } while (pruned)

                    if (candidateHome.isEmpty() && candidateDrawer.isEmpty()) {
                        shuffleHistoryDao.deleteSnapshotById(currentSnapshot.snapshotId)
                        return@runInTransaction UndoShuffleResult(
                            status = UndoShuffleStatus.UNAVAILABLE,
                            skippedComponents = skippedComponents.toList(),
                            failureReason = "No pre-shuffle placements remain safe to restore"
                        )
                    }

                    val updatedHomeById = candidateHome.associate { cand ->
                        cand.currentItem.itemId to cand.currentItem.copy(
                            pageId = cand.targetPageId,
                            rank = cand.targetRank
                        )
                    }
                    val updatedDrawerByComp = candidateDrawer.associate { cand ->
                        cand.comp to cand.currentDrawer.copy(drawerRank = cand.targetRank)
                    }

                    val projectedItems = items.map { updatedHomeById[it.itemId] ?: it }
                    val projectedDrawers = drawers.map { updatedDrawerByComp[it.packageName to it.activityName] ?: it }
                    val preCommitValidation = workspaceValidator.validateNormalizedWorkspace(
                        installedApps = allInstalled,
                        pages = pages,
                        workspaceItems = projectedItems,
                        folders = folders,
                        folderMembers = members,
                        drawerLayouts = projectedDrawers,
                        focusApps = focusList,
                        dockCount = dockCount,
                        drawerShuffleEnabled = prefDrawerShuffleEnabled || deltaSnapshot.drawerShuffleEnabled
                    )
                    if (!preCommitValidation.isValid) {
                        throw IllegalStateException(
                            "Undo aborted by invariant check: ${preCommitValidation.errors.joinToString("; ")}"
                        )
                    }

                    val homeUpdates = updatedHomeById.values.toList()
                    val drawerUpdates = updatedDrawerByComp.values.toList()
                    if (homeUpdates.isNotEmpty()) {
                        workspaceDao.upsertWorkspaceItems(homeUpdates)
                    }
                    if (drawerUpdates.isNotEmpty()) {
                        workspaceDao.upsertDrawerLayouts(drawerUpdates)
                    }
                    shuffleHistoryDao.deleteSnapshotById(currentSnapshot.snapshotId)

                    val postWriteValidation = workspaceValidator.validateNormalizedWorkspace(
                        installedApps = installedAppDao.getAllAppsList(),
                        pages = workspaceDao.getPagesList(),
                        workspaceItems = workspaceDao.getWorkspaceItemsList(),
                        folders = workspaceDao.getFoldersList(),
                        folderMembers = workspaceDao.getFolderMembersList(),
                        drawerLayouts = workspaceDao.getDrawerLayoutsList(),
                        focusApps = workspaceDao.getFocusAppsList(),
                        dockCount = dockCount,
                        drawerShuffleEnabled = prefDrawerShuffleEnabled || deltaSnapshot.drawerShuffleEnabled
                    )
                    if (!postWriteValidation.isValid) {
                        throw IllegalStateException(
                            "Post-write undo failed invariant check: ${postWriteValidation.errors.joinToString("; ")}"
                        )
                    }

                    val status = if (skippedComponents.isEmpty()) {
                        UndoShuffleStatus.APPLIED
                    } else {
                        UndoShuffleStatus.PARTIALLY_APPLIED
                    }
                    UndoShuffleResult(
                        status = status,
                        restoredHomeCount = homeUpdates.size,
                        restoredDrawerCount = drawerUpdates.size,
                        skippedComponents = skippedComponents.toList()
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                UndoShuffleResult(
                    status = UndoShuffleStatus.FAILED,
                    failureReason = t.message ?: "Failed to undo shuffle"
                )
            }
        }
    }

    // --- Component-aware App Actions ---

    suspend fun toggleAppShuffled(packageName: String, activityName: String, isShuffled: Boolean) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            toggleAppShuffledLocked(packageName, activityName, isShuffled)
        }
    }

    private suspend fun toggleAppShuffledLocked(packageName: String, activityName: String, isShuffled: Boolean) {
        runInTransaction {
            val matchingItems = workspaceDao.getWorkspaceItemsList()
                .filter { it.packageName == packageName && it.activityName == activityName }
                .map { it.copy(isShuffled = isShuffled, isFixed = if (isShuffled) false else it.isFixed) }
            val members = workspaceDao.getFolderMembersList()
                .filter { it.packageName == packageName && it.activityName == activityName }
                .map { it.copy(isShuffled = isShuffled, isFixed = if (isShuffled) false else it.isFixed) }
            val drawer = workspaceDao.getDrawerLayoutsList()
                .find { it.packageName == packageName && it.activityName == activityName }

            if (matchingItems.isNotEmpty()) workspaceDao.upsertWorkspaceItems(matchingItems)
            if (members.isNotEmpty()) workspaceDao.upsertFolderMembers(members)
            if (isShuffled && drawer != null && drawer.isFixed) {
                workspaceDao.upsertDrawerLayout(drawer.copy(isFixed = false))
            }
        }
    }

    suspend fun toggleAppShuffled(packageName: String, isShuffled: Boolean) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val target = installedAppDao.getAllAppsList().find { it.packageName == packageName } ?: return@withMutationLock
            toggleAppShuffledLocked(target.packageName, target.activityName, isShuffled)
        }
    }

    suspend fun toggleAppDrawerShuffled(packageName: String, activityName: String, isDrawerShuffled: Boolean) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            runInTransaction {
                val existing = workspaceDao.getDrawerLayoutsList()
                    .find { it.packageName == packageName && it.activityName == activityName }
                    ?: DrawerLayoutEntity(packageName, activityName)
                workspaceDao.upsertDrawerLayout(
                    existing.copy(
                        isDrawerShuffled = isDrawerShuffled,
                        isFixed = if (isDrawerShuffled) false else existing.isFixed
                    )
                )
            }
        }
    }

    suspend fun toggleAppFixed(packageName: String, activityName: String, isFixed: Boolean) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            toggleAppFixedLocked(packageName, activityName, isFixed)
        }
    }

    private suspend fun toggleAppFixedLocked(packageName: String, activityName: String, isFixed: Boolean) {
        runInTransaction {
            val items = workspaceDao.getWorkspaceItemsList()
                .filter { it.packageName == packageName && it.activityName == activityName }
                .map { it.copy(isFixed = isFixed, isShuffled = if (isFixed) false else it.isShuffled) }
            val members = workspaceDao.getFolderMembersList()
                .filter { it.packageName == packageName && it.activityName == activityName }
                .map { it.copy(isFixed = isFixed, isShuffled = if (isFixed) false else it.isShuffled) }
            val drawer = (workspaceDao.getDrawerLayoutsList()
                .find { it.packageName == packageName && it.activityName == activityName }
                ?: DrawerLayoutEntity(packageName, activityName))
                .copy(isFixed = isFixed)

            if (items.isNotEmpty()) workspaceDao.upsertWorkspaceItems(items)
            if (members.isNotEmpty()) workspaceDao.upsertFolderMembers(members)
            workspaceDao.upsertDrawerLayout(drawer)
        }
    }

    suspend fun toggleAppFixed(packageName: String, isFixed: Boolean) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val target = installedAppDao.getAllAppsList().find { it.packageName == packageName } ?: return@withMutationLock
            toggleAppFixedLocked(target.packageName, target.activityName, isFixed)
        }
    }

    suspend fun toggleAppPinnedToDock(packageName: String, activityName: String, isPinned: Boolean) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            toggleAppPinnedToDockLocked(packageName, activityName, isPinned)
        }
    }

    private suspend fun toggleAppPinnedToDockLocked(packageName: String, activityName: String, isPinned: Boolean) {
        val dockCount = preferences.dockCountFlow.first().coerceIn(3, 7)
        runInTransaction {
            val items = workspaceDao.getWorkspaceItemsList()
            val members = workspaceDao.getFolderMembersList()

            if (isPinned) {
                val currentDockItems = items.filter { it.containerType == WorkspaceContainers.DOCK }
                val alreadyPinned = currentDockItems.any { it.packageName == packageName && it.activityName == activityName }
                if (alreadyPinned) return@runInTransaction
                if (currentDockItems.size >= dockCount) return@runInTransaction

                val nextDockRank = (0 until dockCount).firstOrNull { candidate ->
                    currentDockItems.none { it.rank == candidate }
                } ?: currentDockItems.size

                val existingHomeItem = items.find {
                    it.containerType == WorkspaceContainers.HOME &&
                        it.packageName == packageName &&
                        it.activityName == activityName
                }
                val existingMember = members.find { it.packageName == packageName && it.activityName == activityName }
                val existingDrawer = workspaceDao.getDrawerLayoutsList()
                    .find { it.packageName == packageName && it.activityName == activityName }

                val isFixed = existingHomeItem?.isFixed ?: existingMember?.isFixed ?: existingDrawer?.isFixed ?: false
                val isShuffled = !isFixed && (existingHomeItem?.isShuffled ?: existingMember?.isShuffled ?: false)

                val dockItem = WorkspaceItemEntity(
                    itemId = dockItemId(packageName, activityName),
                    itemType = WorkspaceItemTypes.APP,
                    containerType = WorkspaceContainers.DOCK,
                    pageId = null,
                    packageName = packageName,
                    activityName = activityName,
                    rank = nextDockRank,
                    isFixed = isFixed,
                    isShuffled = isShuffled,
                    isHidden = existingHomeItem?.isHidden ?: existingDrawer?.isHidden ?: false
                )

                workspaceDao.deleteWorkspaceItemsForComponentInContainer(packageName, activityName, WorkspaceContainers.DOCK)
                workspaceDao.upsertWorkspaceItem(dockItem)
            } else {
                items.find {
                    it.containerType == WorkspaceContainers.DOCK &&
                        it.packageName == packageName &&
                        it.activityName == activityName
                } ?: return@runInTransaction

                val remainingDock = items
                    .filter {
                        it.containerType == WorkspaceContainers.DOCK &&
                            !(it.packageName == packageName && it.activityName == activityName)
                    }
                    .sortedBy { it.rank }
                    .mapIndexed { idx, dockItem -> dockItem.copy(rank = idx) }

                workspaceDao.deleteWorkspaceItemsForComponentInContainer(packageName, activityName, WorkspaceContainers.DOCK)
                if (remainingDock.isNotEmpty()) workspaceDao.upsertWorkspaceItems(remainingDock)
            }
        }
    }

    suspend fun toggleAppPinnedToDock(packageName: String, isPinned: Boolean) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val target = installedAppDao.getAllAppsList().find { it.packageName == packageName } ?: return@withMutationLock
            toggleAppPinnedToDockLocked(target.packageName, target.activityName, isPinned)
        }
    }

    suspend fun reorderDockApps(orderedComponents: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            runInTransaction {
                val dockItems = workspaceDao.getWorkspaceItemsList()
                    .filter {
                        it.containerType == WorkspaceContainers.DOCK &&
                            it.itemType == WorkspaceItemTypes.APP &&
                            it.packageName != null
                    }
                if (dockItems.isEmpty()) return@runInTransaction

                val byComponent = dockItems.associateBy { it.packageName!! to (it.activityName ?: "") }
                val ordered = mutableListOf<WorkspaceItemEntity>()
                val seen = mutableSetOf<Pair<String, String>>()

                for (comp in orderedComponents) {
                    val item = byComponent[comp] ?: continue
                    if (seen.add(comp)) {
                        ordered.add(item)
                    }
                }
                for (remaining in dockItems.sortedBy { it.rank }) {
                    val comp = remaining.packageName!! to (remaining.activityName ?: "")
                    if (seen.add(comp)) {
                        ordered.add(remaining)
                    }
                }

                val reordered = ordered.mapIndexed { idx, item -> item.copy(rank = idx) }
                workspaceDao.upsertWorkspaceItems(reordered)
            }
        }
    }

    suspend fun setAppPageAndSlot(packageName: String, activityName: String, pageIndex: Int, slotIndex: Int) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            setAppPageAndSlotLocked(packageName, activityName, pageIndex, slotIndex)
        }
    }

    private suspend fun setAppPageAndSlotLocked(packageName: String, activityName: String, pageIndex: Int, slotIndex: Int) {
        val prefPageCount = preferences.pageCountFlow.first().coerceAtLeast(1)
        runInTransaction {
            val rawPages = workspaceDao.getPagesList()
            val pages = ensurePagesList(rawPages, prefPageCount)
            val safePageIndex = pageIndex.coerceIn(0, pages.size - 1)
            val targetPage = pages.find { it.pageIndex == safePageIndex } ?: pages.first()
            val items = workspaceDao.getWorkspaceItemsList()
            val existingHome = items.find {
                it.containerType == WorkspaceContainers.HOME &&
                    it.packageName == packageName &&
                    it.activityName == activityName
            }
            val existingDock = items.find {
                it.containerType == WorkspaceContainers.DOCK &&
                    it.packageName == packageName &&
                    it.activityName == activityName
            }
            val existingMember = workspaceDao.getFolderMembersList()
                .find { it.packageName == packageName && it.activityName == activityName }

            val filteredHomeItems = items.filter {
                !(it.containerType == WorkspaceContainers.HOME &&
                    it.packageName == packageName &&
                    it.activityName == activityName)
            }
            val assignedRank = nextFreeRankForPage(targetPage.pageId, slotIndex.coerceAtLeast(0), filteredHomeItems)
            val isFixed = existingHome?.isFixed ?: existingMember?.isFixed ?: existingDock?.isFixed ?: false
            val isShuffled = !isFixed && (existingHome?.isShuffled ?: existingMember?.isShuffled ?: existingDock?.isShuffled ?: false)

            val homeItem = WorkspaceItemEntity(
                itemId = homeItemId(packageName, activityName),
                itemType = WorkspaceItemTypes.APP,
                containerType = WorkspaceContainers.HOME,
                pageId = targetPage.pageId,
                packageName = packageName,
                activityName = activityName,
                rank = assignedRank,
                isFixed = isFixed,
                isShuffled = isShuffled,
                isHidden = existingHome?.isHidden ?: existingDock?.isHidden ?: false
            )

            if (rawPages.isEmpty()) workspaceDao.insertPages(pages)
            workspaceDao.deleteWorkspaceItemsForComponentInContainer(packageName, activityName, WorkspaceContainers.HOME)
            workspaceDao.deleteFolderMembersForComponent(packageName, activityName)
            workspaceDao.upsertWorkspaceItem(homeItem)
            existingMember?.folderId?.let { reconcileSourceFoldersLocked(listOf(it), prefPageCount) }
        }
    }

    suspend fun setAppPageAndSlot(packageName: String, pageIndex: Int, slotIndex: Int) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val target = installedAppDao.getAllAppsList().find { it.packageName == packageName } ?: return@withMutationLock
            setAppPageAndSlotLocked(target.packageName, target.activityName, pageIndex, slotIndex)
        }
    }

    /**
     * Transactionally moves a HOME app icon to [targetPageIndex] / [targetSlotIndex] using
     * [WorkspacePlacementPlanner] collision resolution and pre/post [WorkspaceValidator] checks.
     * Preserves fixed apps, folders, widgets, dock placements, and optional empty spaces.
     */
    suspend fun moveAppOnHomeScreen(
        packageName: String,
        activityName: String,
        targetPageIndex: Int,
        targetSlotIndex: Int,
        gridColumns: Int,
        preserveEmptySpaces: Boolean = true
    ): MoveWorkspaceAppResult = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val dockCount = preferences.dockCountFlow.first().coerceIn(3, 7)
            val drawerShuffleEnabled = preferences.drawerShuffleEnabledFlow.first()

            try {
                runInTransaction {
                    val allInstalled = installedAppDao.getAllAppsList()
                    val installed = allInstalled.filter { it.isInstalled }
                    val pages = workspaceDao.getPagesList().sortedBy { it.pageIndex }
                    val items = workspaceDao.getWorkspaceItemsList()
                    val foldersEntities = workspaceDao.getFoldersList()
                    val members = workspaceDao.getFolderMembersList()
                    val drawers = workspaceDao.getDrawerLayoutsList()
                    val focusList = workspaceDao.getFocusAppsList()

                    if (pages.isEmpty()) {
                        return@runInTransaction MoveWorkspaceAppResult(false, "Workspace has no home pages")
                    }
                    if (targetPageIndex !in pages.indices || pages.none { it.pageIndex == targetPageIndex }) {
                        return@runInTransaction MoveWorkspaceAppResult(false, "Target page ${targetPageIndex + 1} no longer exists")
                    }

                    val targetAppEntity = installed.find { it.packageName == packageName && it.activityName == activityName }
                        ?: return@runInTransaction MoveWorkspaceAppResult(false, "Application is no longer installed")

                    val currentApps = projectAppItems(installed, pages, items, members, drawers, focusList)
                    val movingApp = currentApps.find { it.packageName == packageName && it.activityName == activityName }
                        ?: return@runInTransaction MoveWorkspaceAppResult(false, "Application not found in workspace")

                    if (!movingApp.isOnHomeScreen || movingApp.folderId != null) {
                        return@runInTransaction MoveWorkspaceAppResult(false, "${targetAppEntity.label} is not placed on the Home screen")
                    }
                    if (movingApp.isFixed) {
                        return@runInTransaction MoveWorkspaceAppResult(false, "${targetAppEntity.label} is fixed and cannot be moved")
                    }

                    val folders = projectFolders(foldersEntities, items, pages)
                    val widgets = projectWidgets(items, pages)

                    val plan = placementPlanner.planMove(
                        PlacementMoveRequest(
                            packageName = packageName,
                            activityName = activityName,
                            targetPageIndex = targetPageIndex,
                            targetSlotIndex = targetSlotIndex,
                            gridColumns = gridColumns,
                            pageCount = pages.size,
                            preserveEmptySpaces = preserveEmptySpaces,
                            apps = currentApps,
                            folders = folders,
                            widgets = widgets
                        )
                    )

                    if (!plan.isValid) {
                        return@runInTransaction MoveWorkspaceAppResult(false, plan.rejectionReason ?: "Invalid drop target")
                    }

                    if (plan.changedHomePlacements.isEmpty()) {
                        return@runInTransaction MoveWorkspaceAppResult(true, movedCount = 0)
                    }

                    val pageIndexToId = pages.associate { it.pageIndex to it.pageId }
                    val homeItemByComponent = items
                        .filter { it.containerType == WorkspaceContainers.HOME && it.itemType == WorkspaceItemTypes.APP && it.packageName != null }
                        .associateBy { it.packageName!! to (it.activityName ?: "") }

                    val updatedHomeItems = mutableListOf<WorkspaceItemEntity>()
                    val updatedByItemId = mutableMapOf<String, WorkspaceItemEntity>()

                    for ((comp, newCoord) in plan.changedHomePlacements) {
                        val existingHome = homeItemByComponent[comp]
                            ?: throw IllegalStateException("Missing HOME item for ${comp.first}/${comp.second}")
                        val newPageId = pageIndexToId[newCoord.first]
                            ?: throw IllegalStateException("Missing pageId for pageIndex ${newCoord.first}")
                        val updated = existingHome.copy(
                            pageId = newPageId,
                            rank = newCoord.second
                        )
                        updatedHomeItems.add(updated)
                        updatedByItemId[updated.itemId] = updated
                    }

                    val projectedItems = items.map { updatedByItemId[it.itemId] ?: it }
                    val preCommitValidation = workspaceValidator.validateNormalizedWorkspace(
                        installedApps = allInstalled,
                        pages = pages,
                        workspaceItems = projectedItems,
                        folders = foldersEntities,
                        folderMembers = members,
                        drawerLayouts = drawers,
                        focusApps = focusList,
                        dockCount = dockCount,
                        drawerShuffleEnabled = drawerShuffleEnabled
                    )
                    if (!preCommitValidation.isValid) {
                        throw IllegalStateException(
                            "Move aborted by invariant check: ${preCommitValidation.errors.joinToString("; ")}"
                        )
                    }

                    workspaceDao.upsertWorkspaceItems(updatedHomeItems)

                    val postWriteValidation = workspaceValidator.validateNormalizedWorkspace(
                        installedApps = installedAppDao.getAllAppsList(),
                        pages = workspaceDao.getPagesList(),
                        workspaceItems = workspaceDao.getWorkspaceItemsList(),
                        folders = workspaceDao.getFoldersList(),
                        folderMembers = workspaceDao.getFolderMembersList(),
                        drawerLayouts = workspaceDao.getDrawerLayoutsList(),
                        focusApps = workspaceDao.getFocusAppsList(),
                        dockCount = dockCount,
                        drawerShuffleEnabled = drawerShuffleEnabled
                    )
                    if (!postWriteValidation.isValid) {
                        throw IllegalStateException(
                            "Post-write move failed invariant check: ${postWriteValidation.errors.joinToString("; ")}"
                        )
                    }

                    MoveWorkspaceAppResult(success = true, movedCount = updatedHomeItems.size)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                MoveWorkspaceAppResult(
                    success = false,
                    failureReason = t.message ?: "Failed to move application"
                )
            }
        }
    }

    suspend fun removeAppFromHomeScreen(packageName: String, activityName: String) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            removeAppFromHomeScreenLocked(packageName, activityName)
        }
    }

    private suspend fun removeAppFromHomeScreenLocked(packageName: String, activityName: String) {
        val prefPageCount = preferences.pageCountFlow.first().coerceAtLeast(1)
        runInTransaction {
            val prevMemberFolderId = workspaceDao.getFolderMembersList()
                .find { it.packageName == packageName && it.activityName == activityName }
                ?.folderId
            workspaceDao.deleteWorkspaceItemsForComponentInContainer(packageName, activityName, WorkspaceContainers.HOME)
            workspaceDao.deleteFolderMembersForComponent(packageName, activityName)
            prevMemberFolderId?.let { reconcileSourceFoldersLocked(listOf(it), prefPageCount) }
        }
    }

    suspend fun removeAppFromHomeScreen(packageName: String) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val target = installedAppDao.getAllAppsList().find { it.packageName == packageName } ?: return@withMutationLock
            removeAppFromHomeScreenLocked(target.packageName, target.activityName)
        }
    }

    suspend fun addAppToHomeScreen(packageName: String, activityName: String, pageIndex: Int) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val prefPageCount = preferences.pageCountFlow.first().coerceAtLeast(1)
            runInTransaction {
                val rawPages = workspaceDao.getPagesList()
                val pages = ensurePagesList(rawPages, prefPageCount)
                val safePageIndex = pageIndex.coerceIn(0, pages.size - 1)
                val targetPage = pages.find { it.pageIndex == safePageIndex } ?: pages.first()
                val items = workspaceDao.getWorkspaceItemsList()
                val existingHome = items.find {
                    it.containerType == WorkspaceContainers.HOME &&
                        it.packageName == packageName &&
                        it.activityName == activityName
                }
                val existingDock = items.find {
                    it.containerType == WorkspaceContainers.DOCK &&
                        it.packageName == packageName &&
                        it.activityName == activityName
                }
                val existingMember = workspaceDao.getFolderMembersList()
                    .find { it.packageName == packageName && it.activityName == activityName }

                val filteredHomeItems = items.filter {
                    !(it.containerType == WorkspaceContainers.HOME &&
                        it.packageName == packageName &&
                        it.activityName == activityName)
                }
                val maxRank = filteredHomeItems
                    .filter { it.containerType == WorkspaceContainers.HOME && it.pageId == targetPage.pageId }
                    .maxOfOrNull { it.rank + (it.columnSpan.coerceAtLeast(1) * it.rowSpan.coerceAtLeast(1)) - 1 } ?: -1
                val nextRank = maxRank + 1

                val isFixed = existingHome?.isFixed ?: existingMember?.isFixed ?: existingDock?.isFixed ?: false
                val isShuffled = !isFixed && (existingHome?.isShuffled ?: existingMember?.isShuffled ?: existingDock?.isShuffled ?: false)

                val homeItem = WorkspaceItemEntity(
                    itemId = homeItemId(packageName, activityName),
                    itemType = WorkspaceItemTypes.APP,
                    containerType = WorkspaceContainers.HOME,
                    pageId = targetPage.pageId,
                    packageName = packageName,
                    activityName = activityName,
                    rank = nextRank,
                    isFixed = isFixed,
                    isShuffled = isShuffled,
                    isHidden = existingHome?.isHidden ?: existingDock?.isHidden ?: false
                )

                if (rawPages.isEmpty()) workspaceDao.insertPages(pages)
                workspaceDao.deleteWorkspaceItemsForComponentInContainer(packageName, activityName, WorkspaceContainers.HOME)
                workspaceDao.deleteFolderMembersForComponent(packageName, activityName)
                workspaceDao.upsertWorkspaceItem(homeItem)
                existingMember?.folderId?.let { reconcileSourceFoldersLocked(listOf(it), prefPageCount) }
            }
        }
    }

    suspend fun toggleFocusVisibility(packageName: String, activityName: String, isVisible: Boolean) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            toggleFocusVisibilityLocked(packageName, activityName, isVisible)
        }
    }

    private suspend fun toggleFocusVisibilityLocked(packageName: String, activityName: String, isVisible: Boolean) {
        runInTransaction {
            val existing = workspaceDao.getFocusAppsList()
                .find { it.packageName == packageName && it.activityName == activityName }
                ?: FocusAppEntity(packageName, activityName)
            workspaceDao.upsertFocusApp(existing.copy(isVisibleInFocus = isVisible))
        }
    }

    suspend fun toggleFocusVisibility(packageName: String, isVisible: Boolean) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val target = installedAppDao.getAllAppsList().find { it.packageName == packageName } ?: return@withMutationLock
            toggleFocusVisibilityLocked(target.packageName, target.activityName, isVisible)
        }
    }

    suspend fun setAllFocusVisibility(components: List<Pair<String, String>>, isVisible: Boolean) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            runInTransaction {
                val existing = workspaceDao.getFocusAppsList().associateBy { it.packageName to it.activityName }
                val updated = components.distinct().map { comp ->
                    val current = existing[comp] ?: FocusAppEntity(comp.first, comp.second)
                    current.copy(isVisibleInFocus = isVisible)
                }
                if (updated.isNotEmpty()) workspaceDao.upsertFocusApps(updated)
            }
        }
    }

    suspend fun toggleFocusEssential(packageName: String, activityName: String, isEssential: Boolean) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            toggleFocusEssentialLocked(packageName, activityName, isEssential)
        }
    }

    private suspend fun toggleFocusEssentialLocked(packageName: String, activityName: String, isEssential: Boolean) {
        runInTransaction {
            val existingFocus = workspaceDao.getFocusAppsList()
                .find { it.packageName == packageName && it.activityName == activityName }
                ?: FocusAppEntity(packageName, activityName)

            val updatedItems = if (isEssential) {
                workspaceDao.getWorkspaceItemsList()
                    .filter { it.packageName == packageName && it.activityName == activityName }
                    .map { it.copy(isFixed = true, isShuffled = false) }
            } else emptyList()

            val updatedMembers = if (isEssential) {
                workspaceDao.getFolderMembersList()
                    .filter { it.packageName == packageName && it.activityName == activityName }
                    .map { it.copy(isFixed = true, isShuffled = false) }
            } else emptyList()

            val drawer = workspaceDao.getDrawerLayoutsList()
                .find { it.packageName == packageName && it.activityName == activityName }

            workspaceDao.upsertFocusApp(existingFocus.copy(isEssentialInFocus = isEssential))
            if (updatedItems.isNotEmpty()) workspaceDao.upsertWorkspaceItems(updatedItems)
            if (updatedMembers.isNotEmpty()) workspaceDao.upsertFolderMembers(updatedMembers)
            if (isEssential) {
                workspaceDao.upsertDrawerLayout(
                    (drawer ?: DrawerLayoutEntity(packageName, activityName)).copy(isFixed = true)
                )
            }
        }
    }

    suspend fun toggleFocusEssential(packageName: String, isEssential: Boolean) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val target = installedAppDao.getAllAppsList().find { it.packageName == packageName } ?: return@withMutationLock
            toggleFocusEssentialLocked(target.packageName, target.activityName, isEssential)
        }
    }

    // --- Page Operations ---

    suspend fun addPage() = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val prefPageCount = preferences.pageCountFlow.first().coerceAtLeast(1)
            val updatedCount = runInTransaction {
                val rawPages = workspaceDao.getPagesList()
                val existingPages = ensurePagesList(rawPages, prefPageCount)
                if (rawPages.isEmpty()) {
                    workspaceDao.insertPages(existingPages)
                }
                val nextIndex = (existingPages.maxOfOrNull { it.pageIndex } ?: -1) + 1
                val newPage = WorkspacePageEntity(
                    pageId = "page_${UUID.randomUUID()}",
                    pageIndex = nextIndex,
                    isDefault = false,
                    createdAt = System.currentTimeMillis()
                )
                workspaceDao.insertPage(newPage)
                existingPages.size + 1
            }
            preferences.setPageCount(updatedCount)
        }
    }

    suspend fun removePage(pageIndex: Int): Boolean = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val prefPageCount = preferences.pageCountFlow.first().coerceAtLeast(1)
            val reindexedPages = runInTransaction {
                val rawPages = workspaceDao.getPagesList()
                val existingPages = ensurePagesList(rawPages, prefPageCount).sortedBy { it.pageIndex }
                if (rawPages.isEmpty()) {
                    workspaceDao.insertPages(existingPages)
                }
                if (existingPages.size <= 1) return@runInTransaction null
                val targetPage = existingPages.find { it.pageIndex == pageIndex } ?: return@runInTransaction null
                val remainingPages = existingPages.filter { it.pageId != targetPage.pageId }
                val fallbackPage = remainingPages.first()

                val items = workspaceDao.getWorkspaceItemsList()
                val itemsOnTarget = items.filter { it.containerType == WorkspaceContainers.HOME && it.pageId == targetPage.pageId }
                val remainingItems = items.filter { !(it.containerType == WorkspaceContainers.HOME && it.pageId == targetPage.pageId) }.toMutableList()

                val reassignedItems = mutableListOf<WorkspaceItemEntity>()
                for (item in itemsOnTarget.sortedBy { it.rank }) {
                    val span = item.columnSpan.coerceAtLeast(1) * item.rowSpan.coerceAtLeast(1)
                    val newRank = nextFreeRankForPage(fallbackPage.pageId, item.rank, remainingItems, span)
                    val moved = item.copy(pageId = fallbackPage.pageId, rank = newRank)
                    reassignedItems.add(moved)
                    remainingItems.add(moved)
                }

                // Re-index remaining pages contiguously 0..N-1 while keeping stable pageIds
                val wasDefaultRemoved = targetPage.isDefault
                val reindexed = remainingPages.mapIndexed { idx, p ->
                    p.copy(
                        pageIndex = idx,
                        isDefault = if (wasDefaultRemoved) idx == 0 else p.isDefault
                    )
                }

                if (reassignedItems.isNotEmpty()) workspaceDao.upsertWorkspaceItems(reassignedItems)
                workspaceDao.deletePage(targetPage.pageId)
                for (p in reindexed) {
                    workspaceDao.updatePageMetadata(p.pageId, p.pageIndex, p.isDefault)
                }
                reindexed
            } ?: return@withMutationLock false

            preferences.setPageCount(reindexedPages.size)
            val newDefaultIndex = reindexedPages.indexOfFirst { it.isDefault }.coerceAtLeast(0)
            preferences.setDefaultPageIndex(newDefaultIndex)
            true
        }
    }

    suspend fun setDefaultPageIndex(index: Int) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val prefPageCount = preferences.pageCountFlow.first().coerceAtLeast(1)
            val safeIndex = runInTransaction {
                val rawPages = workspaceDao.getPagesList()
                val pages = ensurePagesList(rawPages, prefPageCount).sortedBy { it.pageIndex }
                if (rawPages.isEmpty()) {
                    workspaceDao.insertPages(pages)
                }
                val safe = index.coerceIn(0, pages.size - 1)
                for (p in pages) {
                    workspaceDao.updatePageMetadata(p.pageId, p.pageIndex, p.pageIndex == safe)
                }
                safe
            }
            preferences.setDefaultPageIndex(safeIndex)
        }
    }

    // --- Folder Operations ---

    suspend fun createFolder(
        name: String,
        pageIndex: Int,
        slotIndex: Int,
        components: List<Pair<String, String>>
    ): String = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val prefPageCount = preferences.pageCountFlow.first().coerceAtLeast(1)
            val dockCount = preferences.dockCountFlow.first().coerceIn(3, 7)
            val drawerShuffleEnabled = preferences.drawerShuffleEnabledFlow.first()
            val distinctComponents = components.distinct()
            require(distinctComponents.size >= 2) { "Folder must contain at least two applications" }
            val normalizedName = normalizeFolderNameOrDefault(name)

            val folderId = UUID.randomUUID().toString()
            runInTransaction {
                val allInstalled = installedAppDao.getAllAppsList()
                val installedSet = allInstalled.filter { it.isInstalled }.map { it.packageName to it.activityName }.toSet()
                require(distinctComponents.all { it in installedSet }) {
                    "All folder members must be installed applications"
                }

                val rawPages = workspaceDao.getPagesList()
                val pages = ensurePagesList(rawPages, prefPageCount)
                val safePageIndex = pageIndex.coerceIn(0, pages.size - 1)
                val targetPage = pages.find { it.pageIndex == safePageIndex } ?: pages.first()
                val items = workspaceDao.getWorkspaceItemsList()

                val compSet = distinctComponents.toSet()
                val existingHomeByComp = items
                    .filter { it.containerType == WorkspaceContainers.HOME && it.packageName != null }
                    .associateBy { it.packageName!! to (it.activityName ?: "") }
                val existingAnyByComp = items
                    .filter { it.packageName != null }
                    .associateBy { it.packageName!! to (it.activityName ?: "") }
                val existingMemberByComp = workspaceDao.getFolderMembersList()
                    .associateBy { it.packageName to it.activityName }

                val remainingItems = items.filter {
                    val comp = (it.packageName ?: "") to (it.activityName ?: "")
                    !(it.containerType == WorkspaceContainers.HOME && it.itemType == WorkspaceItemTypes.APP && comp in compSet)
                }
                val assignedRank = nextFreeRankForPage(targetPage.pageId, slotIndex.coerceAtLeast(0), remainingItems)

                val folderEntity = FolderEntity(
                    folderId = folderId,
                    name = normalizedName,
                    createdAt = System.currentTimeMillis()
                )
                val folderWorkspaceItem = WorkspaceItemEntity(
                    itemId = folderItemId(folderId),
                    itemType = WorkspaceItemTypes.FOLDER,
                    containerType = WorkspaceContainers.HOME,
                    pageId = targetPage.pageId,
                    folderId = folderId,
                    rank = assignedRank
                )
                val memberEntities = distinctComponents.mapIndexed { idx, (pkg, act) ->
                    val prevHome = existingHomeByComp[pkg to act]
                    val prevMember = existingMemberByComp[pkg to act]
                    val prevAny = existingAnyByComp[pkg to act]
                    val isFixed = prevHome?.isFixed ?: prevMember?.isFixed ?: prevAny?.isFixed ?: false
                    val isShuffled = !isFixed && (prevHome?.isShuffled ?: prevMember?.isShuffled ?: prevAny?.isShuffled ?: false)
                    FolderMemberEntity(
                        folderId = folderId,
                        packageName = pkg,
                        activityName = act,
                        rank = idx,
                        isFixed = isFixed,
                        isShuffled = isShuffled
                    )
                }

                val affectedOldFolders = distinctComponents.mapNotNull { existingMemberByComp[it]?.folderId }.distinct()
                if (rawPages.isEmpty()) workspaceDao.insertPages(pages)
                workspaceDao.upsertFolder(folderEntity)
                for ((pkg, act) in distinctComponents) {
                    workspaceDao.deleteWorkspaceItemsForComponentInContainer(pkg, act, WorkspaceContainers.HOME)
                    workspaceDao.deleteFolderMembersForComponent(pkg, act)
                }
                workspaceDao.upsertFolderMembers(memberEntities)
                workspaceDao.upsertWorkspaceItem(folderWorkspaceItem)
                reconcileSourceFoldersLocked(affectedOldFolders, prefPageCount)

                validateNormalizedDbStateOrThrow(dockCount, drawerShuffleEnabled)
            }
            folderId
        }
    }

    /**
     * Atomically creates a new folder at the target Home app's exact (pageId, rank) containing
     * [targetPackageName]/[targetActivityName] (rank 0) and [draggedPackageName]/[draggedActivityName] (rank 1).
     * Rejects fixed apps, uninstalled apps, non-Home apps, and self-folder attempts.
     * Preserves independent Dock placements.
     */
    suspend fun createFolderFromHomeApps(
        draggedPackageName: String,
        draggedActivityName: String,
        targetPackageName: String,
        targetActivityName: String,
        name: String = DEFAULT_FOLDER_NAME
    ): FolderMutationResult = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            if (draggedPackageName == targetPackageName && draggedActivityName == targetActivityName) {
                return@withMutationLock FolderMutationResult(false, null, "Cannot create a folder containing the same app twice")
            }
            val normalizedName = normalizeFolderNameOrDefault(name)
            val dockCount = preferences.dockCountFlow.first().coerceIn(3, 7)
            val drawerShuffleEnabled = preferences.drawerShuffleEnabledFlow.first()
            val prefPageCount = preferences.pageCountFlow.first().coerceAtLeast(1)

            try {
                runInTransaction {
                    val allInstalled = installedAppDao.getAllAppsList()
                    val installed = allInstalled.filter { it.isInstalled }
                    val rawPages = workspaceDao.getPagesList()
                    val pages = ensurePagesList(rawPages, prefPageCount)
                    if (rawPages.isEmpty()) workspaceDao.insertPages(pages)
                    val items = workspaceDao.getWorkspaceItemsList()
                    val members = workspaceDao.getFolderMembersList()
                    val drawers = workspaceDao.getDrawerLayoutsList()
                    val focusList = workspaceDao.getFocusAppsList()

                    val currentApps = projectAppItems(installed, pages, items, members, drawers, focusList)
                    val draggedApp = currentApps.find {
                        it.packageName == draggedPackageName && it.activityName == draggedActivityName
                    } ?: return@runInTransaction FolderMutationResult(false, null, "Dragged application is not installed")
                    val targetApp = currentApps.find {
                        it.packageName == targetPackageName && it.activityName == targetActivityName
                    } ?: return@runInTransaction FolderMutationResult(false, null, "Target application is not installed")

                    val (canCreate, rejectReason) = placementPlanner.canCreateFolderFromApps(draggedApp, targetApp)
                    if (!canCreate) {
                        return@runInTransaction FolderMutationResult(false, null, rejectReason ?: "Cannot create folder")
                    }

                    val targetHomeItem = items.find {
                        it.containerType == WorkspaceContainers.HOME &&
                            it.itemType == WorkspaceItemTypes.APP &&
                            it.packageName == targetPackageName &&
                            it.activityName == targetActivityName
                    } ?: return@runInTransaction FolderMutationResult(false, null, "Target application is not on Home screen")

                    val targetPageId = targetHomeItem.pageId
                        ?: return@runInTransaction FolderMutationResult(false, null, "Target application has missing page")

                    val folderId = UUID.randomUUID().toString()
                    val folderEntity = FolderEntity(
                        folderId = folderId,
                        name = normalizedName,
                        createdAt = System.currentTimeMillis()
                    )
                    val folderWorkspaceItem = WorkspaceItemEntity(
                        itemId = folderItemId(folderId),
                        itemType = WorkspaceItemTypes.FOLDER,
                        containerType = WorkspaceContainers.HOME,
                        pageId = targetPageId,
                        folderId = folderId,
                        rank = targetHomeItem.rank
                    )

                    val memberEntities = listOf(
                        FolderMemberEntity(
                            folderId = folderId,
                            packageName = targetApp.packageName,
                            activityName = targetApp.activityName,
                            rank = 0,
                            isFixed = false,
                            isShuffled = targetApp.isShuffled
                        ),
                        FolderMemberEntity(
                            folderId = folderId,
                            packageName = draggedApp.packageName,
                            activityName = draggedApp.activityName,
                            rank = 1,
                            isFixed = false,
                            isShuffled = draggedApp.isShuffled
                        )
                    )

                    workspaceDao.upsertFolder(folderEntity)
                    workspaceDao.deleteWorkspaceItemsForComponentInContainer(
                        targetPackageName,
                        targetActivityName,
                        WorkspaceContainers.HOME
                    )
                    workspaceDao.deleteWorkspaceItemsForComponentInContainer(
                        draggedPackageName,
                        draggedActivityName,
                        WorkspaceContainers.HOME
                    )
                    workspaceDao.deleteFolderMembersForComponent(targetPackageName, targetActivityName)
                    workspaceDao.deleteFolderMembersForComponent(draggedPackageName, draggedActivityName)
                    workspaceDao.upsertFolderMembers(memberEntities)
                    workspaceDao.upsertWorkspaceItem(folderWorkspaceItem)

                    validateNormalizedDbStateOrThrow(dockCount, drawerShuffleEnabled)
                    FolderMutationResult(success = true, folderId = folderId)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                FolderMutationResult(false, null, t.message ?: "Failed to create folder")
            }
        }
    }

    /**
     * Transactionally moves a HOME folder to [targetPageIndex] / [targetSlotIndex] using
     * [WorkspacePlacementPlanner.planFolderMove], shifting movable Home apps if needed while
     * preserving folder membership, fixed apps, widgets, other folders, and Dock placements.
     */
    suspend fun moveFolderOnHomeScreen(
        folderId: String,
        targetPageIndex: Int,
        targetSlotIndex: Int,
        gridColumns: Int,
        preserveEmptySpaces: Boolean = true
    ): MoveWorkspaceAppResult = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val dockCount = preferences.dockCountFlow.first().coerceIn(3, 7)
            val drawerShuffleEnabled = preferences.drawerShuffleEnabledFlow.first()

            try {
                runInTransaction {
                    val allInstalled = installedAppDao.getAllAppsList()
                    val installed = allInstalled.filter { it.isInstalled }
                    val pages = workspaceDao.getPagesList().sortedBy { it.pageIndex }
                    val items = workspaceDao.getWorkspaceItemsList()
                    val foldersEntities = workspaceDao.getFoldersList()
                    val members = workspaceDao.getFolderMembersList()
                    val drawers = workspaceDao.getDrawerLayoutsList()
                    val focusList = workspaceDao.getFocusAppsList()

                    if (pages.isEmpty()) {
                        return@runInTransaction MoveWorkspaceAppResult(false, "Workspace has no home pages")
                    }
                    if (targetPageIndex !in pages.indices || pages.none { it.pageIndex == targetPageIndex }) {
                        return@runInTransaction MoveWorkspaceAppResult(false, "Target page ${targetPageIndex + 1} no longer exists")
                    }

                    val currentApps = projectAppItems(installed, pages, items, members, drawers, focusList)
                    val folders = projectFolders(foldersEntities, items, pages)
                    val widgets = projectWidgets(items, pages)

                    val plan = placementPlanner.planFolderMove(
                        FolderMoveRequest(
                            folderId = folderId,
                            targetPageIndex = targetPageIndex,
                            targetSlotIndex = targetSlotIndex,
                            gridColumns = gridColumns,
                            pageCount = pages.size,
                            preserveEmptySpaces = preserveEmptySpaces,
                            apps = currentApps,
                            folders = folders,
                            widgets = widgets
                        )
                    )

                    if (!plan.isValid || plan.movedFolder == null) {
                        return@runInTransaction MoveWorkspaceAppResult(false, plan.rejectionReason ?: "Invalid folder drop target")
                    }

                    val folderHomeItem = items.find {
                        it.containerType == WorkspaceContainers.HOME &&
                            it.itemType == WorkspaceItemTypes.FOLDER &&
                            it.folderId == folderId
                    } ?: return@runInTransaction MoveWorkspaceAppResult(false, "Folder placement missing")

                    val pageIndexToId = pages.associate { it.pageIndex to it.pageId }
                    val targetPageId = pageIndexToId[plan.movedFolder.pageIndex]
                        ?: throw IllegalStateException("Missing pageId for pageIndex ${plan.movedFolder.pageIndex}")

                    if (folderHomeItem.pageId == targetPageId &&
                        folderHomeItem.rank == plan.movedFolder.slotIndex &&
                        plan.changedHomePlacements.isEmpty()
                    ) {
                        return@runInTransaction MoveWorkspaceAppResult(true, movedCount = 0)
                    }

                    val updatedItems = mutableListOf<WorkspaceItemEntity>()
                    updatedItems.add(folderHomeItem.copy(pageId = targetPageId, rank = plan.movedFolder.slotIndex))

                    val homeItemByComponent = items
                        .filter { it.containerType == WorkspaceContainers.HOME && it.itemType == WorkspaceItemTypes.APP && it.packageName != null }
                        .associateBy { it.packageName!! to (it.activityName ?: "") }

                    for ((comp, newCoord) in plan.changedHomePlacements) {
                        val existingHome = homeItemByComponent[comp]
                            ?: throw IllegalStateException("Missing HOME item for ${comp.first}/${comp.second}")
                        val newPageId = pageIndexToId[newCoord.first]
                            ?: throw IllegalStateException("Missing pageId for pageIndex ${newCoord.first}")
                        updatedItems.add(existingHome.copy(pageId = newPageId, rank = newCoord.second))
                    }

                    workspaceDao.upsertWorkspaceItems(updatedItems)
                    validateNormalizedDbStateOrThrow(dockCount, drawerShuffleEnabled)
                    MoveWorkspaceAppResult(success = true, movedCount = updatedItems.size)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                MoveWorkspaceAppResult(false, t.message ?: "Failed to move folder")
            }
        }
    }

    suspend fun renameFolderResult(folderId: String, newName: String): FolderMutationResult = withContext(Dispatchers.IO) {
        val validatedName = normalizeAndValidateFolderName(newName).getOrElse { error ->
            return@withContext FolderMutationResult(false, folderId, error.message ?: "Invalid folder name")
        }
        mutationCoordinator.withMutationLock {
            val dockCount = preferences.dockCountFlow.first().coerceIn(3, 7)
            val drawerShuffleEnabled = preferences.drawerShuffleEnabledFlow.first()
            try {
                runInTransaction {
                    val folder = workspaceDao.getFoldersList().find { it.folderId == folderId }
                        ?: return@runInTransaction FolderMutationResult(false, folderId, "Folder no longer exists")
                    workspaceDao.upsertFolder(folder.copy(name = validatedName))
                    validateNormalizedDbStateOrThrow(dockCount, drawerShuffleEnabled)
                    FolderMutationResult(true, folderId)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                FolderMutationResult(false, folderId, t.message ?: "Failed to rename folder")
            }
        }
    }

    suspend fun renameFolder(folderId: String, newName: String) {
        renameFolderResult(folderId, newName)
    }

    suspend fun deleteFolderResult(folderId: String): FolderMutationResult = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val prefPageCount = preferences.pageCountFlow.first().coerceAtLeast(1)
            val dockCount = preferences.dockCountFlow.first().coerceIn(3, 7)
            val drawerShuffleEnabled = preferences.drawerShuffleEnabledFlow.first()
            try {
                runInTransaction {
                    val existingFolder = workspaceDao.getFoldersList().find { it.folderId == folderId }
                        ?: return@runInTransaction FolderMutationResult(false, folderId, "Folder no longer exists")
                    val rawPages = workspaceDao.getPagesList()
                    val pages = ensurePagesList(rawPages, prefPageCount)
                    if (rawPages.isEmpty()) workspaceDao.insertPages(pages)
                    val installedSet = installedAppDao.getAllAppsList()
                        .filter { it.isInstalled }
                        .map { it.packageName to it.activityName }
                        .toSet()
                    val items = workspaceDao.getWorkspaceItemsList()
                    val folderItem = items.find { it.itemType == WorkspaceItemTypes.FOLDER && it.folderId == existingFolder.folderId }
                    val targetPage = pages.find { it.pageId == folderItem?.pageId } ?: pages.first()
                    val members = workspaceDao.getFolderMembersList()
                        .filter { it.folderId == folderId && (it.packageName to it.activityName) in installedSet }
                        .sortedWith(compareBy<FolderMemberEntity> { it.rank }.thenBy { it.packageName }.thenBy { it.activityName })

                    val dockByComp = items
                        .filter { it.containerType == WorkspaceContainers.DOCK && it.packageName != null }
                        .associateBy { it.packageName!! to (it.activityName ?: "") }

                    val remainingItems = items.filter { !(it.itemType == WorkspaceItemTypes.FOLDER && it.folderId == folderId) }.toMutableList()
                    val unpackedItems = mutableListOf<WorkspaceItemEntity>()
                    for (member in members) {
                        remainingItems.removeAll {
                            it.containerType == WorkspaceContainers.HOME &&
                                it.packageName == member.packageName &&
                                it.activityName == member.activityName
                        }
                        val rank = nextFreeRankForPage(targetPage.pageId, folderItem?.rank ?: 0, remainingItems)
                        val dockItem = dockByComp[member.packageName to member.activityName]
                        val restored = WorkspaceItemEntity(
                            itemId = homeItemId(member.packageName, member.activityName),
                            itemType = WorkspaceItemTypes.APP,
                            containerType = WorkspaceContainers.HOME,
                            pageId = targetPage.pageId,
                            packageName = member.packageName,
                            activityName = member.activityName,
                            rank = rank,
                            isFixed = member.isFixed,
                            isShuffled = !member.isFixed && member.isShuffled,
                            isHidden = dockItem?.isHidden ?: false
                        )
                        unpackedItems.add(restored)
                        remainingItems.add(restored)
                    }

                    workspaceDao.deleteWorkspaceItemsForFolder(folderId)
                    workspaceDao.deleteFolderMembersByFolder(folderId)
                    workspaceDao.deleteFolder(folderId)
                    if (unpackedItems.isNotEmpty()) {
                        workspaceDao.upsertWorkspaceItems(unpackedItems)
                    }
                    validateNormalizedDbStateOrThrow(dockCount, drawerShuffleEnabled)
                    FolderMutationResult(true, folderId)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                FolderMutationResult(false, folderId, t.message ?: "Failed to delete folder")
            }
        }
    }

    suspend fun deleteFolder(folderId: String) {
        deleteFolderResult(folderId)
    }

    suspend fun addAppToFolderResult(
        packageName: String,
        activityName: String,
        folderId: String
    ): FolderMutationResult = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            addAppToFolderLocked(packageName, activityName, folderId)
        }
    }

    suspend fun addAppToFolder(packageName: String, activityName: String, folderId: String) {
        addAppToFolderResult(packageName, activityName, folderId)
    }

    private suspend fun addAppToFolderLocked(
        packageName: String,
        activityName: String,
        folderId: String
    ): FolderMutationResult {
        val prefPageCount = preferences.pageCountFlow.first().coerceAtLeast(1)
        val dockCount = preferences.dockCountFlow.first().coerceIn(3, 7)
        val drawerShuffleEnabled = preferences.drawerShuffleEnabledFlow.first()
        return try {
            runInTransaction {
                val folderExists = workspaceDao.getFoldersList().any { it.folderId == folderId }
                if (!folderExists) {
                    return@runInTransaction FolderMutationResult(false, folderId, "Target folder no longer exists")
                }

                val installed = installedAppDao.getAllAppsList().find {
                    it.isInstalled && it.packageName == packageName && it.activityName == activityName
                } ?: return@runInTransaction FolderMutationResult(false, folderId, "Application is not installed")

                val items = workspaceDao.getWorkspaceItemsList()
                val prevHomeItem = items.find {
                    it.containerType == WorkspaceContainers.HOME &&
                        it.packageName == installed.packageName &&
                        it.activityName == installed.activityName
                }
                val prevDockItem = items.find {
                    it.containerType == WorkspaceContainers.DOCK &&
                        it.packageName == installed.packageName &&
                        it.activityName == installed.activityName
                }
                val existingMembers = workspaceDao.getFolderMembersList()
                val prevMember = existingMembers.find { it.packageName == packageName && it.activityName == activityName }
                if (prevMember?.folderId == folderId && prevHomeItem == null) {
                    return@runInTransaction FolderMutationResult(true, folderId)
                }

                val destMembers = existingMembers
                    .filter { it.folderId == folderId }
                    .sortedWith(compareBy<FolderMemberEntity> { it.rank }.thenBy { it.packageName }.thenBy { it.activityName })
                val repackedDest = destMembers.mapIndexedNotNull { idx, m ->
                    if (m.rank != idx) m.copy(rank = idx) else null
                }

                val nextMemberRank = destMembers.size
                val isFixed = prevHomeItem?.isFixed ?: prevMember?.isFixed ?: prevDockItem?.isFixed ?: false
                val isShuffled = !isFixed && (prevHomeItem?.isShuffled ?: prevMember?.isShuffled ?: prevDockItem?.isShuffled ?: false)

                val member = FolderMemberEntity(
                    folderId = folderId,
                    packageName = packageName,
                    activityName = activityName,
                    rank = nextMemberRank,
                    isFixed = isFixed,
                    isShuffled = isShuffled
                )

                val oldFolderId = prevMember?.folderId?.takeIf { it != folderId }
                workspaceDao.deleteWorkspaceItemsForComponentInContainer(packageName, activityName, WorkspaceContainers.HOME)
                workspaceDao.deleteFolderMembersForComponent(packageName, activityName)
                if (repackedDest.isNotEmpty()) {
                    workspaceDao.upsertFolderMembers(repackedDest)
                }
                workspaceDao.upsertFolderMember(member)

                // Reconcile source folder if moved from another folder (repack >=2, dissolve at <=1)
                if (oldFolderId != null) {
                    reconcileSourceFoldersLocked(listOf(oldFolderId), prefPageCount)
                }

                validateNormalizedDbStateOrThrow(dockCount, drawerShuffleEnabled)
                FolderMutationResult(true, folderId)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            FolderMutationResult(false, folderId, t.message ?: "Failed to add application to folder")
        }
    }

    suspend fun addAppToFolder(packageName: String, folderId: String) = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val target = installedAppDao.getAllAppsList().find { it.packageName == packageName } ?: return@withMutationLock
            addAppToFolderLocked(target.packageName, target.activityName, folderId)
        }
    }

    /**
     * Persists a deterministic reordering of existing members inside [folderId] without altering
     * Home or Dock placements or allowing duplicate/missing members.
     */
    suspend fun reorderFolderMembers(
        folderId: String,
        orderedComponents: List<Pair<String, String>>
    ): FolderMutationResult = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val dockCount = preferences.dockCountFlow.first().coerceIn(3, 7)
            val drawerShuffleEnabled = preferences.drawerShuffleEnabledFlow.first()
            try {
                runInTransaction {
                    val folderExists = workspaceDao.getFoldersList().any { it.folderId == folderId }
                    if (!folderExists) {
                        return@runInTransaction FolderMutationResult(false, folderId, "Folder no longer exists")
                    }
                    if (orderedComponents.distinct().size != orderedComponents.size) {
                        return@runInTransaction FolderMutationResult(false, folderId, "Duplicate folder members are not allowed")
                    }

                    val existingMembers = workspaceDao.getFolderMembersList()
                        .filter { it.folderId == folderId }
                    val existingByComp = existingMembers.associateBy { it.packageName to it.activityName }
                    if (orderedComponents.size != existingByComp.size || orderedComponents.toSet() != existingByComp.keys) {
                        return@runInTransaction FolderMutationResult(false, folderId, "Folder member list does not match persisted members")
                    }

                    val reorderedEntities = orderedComponents.mapIndexed { idx, comp ->
                        existingByComp.getValue(comp).copy(rank = idx)
                    }
                    workspaceDao.upsertFolderMembers(reorderedEntities)
                    validateNormalizedDbStateOrThrow(dockCount, drawerShuffleEnabled)
                    FolderMutationResult(true, folderId)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                FolderMutationResult(false, folderId, t.message ?: "Failed to reorder folder apps")
            }
        }
    }

    /**
     * Removes [packageName]/[activityName] from its folder and places it safely on [targetPage]
     * without uninstalling the app or disturbing its Dock placement.
     *
     * Empty/single-member rule:
     * - If 0 members remain after removal, the empty folder is deleted.
     * - If 1 member remains after removal, the folder dissolves automatically and that last member
     *   inherits the folder's Home slot so invalid empty or single-app folders are not left behind.
     * - If 2+ members remain, their folder ranks are re-packed contiguously (0..N-1).
     */
    suspend fun removeAppFromFolderResult(
        packageName: String,
        activityName: String,
        targetPage: Int = 0
    ): FolderMutationResult = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val prefPageCount = preferences.pageCountFlow.first().coerceAtLeast(1)
            val dockCount = preferences.dockCountFlow.first().coerceIn(3, 7)
            val drawerShuffleEnabled = preferences.drawerShuffleEnabledFlow.first()
            try {
                runInTransaction {
                    val rawPages = workspaceDao.getPagesList()
                    val pages = ensurePagesList(rawPages, prefPageCount)
                    if (pages.isEmpty()) {
                        return@runInTransaction FolderMutationResult(false, null, "No home pages available")
                    }
                    if (rawPages.isEmpty()) workspaceDao.insertPages(pages)

                    val allMembers = workspaceDao.getFolderMembersList()
                    val member = allMembers.find { it.packageName == packageName && it.activityName == activityName }
                        ?: return@runInTransaction FolderMutationResult(false, null, "Application is not inside a folder")

                    val folderId = member.folderId
                    val safePageIndex = targetPage.coerceIn(0, pages.size - 1)
                    val page = pages.find { it.pageIndex == safePageIndex } ?: pages.first()
                    val items = workspaceDao.getWorkspaceItemsList()
                    val folderItem = items.find { it.itemType == WorkspaceItemTypes.FOLDER && it.folderId == folderId }

                    val existingDock = items.find {
                        it.containerType == WorkspaceContainers.DOCK &&
                            it.packageName == packageName &&
                            it.activityName == activityName
                    }

                    val remainingMembers = allMembers
                        .filter { it.folderId == folderId && !(it.packageName == packageName && it.activityName == activityName) }
                        .sortedBy { it.rank }

                    val workingHomeItems = items.filter {
                        !(it.containerType == WorkspaceContainers.HOME &&
                            it.packageName == packageName &&
                            it.activityName == activityName)
                    }.toMutableList()

                    workspaceDao.deleteFolderMembersForComponent(packageName, activityName)
                    workspaceDao.deleteWorkspaceItemsForComponentInContainer(
                        packageName,
                        activityName,
                        WorkspaceContainers.HOME
                    )

                    if (remainingMembers.size <= 1) {
                        // Dissolve folder on 0 or 1 remaining member
                        workingHomeItems.removeAll { it.itemType == WorkspaceItemTypes.FOLDER && it.folderId == folderId }
                        workspaceDao.deleteWorkspaceItemsForFolder(folderId)
                        workspaceDao.deleteFolderMembersByFolder(folderId)
                        workspaceDao.deleteFolder(folderId)

                        val lastMember = remainingMembers.firstOrNull()
                        if (lastMember != null) {
                            val lastDock = items.find {
                                it.containerType == WorkspaceContainers.DOCK &&
                                    it.packageName == lastMember.packageName &&
                                    it.activityName == lastMember.activityName
                            }
                            val lastPageId = folderItem?.pageId?.takeIf { pId -> pages.any { it.pageId == pId } } ?: page.pageId
                            val lastRank = folderItem?.rank?.takeIf { rank ->
                                workingHomeItems.none { it.containerType == WorkspaceContainers.HOME && it.pageId == lastPageId && it.rank == rank }
                            } ?: nextFreeRankForPage(lastPageId, 0, workingHomeItems)

                            val restoredLast = WorkspaceItemEntity(
                                itemId = homeItemId(lastMember.packageName, lastMember.activityName),
                                itemType = WorkspaceItemTypes.APP,
                                containerType = WorkspaceContainers.HOME,
                                pageId = lastPageId,
                                packageName = lastMember.packageName,
                                activityName = lastMember.activityName,
                                rank = lastRank,
                                isFixed = lastMember.isFixed,
                                isShuffled = !lastMember.isFixed && lastMember.isShuffled,
                                isHidden = lastDock?.isHidden ?: false
                            )
                            workingHomeItems.add(restoredLast)
                            workspaceDao.upsertWorkspaceItem(restoredLast)
                        }
                    } else {
                        val repacked = remainingMembers.mapIndexed { idx, m -> m.copy(rank = idx) }
                        workspaceDao.upsertFolderMembers(repacked)
                    }

                    val preferredRank = if (folderItem?.pageId == page.pageId) folderItem.rank else 0
                    val rank = nextFreeRankForPage(page.pageId, preferredRank, workingHomeItems)
                    val isFixed = member.isFixed
                    val isShuffled = !isFixed && member.isShuffled

                    val restoredHome = WorkspaceItemEntity(
                        itemId = homeItemId(packageName, activityName),
                        itemType = WorkspaceItemTypes.APP,
                        containerType = WorkspaceContainers.HOME,
                        pageId = page.pageId,
                        packageName = packageName,
                        activityName = activityName,
                        rank = rank,
                        isFixed = isFixed,
                        isShuffled = isShuffled,
                        isHidden = existingDock?.isHidden ?: false
                    )
                    workspaceDao.upsertWorkspaceItem(restoredHome)

                    validateNormalizedDbStateOrThrow(dockCount, drawerShuffleEnabled)
                    FolderMutationResult(true, folderId)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                FolderMutationResult(false, null, t.message ?: "Failed to remove application from folder")
            }
        }
    }

    suspend fun removeAppFromFolder(packageName: String, activityName: String, targetPage: Int = 0) {
        removeAppFromFolderResult(packageName, activityName, targetPage)
    }

    /**
     * Reconciles source folders after a member leaves (e.g., cross-folder move, Home placement):
     * - >= 2 installed members remaining: repacks ranks contiguously 0..N-1.
     * - 1 installed member remaining: dissolves folder and unpacks survivor onto HOME at the
     *   folder's former slot (or next free slot), preserving Dock and item flags.
     * - 0 installed members remaining: deletes empty folder and its HOME item.
     */
    private suspend fun reconcileSourceFoldersLocked(
        affectedFolderIds: Collection<String>,
        prefPageCount: Int
    ) {
        if (affectedFolderIds.isEmpty()) return
        val rawPages = workspaceDao.getPagesList()
        val pages = ensurePagesList(rawPages, prefPageCount)
        if (rawPages.isEmpty()) workspaceDao.insertPages(pages)

        val foldersById = workspaceDao.getFoldersList().associateBy { it.folderId }
        val installedSet = installedAppDao.getAllAppsList()
            .filter { it.isInstalled }
            .map { it.packageName to it.activityName }
            .toSet()
        val items = workspaceDao.getWorkspaceItemsList().toMutableList()
        val allMembers = workspaceDao.getFolderMembersList()

        for (srcFolderId in affectedFolderIds.distinct().sorted()) {
            if (srcFolderId !in foldersById) continue
            val folderItem = items.find { it.itemType == WorkspaceItemTypes.FOLDER && it.folderId == srcFolderId }
            val remainingMembers = allMembers
                .filter { it.folderId == srcFolderId && (it.packageName to it.activityName) in installedSet }
                .sortedWith(compareBy<FolderMemberEntity> { it.rank }.thenBy { it.packageName }.thenBy { it.activityName })

            if (remainingMembers.size >= 2) {
                val repacked = remainingMembers.mapIndexedNotNull { idx, m ->
                    if (m.rank != idx) m.copy(rank = idx) else null
                }
                if (repacked.isNotEmpty()) {
                    workspaceDao.upsertFolderMembers(repacked)
                }
            } else {
                items.removeAll { it.itemType == WorkspaceItemTypes.FOLDER && it.folderId == srcFolderId }
                workspaceDao.deleteWorkspaceItemsForFolder(srcFolderId)
                workspaceDao.deleteFolderMembersByFolder(srcFolderId)
                workspaceDao.deleteFolder(srcFolderId)

                val survivor = remainingMembers.singleOrNull()
                if (survivor != null) {
                    items.removeAll {
                        it.containerType == WorkspaceContainers.HOME &&
                            it.packageName == survivor.packageName &&
                            it.activityName == survivor.activityName
                    }
                    val dockItem = items.find {
                        it.containerType == WorkspaceContainers.DOCK &&
                            it.packageName == survivor.packageName &&
                            it.activityName == survivor.activityName
                    }
                    val targetPage = pages.find { it.pageId == folderItem?.pageId } ?: pages.first()
                    val rank = nextFreeRankForPage(targetPage.pageId, folderItem?.rank ?: 0, items)
                    val restoredHome = WorkspaceItemEntity(
                        itemId = homeItemId(survivor.packageName, survivor.activityName),
                        itemType = WorkspaceItemTypes.APP,
                        containerType = WorkspaceContainers.HOME,
                        pageId = targetPage.pageId,
                        packageName = survivor.packageName,
                        activityName = survivor.activityName,
                        rank = rank,
                        isFixed = survivor.isFixed,
                        isShuffled = !survivor.isFixed && survivor.isShuffled,
                        isHidden = dockItem?.isHidden ?: false
                    )
                    items.add(restoredHome)
                    workspaceDao.upsertWorkspaceItem(restoredHome)
                }
            }
        }
    }

    private suspend fun validateNormalizedDbStateOrThrow(dockCount: Int, drawerShuffleEnabled: Boolean) {
        val result = workspaceValidator.validateNormalizedWorkspace(
            installedApps = installedAppDao.getAllAppsList(),
            pages = workspaceDao.getPagesList(),
            workspaceItems = workspaceDao.getWorkspaceItemsList(),
            folders = workspaceDao.getFoldersList(),
            folderMembers = workspaceDao.getFolderMembersList(),
            drawerLayouts = workspaceDao.getDrawerLayoutsList(),
            focusApps = workspaceDao.getFocusAppsList(),
            dockCount = dockCount,
            drawerShuffleEnabled = drawerShuffleEnabled
        )
        if (!result.isValid) {
            throw IllegalStateException("Workspace invariant violation: ${result.errors.joinToString("; ")}")
        }
    }

    /**
     * Check if a scheduled shuffle is due. If so, triggers it.
     */
    suspend fun checkAndTriggerScheduledShuffle(): Boolean = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            val isEnabled = preferences.isShuffleEnabledFlow.first()
            if (!isEnabled) return@withMutationLock false

            val interval = preferences.shuffleIntervalFlow.first()
            if (interval == ShuffleInterval.MANUAL || interval.minutes <= 0) return@withMutationLock false

            val lastShuffle = preferences.lastShuffleTimeFlow.first()
            val now = System.currentTimeMillis()
            val intervalMillis = interval.minutes * 60 * 1000L

            if (now - lastShuffle >= intervalMillis) {
                return@withMutationLock performShuffleLocked(triggerType = "SCHEDULED")
            }
            false
        }
    }

    fun launchApp(app: AppItem): Boolean {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                mutationCoordinator.withMutationLock {
                    installedAppDao.updateLastUsedTimestamp(
                        packageName = app.packageName,
                        activityName = app.activityName,
                        timestamp = System.currentTimeMillis()
                    )
                }
            }
        }

        return try {
            val intent = if (app.activityName.isNotEmpty()) {
                Intent().apply {
                    component = ComponentName(app.packageName, app.activityName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                }
            } else {
                packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                }
            }

            if (intent != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun setHomeMode(mode: HomeMode) = preferences.setHomeMode(mode)
    suspend fun setShuffleInterval(interval: ShuffleInterval) = preferences.setShuffleInterval(interval)
    suspend fun setShuffleEnabled(enabled: Boolean) = preferences.setShuffleEnabled(enabled)
    suspend fun setThemeMode(mode: String) = preferences.setThemeMode(mode)
    suspend fun setGridColumns(cols: Int) = preferences.setGridColumns(cols)
    suspend fun setIconSize(size: String) = preferences.setIconSize(size)
    suspend fun setShowLabels(show: Boolean) = preferences.setShowLabels(show)
    suspend fun setPageCount(count: Int) = preferences.setPageCount(count)
    suspend fun setCrossPageShuffle(enabled: Boolean) = preferences.setCrossPageShuffle(enabled)
    suspend fun setDockCount(count: Int) = preferences.setDockCount(count)
    suspend fun setDockShowBackground(show: Boolean) = preferences.setDockShowBackground(show)
    suspend fun setDockShowLabels(show: Boolean) = preferences.setDockShowLabels(show)
    suspend fun setOnboardingCompleted(completed: Boolean) = preferences.setOnboardingCompleted(completed)
    suspend fun setHideDistractingInFocus(hide: Boolean) = preferences.setHideDistractingInFocus(hide)
    suspend fun setFocusSearchEnabled(enabled: Boolean) = preferences.setFocusSearchEnabled(enabled)
    suspend fun setGestureSwipeUp(gesture: String) = preferences.setGestureSwipeUp(gesture)
    suspend fun setGestureSwipeDown(gesture: String) = preferences.setGestureSwipeDown(gesture)
    suspend fun setGestureDoubleTap(gesture: String) = preferences.setGestureDoubleTap(gesture)
    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        mutationCoordinator.withMutationLock {
            runInTransaction {
                shuffleHistoryDao.clearHistory()
                shuffleHistoryDao.clearSnapshots()
            }
        }
    }

    // --- Projection & Serialization Helpers ---

    private fun ensurePagesList(pages: List<WorkspacePageEntity>, minCount: Int): List<WorkspacePageEntity> {
        val sorted = pages.sortedBy { it.pageIndex }.toMutableList()
        if (sorted.isEmpty()) {
            val now = System.currentTimeMillis()
            for (idx in 0 until minCount.coerceAtLeast(1)) {
                sorted.add(
                    WorkspacePageEntity(
                        pageId = "page_$idx",
                        pageIndex = idx,
                        isDefault = idx == 0,
                        createdAt = now
                    )
                )
            }
        }
        return sorted
    }

    private fun nextFreeRankForPage(
        pageId: String,
        preferredRank: Int,
        items: List<WorkspaceItemEntity>,
        span: Int = 1
    ): Int {
        val occupied = mutableSetOf<Int>()
        for (item in items) {
            if (item.containerType == WorkspaceContainers.HOME && item.pageId == pageId) {
                val itemSpan = (item.columnSpan.coerceAtLeast(1) * item.rowSpan.coerceAtLeast(1))
                for (offset in 0 until itemSpan) occupied.add(item.rank + offset)
            }
        }
        val neededSpan = span.coerceAtLeast(1)
        fun isRangeFree(start: Int): Boolean =
            (0 until neededSpan).all { offset -> (start + offset) !in occupied }

        var candidate = preferredRank.coerceAtLeast(0)
        if (isRangeFree(candidate)) return candidate
        candidate = 0
        while (!isRangeFree(candidate)) candidate++
        return candidate
    }

    private fun serializeArchivedPlacement(
        activeItems: List<WorkspaceItemEntity>,
        activeMember: FolderMemberEntity?,
        pageIdToIndex: Map<String, Int>,
        folderHomeItem: WorkspaceItemEntity? = null
    ): String {
        val root = JSONObject()
        val itemsArr = JSONArray()
        for (item in activeItems) {
            itemsArr.put(
                JSONObject().apply {
                    put("containerType", item.containerType)
                    put("pageId", item.pageId ?: JSONObject.NULL)
                    put("pageIndex", item.pageId?.let { pageIdToIndex[it] } ?: 0)
                    put("rank", item.rank)
                    put("isFixed", item.isFixed)
                    put("isShuffled", item.isShuffled)
                    put("isHidden", item.isHidden)
                }
            )
        }
        root.put("items", itemsArr)
        if (activeMember != null) {
            root.put(
                "folderMember",
                JSONObject().apply {
                    put("folderId", activeMember.folderId)
                    put("rank", activeMember.rank)
                    put("isFixed", activeMember.isFixed)
                    put("isShuffled", activeMember.isShuffled)
                    if (folderHomeItem != null) {
                        put("fallbackPageId", folderHomeItem.pageId ?: JSONObject.NULL)
                        put("fallbackPageIndex", folderHomeItem.pageId?.let { pageIdToIndex[it] } ?: 0)
                        put("fallbackRank", folderHomeItem.rank)
                    }
                }
            )
        }
        return root.toString()
    }

    private fun restoreArchivedPlacement(
        archivedJson: String,
        pkg: String,
        act: String,
        pages: List<WorkspacePageEntity>,
        validFolderIds: Set<String>,
        existingItems: List<WorkspaceItemEntity>,
        existingMembers: List<FolderMemberEntity>,
        currentDockSize: Int,
        maxDockCount: Int,
        allocateHomeRank: (String, Int) -> Int,
        allocateDockRank: (Int) -> Int,
        onRestoreItem: (WorkspaceItemEntity) -> Unit,
        onRestoreMember: (FolderMemberEntity) -> Unit
    ): Boolean {
        return runCatching {
            val root = JSONObject(archivedJson)
            var restoredToFolder = false
            var fallbackFolderHomeObj: JSONObject? = null

            if (root.has("folderMember") && !root.isNull("folderMember")) {
                val fm = root.getJSONObject("folderMember")
                val fId = fm.getString("folderId")
                if (fId in validFolderIds) {
                    val alreadyMember = existingMembers.any {
                        it.packageName == pkg && it.activityName == act
                    }
                    if (!alreadyMember) {
                        val existingRanks = existingMembers.filter { it.folderId == fId }.map { it.rank }.toSet()
                        var rank = fm.optInt("rank", existingRanks.size).coerceIn(0, existingRanks.size)
                        while (rank in existingRanks) rank++
                        val memberFixed = fm.optBoolean("isFixed", false)
                        onRestoreMember(
                            FolderMemberEntity(
                                folderId = fId,
                                packageName = pkg,
                                activityName = act,
                                rank = rank,
                                isFixed = memberFixed,
                                isShuffled = !memberFixed && fm.optBoolean("isShuffled", false)
                            )
                        )
                    }
                    restoredToFolder = true
                } else {
                    fallbackFolderHomeObj = fm
                }
            }

            val itemsArr = root.optJSONArray("items") ?: JSONArray()
            val pagesById = pages.associateBy { it.pageId }
            val pagesByIndex = pages.associateBy { it.pageIndex }
            var localDockSize = currentDockSize
            var restoredHome = false
            var restoredDockHidden = false

            for (i in 0 until itemsArr.length()) {
                val obj = itemsArr.getJSONObject(i)
                val container = obj.optString("containerType", WorkspaceContainers.HOME)
                val isFixed = obj.optBoolean("isFixed", false)
                val isShuffled = obj.optBoolean("isShuffled", false) && !isFixed
                val isHidden = obj.optBoolean("isHidden", false)

                if (container == WorkspaceContainers.DOCK) {
                    val alreadyDock = existingItems.any {
                        it.containerType == WorkspaceContainers.DOCK &&
                            it.packageName == pkg &&
                            it.activityName == act
                    }
                    if (!alreadyDock && localDockSize < maxDockCount) {
                        onRestoreItem(
                            WorkspaceItemEntity(
                                itemId = dockItemId(pkg, act),
                                itemType = WorkspaceItemTypes.APP,
                                containerType = WorkspaceContainers.DOCK,
                                pageId = null,
                                packageName = pkg,
                                activityName = act,
                                rank = allocateDockRank(obj.optInt("rank", localDockSize)),
                                isFixed = isFixed,
                                isShuffled = isShuffled,
                                isHidden = isHidden
                            )
                        )
                        localDockSize++
                        restoredDockHidden = isHidden
                    }
                } else if (container == WorkspaceContainers.HOME && !restoredToFolder && !restoredHome) {
                    val alreadyHome = existingItems.any {
                        it.containerType == WorkspaceContainers.HOME &&
                            it.packageName == pkg &&
                            it.activityName == act
                    }
                    if (!alreadyHome) {
                        val savedPageId = obj.optString("pageId", "").takeIf { it.isNotBlank() && it in pagesById }
                        val fallbackIndex = obj.optInt("pageIndex", 0).coerceIn(0, pages.size - 1)
                        val targetPageId = savedPageId ?: (pagesByIndex[fallbackIndex]?.pageId ?: pages.first().pageId)
                        onRestoreItem(
                            WorkspaceItemEntity(
                                itemId = homeItemId(pkg, act),
                                itemType = WorkspaceItemTypes.APP,
                                containerType = WorkspaceContainers.HOME,
                                pageId = targetPageId,
                                packageName = pkg,
                                activityName = act,
                                rank = allocateHomeRank(targetPageId, obj.optInt("rank", 0)),
                                isFixed = isFixed,
                                isShuffled = isShuffled,
                                isHidden = isHidden
                            )
                        )
                        restoredHome = true
                    }
                }
            }

            // Original folder no longer exists: restore former folder member safely to HOME
            if (!restoredToFolder && !restoredHome && fallbackFolderHomeObj != null) {
                val alreadyHome = existingItems.any {
                    it.containerType == WorkspaceContainers.HOME &&
                        it.packageName == pkg &&
                        it.activityName == act
                }
                if (!alreadyHome) {
                    val savedPageId = fallbackFolderHomeObj.optString("fallbackPageId", "")
                        .takeIf { it.isNotBlank() && it in pagesById }
                    val fallbackIndex = fallbackFolderHomeObj.optInt("fallbackPageIndex", 0).coerceIn(0, pages.size - 1)
                    val targetPageId = savedPageId ?: (pagesByIndex[fallbackIndex]?.pageId ?: pages.first().pageId)
                    val preferredRank = fallbackFolderHomeObj.optInt("fallbackRank", 0).coerceAtLeast(0)
                    val isFixed = fallbackFolderHomeObj.optBoolean("isFixed", false)
                    val isShuffled = !isFixed && fallbackFolderHomeObj.optBoolean("isShuffled", false)
                    onRestoreItem(
                        WorkspaceItemEntity(
                            itemId = homeItemId(pkg, act),
                            itemType = WorkspaceItemTypes.APP,
                            containerType = WorkspaceContainers.HOME,
                            pageId = targetPageId,
                            packageName = pkg,
                            activityName = act,
                            rank = allocateHomeRank(targetPageId, preferredRank),
                            isFixed = isFixed,
                            isShuffled = isShuffled,
                            isHidden = restoredDockHidden
                        )
                    )
                }
            }
            true
        }.getOrDefault(false)
    }

    companion object {
        const val MAX_FOLDER_NAME_LENGTH = 32
        const val DEFAULT_FOLDER_NAME = "Folder"

        fun normalizeAndValidateFolderName(rawName: String): Result<String> {
            val trimmed = rawName.trim()
            if (trimmed.isEmpty()) {
                return Result.failure(IllegalArgumentException("Folder name cannot be empty"))
            }
            if (trimmed.length > MAX_FOLDER_NAME_LENGTH) {
                return Result.failure(
                    IllegalArgumentException("Folder name cannot exceed $MAX_FOLDER_NAME_LENGTH characters")
                )
            }
            return Result.success(trimmed)
        }

        fun normalizeFolderNameOrDefault(rawName: String): String {
            val trimmed = rawName.trim()
            return if (trimmed.isEmpty()) DEFAULT_FOLDER_NAME else trimmed.take(MAX_FOLDER_NAME_LENGTH)
        }

        fun homeItemId(packageName: String, activityName: String): String =
            "home_${packageName}_${activityName}"

        fun dockItemId(packageName: String, activityName: String): String =
            "dock_${packageName}_${activityName}"

        fun folderItemId(folderId: String): String =
            "folder_$folderId"

        fun projectAppItems(
            installed: List<InstalledAppEntity>,
            pages: List<WorkspacePageEntity>,
            items: List<WorkspaceItemEntity>,
            members: List<FolderMemberEntity>,
            drawers: List<DrawerLayoutEntity>,
            focusList: List<FocusAppEntity>
        ): List<AppItem> {
            val pageIdToIndex = pages.associate { it.pageId to it.pageIndex }
            val homeByComp = items
                .filter { it.containerType == WorkspaceContainers.HOME && it.itemType == WorkspaceItemTypes.APP && it.packageName != null }
                .associateBy { it.packageName!! to (it.activityName ?: "") }
            val dockByComp = items
                .filter { it.containerType == WorkspaceContainers.DOCK && it.itemType == WorkspaceItemTypes.APP && it.packageName != null }
                .associateBy { it.packageName!! to (it.activityName ?: "") }
            val memberByComp = members.associateBy { it.packageName to it.activityName }
            val drawerByComp = drawers.associateBy { it.packageName to it.activityName }
            val focusByComp = focusList.associateBy { it.packageName to it.activityName }

            return installed.filter { it.isInstalled }.map { app ->
                val comp = app.packageName to app.activityName
                val homeItem = homeByComp[comp]
                val dockItem = dockByComp[comp]
                val member = memberByComp[comp]
                val drawer = drawerByComp[comp]
                val focus = focusByComp[comp]

                val isPinned = dockItem != null
                val folderId = if (homeItem == null) member?.folderId else null
                val isOnHome = homeItem != null
                val pageIdx = if (isOnHome) (homeItem.pageId?.let { pageIdToIndex[it] } ?: 0) else 0
                val slotIdx = if (isOnHome) homeItem.rank else -1

                val isFixed = homeItem?.isFixed ?: member?.isFixed ?: dockItem?.isFixed ?: drawer?.isFixed ?: false
                val isShuffled = !isFixed && (homeItem?.isShuffled ?: member?.isShuffled ?: dockItem?.isShuffled ?: false)

                AppItem(
                    packageName = app.packageName,
                    activityName = app.activityName,
                    label = app.label,
                    isShuffled = isShuffled,
                    isFixed = isFixed,
                    slotIndex = slotIdx,
                    pageIndex = pageIdx,
                    folderId = folderId,
                    isPinnedToDock = isPinned,
                    isOnHomeScreen = isOnHome,
                    isVisibleInFocus = focus?.isVisibleInFocus ?: false,
                    isEssentialInFocus = focus?.isEssentialInFocus ?: false,
                    isHidden = homeItem?.isHidden ?: dockItem?.isHidden ?: drawer?.isHidden ?: false,
                    isSystemApp = app.isSystemApp,
                    customCategory = app.customCategory,
                    lastUsedTimestamp = app.lastUsedTimestamp,
                    drawerSlotIndex = drawer?.drawerRank ?: -1,
                    isDrawerShuffled = drawer?.isDrawerShuffled ?: false,
                    dockRank = dockItem?.rank ?: -1,
                    homeItemId = homeItem?.itemId,
                    dockItemId = dockItem?.itemId,
                    isInstalled = app.isInstalled,
                    folderRank = if (folderId != null) (member?.rank ?: -1) else -1
                )
            }
        }

        fun projectFolders(
            folders: List<FolderEntity>,
            items: List<WorkspaceItemEntity>,
            pages: List<WorkspacePageEntity>
        ): List<FolderItem> {
            val pageIdToIndex = pages.associate { it.pageId to it.pageIndex }
            val folderItemsByFolderId = items
                .filter { it.itemType == WorkspaceItemTypes.FOLDER && it.folderId != null }
                .associateBy { it.folderId!! }

            return folders.map { folder ->
                val placement = folderItemsByFolderId[folder.folderId]
                val pageIdx = placement?.pageId?.let { pageIdToIndex[it] } ?: 0
                FolderItem(
                    id = folder.folderId,
                    name = folder.name,
                    pageIndex = pageIdx,
                    slotIndex = placement?.rank ?: 0,
                    createdAt = folder.createdAt
                )
            }.sortedWith(compareBy({ it.pageIndex }, { it.slotIndex }, { it.createdAt }))
        }

        fun projectWidgets(
            items: List<WorkspaceItemEntity>,
            pages: List<WorkspacePageEntity>
        ): List<WidgetPlacement> {
            val pageIdToIndex = pages.associate { it.pageId to it.pageIndex }
            return items
                .filter { it.itemType == WorkspaceItemTypes.WIDGET && it.appWidgetId != null }
                .map { item ->
                    WidgetPlacement(
                        appWidgetId = item.appWidgetId!!,
                        pageIndex = item.pageId?.let { pageIdToIndex[it] } ?: 0,
                        slotIndex = item.rank,
                        columnSpan = item.columnSpan.coerceAtLeast(1),
                        rowSpan = item.rowSpan.coerceAtLeast(1)
                    )
                }
        }
    }
}

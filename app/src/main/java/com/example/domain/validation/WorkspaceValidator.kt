package com.example.domain.validation

import com.example.data.database.DrawerLayoutEntity
import com.example.data.database.FocusAppEntity
import com.example.data.database.FolderEntity
import com.example.data.database.FolderMemberEntity
import com.example.data.database.InstalledAppEntity
import com.example.data.database.WorkspaceContainers
import com.example.data.database.WorkspaceItemEntity
import com.example.data.database.WorkspaceItemTypes
import com.example.data.database.WorkspacePageEntity
import com.example.domain.model.AppItem
import com.example.domain.model.FolderItem

/**
 * Lightweight domain representation of a widget's home-screen placement
 * used for occupancy and collision validation.
 */
data class WidgetPlacement(
    val appWidgetId: Int,
    val pageIndex: Int,
    val slotIndex: Int,
    val columnSpan: Int = 1,
    val rowSpan: Int = 1
) {
    fun occupiedCoords(): List<Pair<Int, Int>> {
        val spanCount = (columnSpan.coerceAtLeast(1) * rowSpan.coerceAtLeast(1))
        return (0 until spanCount).map { offset -> pageIndex to (slotIndex + offset) }
    }
}

data class WorkspaceValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList()
)

/**
 * Centralized invariant validator for Shellfri launcher workspace state and transitions.
 * Canonical app identity is (packageName, activityName), allowing multiple launcher
 * activities within the same package independently.
 */
class WorkspaceValidator {

    fun validateWorkspace(
        apps: List<AppItem>,
        folders: List<FolderItem> = emptyList(),
        widgets: List<WidgetPlacement> = emptyList(),
        pageCount: Int = 1,
        dockCount: Int = 5,
        drawerShuffleEnabled: Boolean = false
    ): WorkspaceValidationResult {
        val errors = mutableListOf<String>()

        if (pageCount < 1) {
            errors.add("pageCount must be >= 1, got $pageCount")
        }
        if (dockCount < 0) {
            errors.add("dockCount must be >= 0, got $dockCount")
        }

        // 1. Unique application component identities: (packageName, activityName)
        val seenComponents = mutableSetOf<Pair<String, String>>()
        for (app in apps) {
            if (app.packageName.isBlank()) {
                errors.add("App entry has blank packageName")
                continue
            }
            val component = app.packageName to app.activityName
            if (!seenComponents.add(component)) {
                errors.add("Duplicate component identity: ${app.packageName}/${app.activityName}")
            }
            if (app.isFixed && app.isShuffled) {
                errors.add("App ${app.componentKey} cannot be both fixed and shuffled")
            }
            if (!app.isInstalled && (app.isOnHomeScreen || app.isPinnedToDock || app.folderId != null)) {
                errors.add("Uninstalled app ${app.componentKey} cannot occupy active workspace placement")
            }
        }

        // 2. Folder invariants & placement
        val folderIds = mutableSetOf<String>()
        val occupiedByFolders = mutableSetOf<Pair<Int, Int>>()
        for (folder in folders) {
            if (folder.id.isBlank()) {
                errors.add("Folder has blank id")
                continue
            }
            if (!folderIds.add(folder.id)) {
                errors.add("Duplicate folder id: ${folder.id}")
            }
            if (folder.pageIndex < 0 || (pageCount > 0 && folder.pageIndex >= pageCount)) {
                errors.add("Folder ${folder.id} has out-of-bounds pageIndex ${folder.pageIndex} (pageCount=$pageCount)")
            }
            if (folder.slotIndex < 0) {
                errors.add("Folder ${folder.id} has negative slotIndex ${folder.slotIndex}")
            }
            val coord = folder.pageIndex to folder.slotIndex
            if (!occupiedByFolders.add(coord)) {
                errors.add("Duplicate folder occupancy at $coord (folder=${folder.id})")
            }
        }

        // 3. Widget invariants & occupancy
        val seenWidgetIds = mutableSetOf<Int>()
        val occupiedByWidgets = mutableSetOf<Pair<Int, Int>>()
        for (widget in widgets) {
            if (widget.appWidgetId <= 0) {
                errors.add("Widget has invalid appWidgetId ${widget.appWidgetId}")
            } else if (!seenWidgetIds.add(widget.appWidgetId)) {
                errors.add("Duplicate appWidgetId ${widget.appWidgetId}")
            }
            if (widget.columnSpan < 1 || widget.rowSpan < 1) {
                errors.add("Widget ${widget.appWidgetId} has invalid span ${widget.columnSpan}x${widget.rowSpan}")
            }
            if (widget.pageIndex < 0 || (pageCount > 0 && widget.pageIndex >= pageCount)) {
                errors.add("Widget ${widget.appWidgetId} has out-of-bounds pageIndex ${widget.pageIndex}")
            }
            if (widget.slotIndex < 0) {
                errors.add("Widget ${widget.appWidgetId} has negative slotIndex ${widget.slotIndex}")
            }
            for (coord in widget.occupiedCoords()) {
                if (!occupiedByWidgets.add(coord)) {
                    errors.add("Widget ${widget.appWidgetId} overlaps another widget at $coord")
                }
                if (coord in occupiedByFolders) {
                    errors.add("Widget ${widget.appWidgetId} collides with folder at $coord")
                }
            }
        }

        // 4. Dock, folder membership, home grid placement, and drawer invariants
        val dockApps = apps.filter { it.isPinnedToDock }
        if (dockApps.size > dockCount) {
            errors.add("Dock app count ${dockApps.size} exceeds dockCount $dockCount")
        }
        val occupiedDockRanks = mutableSetOf<Int>()
        for (dockApp in dockApps) {
            if (dockApp.dockRank < -1) {
                errors.add("Invalid dockRank ${dockApp.dockRank} for ${dockApp.componentKey}")
            } else if (dockApp.dockRank >= 0 && !occupiedDockRanks.add(dockApp.dockRank)) {
                errors.add("Duplicate dockRank ${dockApp.dockRank} at ${dockApp.componentKey}")
            }
        }

        val occupiedByApps = mutableSetOf<Pair<Int, Int>>()
        val occupiedDrawerSlots = mutableSetOf<Int>()

        for (app in apps) {
            val fId = app.folderId
            if (fId != null) {
                if (fId !in folderIds) {
                    errors.add("App ${app.componentKey} references missing folderId $fId")
                }
                if (app.isOnHomeScreen) {
                    errors.add("App ${app.componentKey} cannot be directly on home screen while inside folder $fId")
                }
            }

            if (app.isOnHomeScreen && fId == null) {
                if (app.pageIndex < 0 || (pageCount > 0 && app.pageIndex >= pageCount)) {
                    errors.add("Invalid pageIndex ${app.pageIndex} for ${app.componentKey}")
                }
                if (app.slotIndex < 0) {
                    errors.add("Negative slotIndex ${app.slotIndex} for ${app.componentKey}")
                }
                val coord = app.pageIndex to app.slotIndex
                if (!occupiedByApps.add(coord)) {
                    errors.add("Duplicate occupied home slot $coord at ${app.componentKey}")
                }
                if (coord in occupiedByFolders) {
                    errors.add("App ${app.componentKey} collided with folder at $coord")
                }
                if (coord in occupiedByWidgets) {
                    errors.add("App ${app.componentKey} collided with widget at $coord")
                }
            }

            if (app.drawerSlotIndex < -1) {
                errors.add("Invalid drawerSlotIndex ${app.drawerSlotIndex} for ${app.componentKey}")
            }
            if (drawerShuffleEnabled && app.isDrawerShuffled && !app.isFixed && app.drawerSlotIndex >= 0) {
                if (!occupiedDrawerSlots.add(app.drawerSlotIndex)) {
                    errors.add("Duplicate drawerSlotIndex ${app.drawerSlotIndex} at ${app.componentKey}")
                }
            }
        }

        return WorkspaceValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }

    /**
     * Validates normalized Phase B Room entities directly for referential integrity,
     * component uniqueness, page existence, rank uniqueness, widget spans, dock bounds,
     * folder membership consistency, uninstalled-app absence from active placements, and
     * drawer independence.
     */
    fun validateNormalizedWorkspace(
        installedApps: List<InstalledAppEntity>,
        pages: List<WorkspacePageEntity>,
        workspaceItems: List<WorkspaceItemEntity>,
        folders: List<FolderEntity>,
        folderMembers: List<FolderMemberEntity>,
        drawerLayouts: List<DrawerLayoutEntity>,
        focusApps: List<FocusAppEntity> = emptyList(),
        dockCount: Int = 5,
        drawerShuffleEnabled: Boolean = false
    ): WorkspaceValidationResult {
        val errors = mutableListOf<String>()

        if (pages.isEmpty()) {
            errors.add("Workspace must contain at least one page")
        }
        if (dockCount < 0) {
            errors.add("dockCount must be >= 0, got $dockCount")
        }

        // Pages uniqueness and contiguity
        val pageById = mutableMapOf<String, WorkspacePageEntity>()
        val seenPageIndices = mutableSetOf<Int>()
        for (page in pages) {
            if (page.pageId.isBlank()) {
                errors.add("Workspace page has blank pageId")
                continue
            }
            if (pageById.put(page.pageId, page) != null) {
                errors.add("Duplicate pageId: ${page.pageId}")
            }
            if (page.pageIndex < 0) {
                errors.add("Negative pageIndex ${page.pageIndex} for ${page.pageId}")
            } else if (!seenPageIndices.add(page.pageIndex)) {
                errors.add("Duplicate pageIndex ${page.pageIndex}")
            }
        }

        // Installed apps component uniqueness
        val installedByComponent = mutableMapOf<Pair<String, String>, InstalledAppEntity>()
        for (app in installedApps) {
            if (app.packageName.isBlank()) {
                errors.add("InstalledApp has blank packageName")
                continue
            }
            val key = app.packageName to app.activityName
            if (installedByComponent.put(key, app) != null) {
                errors.add("Duplicate component identity in installed_apps: ${app.packageName}/${app.activityName}")
            }
        }

        // Folders uniqueness
        val folderIds = mutableSetOf<String>()
        for (folder in folders) {
            if (folder.folderId.isBlank()) {
                errors.add("Folder has blank folderId")
                continue
            }
            if (!folderIds.add(folder.folderId)) {
                errors.add("Duplicate folderId: ${folder.folderId}")
            }
        }

        // Workspace items: page references, rank uniqueness, spans, dock rules, installed checks
        val seenItemIds = mutableSetOf<String>()
        val occupiedHomeCells = mutableSetOf<Pair<String, Int>>() // (pageId, cellRank)
        val occupiedDockRanks = mutableSetOf<Int>()
        val activeHomeAppComponents = mutableSetOf<Pair<String, String>>()
        val activeDockAppComponents = mutableSetOf<Pair<String, String>>()
        val placedFolderIds = mutableSetOf<String>()
        val seenWidgetIds = mutableSetOf<Int>()
        var dockItemCount = 0

        for (item in workspaceItems) {
            if (item.itemId.isBlank()) {
                errors.add("WorkspaceItem has blank itemId")
                continue
            }
            if (!seenItemIds.add(item.itemId)) {
                errors.add("Duplicate itemId: ${item.itemId}")
            }
            if (item.rank < 0) {
                errors.add("WorkspaceItem ${item.itemId} has negative rank ${item.rank}")
            }
            if (item.isFixed && item.isShuffled) {
                errors.add("WorkspaceItem ${item.itemId} cannot be both fixed and shuffled")
            }

            when (item.containerType) {
                WorkspaceContainers.HOME -> {
                    val pId = item.pageId
                    if (pId == null || pId !in pageById) {
                        errors.add("HOME item ${item.itemId} references missing pageId=$pId")
                    } else {
                        val spanCells = (item.columnSpan.coerceAtLeast(1) * item.rowSpan.coerceAtLeast(1))
                        if (item.columnSpan < 1 || item.rowSpan < 1) {
                            errors.add("WorkspaceItem ${item.itemId} has invalid span ${item.columnSpan}x${item.rowSpan}")
                        }
                        for (offset in 0 until spanCells) {
                            val cellCoord = pId to (item.rank + offset)
                            if (!occupiedHomeCells.add(cellCoord)) {
                                errors.add("Collision on HOME page $pId at rank ${item.rank + offset} (item=${item.itemId})")
                            }
                        }
                    }
                }

                WorkspaceContainers.DOCK -> {
                    dockItemCount++
                    if (item.pageId != null) {
                        errors.add("DOCK item ${item.itemId} must not reference pageId=${item.pageId}")
                    }
                    if (!occupiedDockRanks.add(item.rank)) {
                        errors.add("Duplicate DOCK rank ${item.rank} (item=${item.itemId})")
                    }
                }

                else -> errors.add("Unknown containerType ${item.containerType} on ${item.itemId}")
            }

            when (item.itemType) {
                WorkspaceItemTypes.APP -> {
                    val pkg = item.packageName
                    val act = item.activityName
                    if (pkg.isNullOrBlank() || act == null) {
                        errors.add("APP item ${item.itemId} missing component identity")
                    } else {
                        val comp = pkg to act
                        val installed = installedByComponent[comp]
                        if (installed == null || !installed.isInstalled) {
                            errors.add("APP item ${item.itemId} references uninstalled or missing component $pkg/$act")
                        }
                        if (item.containerType == WorkspaceContainers.HOME && !activeHomeAppComponents.add(comp)) {
                            errors.add("Duplicate HOME placement for component $pkg/$act")
                        }
                        if (item.containerType == WorkspaceContainers.DOCK && !activeDockAppComponents.add(comp)) {
                            errors.add("Duplicate DOCK placement for component $pkg/$act")
                        }
                    }
                }

                WorkspaceItemTypes.FOLDER -> {
                    val fId = item.folderId
                    if (fId.isNullOrBlank() || fId !in folderIds) {
                        errors.add("FOLDER item ${item.itemId} references missing folderId=$fId")
                    } else if (!placedFolderIds.add(fId)) {
                        errors.add("Duplicate workspace placement for folder $fId")
                    }
                }

                WorkspaceItemTypes.WIDGET -> {
                    val widgetId = item.appWidgetId
                    if (widgetId == null || widgetId <= 0) {
                        errors.add("WIDGET item ${item.itemId} has invalid appWidgetId=$widgetId")
                    } else if (!seenWidgetIds.add(widgetId)) {
                        errors.add("Duplicate appWidgetId $widgetId")
                    }
                    if (item.columnSpan < 1 || item.rowSpan < 1) {
                        errors.add("WIDGET item ${item.itemId} has invalid span ${item.columnSpan}x${item.rowSpan}")
                    }
                }

                else -> errors.add("Unknown itemType ${item.itemType} on ${item.itemId}")
            }
        }

        if (dockItemCount > dockCount) {
            errors.add("DOCK item count $dockItemCount exceeds dockCount $dockCount")
        }

        // Folder members integrity
        val seenMemberKeys = mutableSetOf<Triple<String, String, String>>()
        val memberRanksByFolder = mutableMapOf<String, MutableSet<Int>>()
        for (member in folderMembers) {
            if (member.folderId !in folderIds) {
                errors.add("FolderMember references missing folderId=${member.folderId}")
            }
            val key = Triple(member.folderId, member.packageName, member.activityName)
            if (!seenMemberKeys.add(key)) {
                errors.add("Duplicate FolderMember $key")
            }
            val comp = member.packageName to member.activityName
            val installed = installedByComponent[comp]
            if (installed == null || !installed.isInstalled) {
                errors.add("FolderMember references uninstalled or missing component ${member.packageName}/${member.activityName}")
            }
            if (comp in activeHomeAppComponents) {
                errors.add("Component ${member.packageName}/${member.activityName} cannot be in folder ${member.folderId} while placed directly on HOME")
            }
            if (member.rank < 0) {
                errors.add("FolderMember ${member.packageName}/${member.activityName} has negative rank ${member.rank}")
            } else {
                val ranks = memberRanksByFolder.getOrPut(member.folderId) { mutableSetOf() }
                if (!ranks.add(member.rank)) {
                    errors.add("Duplicate rank ${member.rank} in folder ${member.folderId}")
                }
            }
        }

        // Drawer layout integrity (independent of home placement)
        val seenDrawerComponents = mutableSetOf<Pair<String, String>>()
        val occupiedDrawerRanks = mutableSetOf<Int>()
        for (drawer in drawerLayouts) {
            val comp = drawer.packageName to drawer.activityName
            if (!seenDrawerComponents.add(comp)) {
                errors.add("Duplicate drawer_layout entry for ${drawer.packageName}/${drawer.activityName}")
            }
            if (drawer.drawerRank < -1) {
                errors.add("Invalid drawerRank ${drawer.drawerRank} for ${drawer.packageName}/${drawer.activityName}")
            }
            if (drawerShuffleEnabled && drawer.isDrawerShuffled && !drawer.isFixed && drawer.drawerRank >= 0) {
                if (!occupiedDrawerRanks.add(drawer.drawerRank)) {
                    errors.add("Duplicate drawerRank ${drawer.drawerRank} for ${drawer.packageName}/${drawer.activityName}")
                }
            }
        }

        // Focus apps uniqueness
        val seenFocusComponents = mutableSetOf<Pair<String, String>>()
        for (focus in focusApps) {
            val comp = focus.packageName to focus.activityName
            if (!seenFocusComponents.add(comp)) {
                errors.add("Duplicate focus_apps entry for ${focus.packageName}/${focus.activityName}")
            }
        }

        return WorkspaceValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }

    fun validateLayoutTransition(
        original: List<AppItem>,
        candidate: List<AppItem>,
        folders: List<FolderItem> = emptyList(),
        widgets: List<WidgetPlacement> = emptyList(),
        pageCount: Int = 1,
        crossPageAllowed: Boolean = true,
        drawerShuffleEnabled: Boolean = false
    ): WorkspaceValidationResult {
        val errors = mutableListOf<String>()

        if (original.size != candidate.size) {
            errors.add("App count mismatch: expected ${original.size}, got ${candidate.size}")
            return WorkspaceValidationResult(isValid = false, errors = errors)
        }

        val origMap = original.associateBy { identityKey(it) }
        val candKeys = candidate.map { identityKey(it) }
        if (candKeys.toSet() != origMap.keys) {
            errors.add("Application identities changed during transition")
            return WorkspaceValidationResult(isValid = false, errors = errors)
        }

        for (app in candidate) {
            val prev = origMap[identityKey(app)] ?: continue
            if (prev.folderId != app.folderId) {
                errors.add("Folder membership mutated for ${app.componentKey}")
            }
            if (prev.isPinnedToDock != app.isPinnedToDock) {
                errors.add("Dock membership mutated for ${app.componentKey}")
            }
            if (prev.dockRank != app.dockRank) {
                errors.add("Dock rank mutated during home/drawer shuffle for ${app.componentKey}")
            }
            if (prev.isFixed && (prev.pageIndex != app.pageIndex || prev.slotIndex != app.slotIndex)) {
                errors.add("Fixed app moved: ${app.componentKey}")
            }
            if ((prev.folderId != null || prev.isPinnedToDock || !prev.isShuffled || !prev.isOnHomeScreen) &&
                (prev.pageIndex != app.pageIndex || prev.slotIndex != app.slotIndex)
            ) {
                errors.add("Immune app moved on home screen: ${app.componentKey}")
            }
            if (!crossPageAllowed && prev.isShuffled && !prev.isFixed &&
                prev.folderId == null && !prev.isPinnedToDock && prev.pageIndex != app.pageIndex
            ) {
                errors.add("App crossed page boundary when cross-page disabled: ${app.componentKey}")
            }
            if (!drawerShuffleEnabled && prev.drawerSlotIndex != app.drawerSlotIndex) {
                errors.add("Drawer slot mutated while drawer shuffle disabled: ${app.componentKey}")
            }
        }

        val workspaceResult = validateWorkspace(
            apps = candidate,
            folders = folders,
            widgets = widgets,
            pageCount = pageCount,
            dockCount = Int.MAX_VALUE,
            drawerShuffleEnabled = drawerShuffleEnabled
        )
        errors.addAll(workspaceResult.errors)

        return WorkspaceValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }

    private fun identityKey(app: AppItem): String = "${app.packageName}/${app.activityName}"
}

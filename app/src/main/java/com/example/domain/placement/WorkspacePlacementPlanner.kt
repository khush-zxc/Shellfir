package com.example.domain.placement

import com.example.domain.model.AppItem
import com.example.domain.model.FolderItem
import com.example.domain.validation.WidgetPlacement
import com.example.domain.validation.WorkspaceValidator

data class PlacementMoveRequest(
    val packageName: String,
    val activityName: String,
    val targetPageIndex: Int,
    val targetSlotIndex: Int,
    val gridColumns: Int,
    val pageCount: Int,
    val preserveEmptySpaces: Boolean = true,
    val apps: List<AppItem>,
    val folders: List<FolderItem> = emptyList(),
    val widgets: List<WidgetPlacement> = emptyList(),
    val maxSlotsPerPage: Int? = null
)

data class PlacementMoveResult(
    val isValid: Boolean,
    val rejectionReason: String? = null,
    val movedApp: AppItem? = null,
    val updatedApps: List<AppItem>,
    val changedHomePlacements: Map<Pair<String, String>, Pair<Int, Int>> = emptyMap()
)

data class FolderMoveRequest(
    val folderId: String,
    val targetPageIndex: Int,
    val targetSlotIndex: Int,
    val gridColumns: Int,
    val pageCount: Int,
    val preserveEmptySpaces: Boolean = true,
    val apps: List<AppItem>,
    val folders: List<FolderItem>,
    val widgets: List<WidgetPlacement> = emptyList(),
    val maxSlotsPerPage: Int? = null
)

data class FolderMoveResult(
    val isValid: Boolean,
    val rejectionReason: String? = null,
    val movedFolder: FolderItem? = null,
    val updatedFolders: List<FolderItem>,
    val updatedApps: List<AppItem>,
    val changedHomePlacements: Map<Pair<String, String>, Pair<Int, Int>> = emptyMap()
)

/**
 * Pure, deterministic home-screen rank placement & collision planner.
 * Independent from Compose and Room so it can be unit-tested directly and shared
 * between UI drag previews and transactional repository persistence.
 */
class WorkspacePlacementPlanner(
    private val workspaceValidator: WorkspaceValidator = WorkspaceValidator()
) {

    fun planMove(request: PlacementMoveRequest): PlacementMoveResult {
        if (request.gridColumns < 1) {
            return reject(request.apps, "Grid columns must be at least 1")
        }
        if (request.pageCount < 1) {
            return reject(request.apps, "Page count must be at least 1")
        }
        if (request.targetPageIndex !in 0 until request.pageCount) {
            return reject(
                request.apps,
                "Target page ${request.targetPageIndex} is out of bounds (pageCount=${request.pageCount})"
            )
        }
        if (request.targetSlotIndex < 0) {
            return reject(request.apps, "Target slot ${request.targetSlotIndex} cannot be negative")
        }
        if (request.maxSlotsPerPage != null && request.targetSlotIndex >= request.maxSlotsPerPage) {
            return reject(
                request.apps,
                "Target slot ${request.targetSlotIndex} exceeds page capacity ${request.maxSlotsPerPage}"
            )
        }

        val targetComponent = request.packageName to request.activityName
        val movingApp = request.apps.find { it.packageName == request.packageName && it.activityName == request.activityName }
            ?: return reject(request.apps, "Application ${request.packageName}/${request.activityName} not found")

        if (!movingApp.isInstalled) {
            return reject(request.apps, "Uninstalled application cannot be moved")
        }
        if (!movingApp.isOnHomeScreen || movingApp.folderId != null) {
            return reject(request.apps, "Application is not directly placed on the Home screen")
        }
        if (movingApp.isFixed) {
            return reject(request.apps, "Fixed application cannot be moved")
        }

        // Verify pre-existing workspace sanity so we never silently mask a corrupt state.
        val preCheck = workspaceValidator.validateWorkspace(
            apps = request.apps,
            folders = request.folders,
            widgets = request.widgets,
            pageCount = request.pageCount,
            dockCount = maxOf(5, request.apps.count { it.isPinnedToDock }),
            drawerShuffleEnabled = false
        )
        if (!preCheck.isValid) {
            return reject(request.apps, "Invalid workspace state: ${preCheck.errors.firstOrNull()}")
        }

        if (movingApp.pageIndex == request.targetPageIndex && movingApp.slotIndex == request.targetSlotIndex) {
            return PlacementMoveResult(
                isValid = true,
                movedApp = movingApp,
                updatedApps = request.apps,
                changedHomePlacements = emptyMap()
            )
        }

        // Static obstacles on target page: folders, widgets, and fixed apps.
        val staticBlockedOnTarget = buildStaticObstacles(
            pageIndex = request.targetPageIndex,
            apps = request.apps,
            folders = request.folders,
            widgets = request.widgets,
            excludeComponent = targetComponent
        )

        if (request.targetSlotIndex in staticBlockedOnTarget) {
            val reason = when {
                request.folders.any { it.pageIndex == request.targetPageIndex && it.slotIndex == request.targetSlotIndex } ->
                    "Target slot is occupied by a folder"
                request.widgets.any { (request.targetPageIndex to request.targetSlotIndex) in it.occupiedCoords() } ->
                    "Target slot is occupied by a widget"
                else ->
                    "Target slot is occupied by a fixed application"
            }
            return reject(request.apps, reason)
        }

        // Movable non-fixed apps on target page excluding the dragged app.
        val movableOnTargetBySlot = mutableMapOf<Int, AppItem>()
        for (app in request.apps) {
            val comp = app.packageName to app.activityName
            if (comp != targetComponent &&
                app.isOnHomeScreen &&
                app.folderId == null &&
                !app.isFixed &&
                app.pageIndex == request.targetPageIndex
            ) {
                movableOnTargetBySlot[app.slotIndex] = app
            }
        }

        val reassignedPositions = mutableMapOf<Pair<String, String>, Pair<Int, Int>>()
        val occupant = movableOnTargetBySlot[request.targetSlotIndex]

        if (occupant != null) {
            val shiftApplied = computeDirectionalShift(
                movingFromSamePage = movingApp.pageIndex == request.targetPageIndex,
                sourceSlot = movingApp.slotIndex,
                targetSlot = request.targetSlotIndex,
                staticBlocked = staticBlockedOnTarget,
                movableBySlot = movableOnTargetBySlot,
                maxSlotsPerPage = request.maxSlotsPerPage
            ) ?: return reject(request.apps, "No available slot on target page to shift occupied items")

            for ((newSlot, shiftedApp) in shiftApplied) {
                reassignedPositions[shiftedApp.packageName to shiftedApp.activityName] =
                    request.targetPageIndex to newSlot
            }
        }

        // Assign moving app to target position.
        reassignedPositions[targetComponent] = request.targetPageIndex to request.targetSlotIndex

        var candidateApps = request.apps.map { app ->
            val comp = app.packageName to app.activityName
            val updatedPos = reassignedPositions[comp]
            if (updatedPos != null) {
                app.copy(pageIndex = updatedPos.first, slotIndex = updatedPos.second)
            } else {
                app
            }
        }

        // When COMPACT_LAYOUT is active (!preserveEmptySpaces), close vacated gaps on the source page
        // when moving across pages without disturbing folders, widgets, fixed apps, or dock placements.
        if (!request.preserveEmptySpaces && movingApp.pageIndex != request.targetPageIndex) {
            candidateApps = compactSourcePageGaps(
                apps = candidateApps,
                sourcePageIndex = movingApp.pageIndex,
                folders = request.folders,
                widgets = request.widgets
            )
        }

        // Validate resulting workspace invariants before returning.
        val postCheck = workspaceValidator.validateWorkspace(
            apps = candidateApps,
            folders = request.folders,
            widgets = request.widgets,
            pageCount = request.pageCount,
            dockCount = maxOf(5, candidateApps.count { it.isPinnedToDock }),
            drawerShuffleEnabled = false
        )
        if (!postCheck.isValid) {
            return reject(request.apps, postCheck.errors.joinToString("; "))
        }

        // Ensure fixed, dock, and folder invariants did not drift.
        val origByComp = request.apps.associateBy { it.packageName to it.activityName }
        val changed = linkedMapOf<Pair<String, String>, Pair<Int, Int>>()
        for (app in candidateApps) {
            val comp = app.packageName to app.activityName
            val prev = origByComp[comp] ?: return reject(request.apps, "Application identity lost")
            if (prev.isFixed && (prev.pageIndex != app.pageIndex || prev.slotIndex != app.slotIndex)) {
                return reject(request.apps, "Fixed application ${app.componentKey} cannot move")
            }
            if (prev.dockRank != app.dockRank || prev.isPinnedToDock != app.isPinnedToDock || prev.folderId != app.folderId) {
                return reject(request.apps, "Dock or folder state mutated unexpectedly")
            }
            if (prev.pageIndex != app.pageIndex || prev.slotIndex != app.slotIndex) {
                changed[comp] = app.pageIndex to app.slotIndex
            }
        }

        val movedResultApp = candidateApps.first { it.packageName == request.packageName && it.activityName == request.activityName }
        return PlacementMoveResult(
            isValid = true,
            movedApp = movedResultApp,
            updatedApps = candidateApps,
            changedHomePlacements = changed
        )
    }

    /**
     * Checks whether a given (pageIndex, slotIndex) is a valid static/obstacle-free drop target
     * without mutating any workspace state.
     */
    fun isValidDropTarget(
        apps: List<AppItem>,
        folders: List<FolderItem>,
        widgets: List<WidgetPlacement>,
        movingPackageName: String,
        movingActivityName: String,
        targetPageIndex: Int,
        targetSlotIndex: Int,
        pageCount: Int,
        maxSlotsPerPage: Int? = null
    ): Boolean {
        if (targetPageIndex !in 0 until pageCount) return false
        if (targetSlotIndex < 0) return false
        if (maxSlotsPerPage != null && targetSlotIndex >= maxSlotsPerPage) return false

        val movingComp = movingPackageName to movingActivityName
        val movingApp = apps.find { it.packageName == movingPackageName && it.activityName == movingActivityName }
            ?: return false
        if (!movingApp.isInstalled || !movingApp.isOnHomeScreen || movingApp.folderId != null || movingApp.isFixed) {
            return false
        }

        val blocked = buildStaticObstacles(
            pageIndex = targetPageIndex,
            apps = apps,
            folders = folders,
            widgets = widgets,
            excludeComponent = movingComp
        )
        return targetSlotIndex !in blocked
    }

    /**
     * Checks whether dragging [dragged] onto [target] can create a new folder.
     */
    fun canCreateFolderFromApps(dragged: AppItem, target: AppItem): Pair<Boolean, String?> {
        if (!dragged.isInstalled || !target.isInstalled) {
            return false to "Uninstalled application cannot be added to a folder"
        }
        if (dragged.componentKey == target.componentKey) {
            return false to "Cannot create a folder containing the same app twice"
        }
        if (!dragged.isOnHomeScreen || dragged.folderId != null || !target.isOnHomeScreen || target.folderId != null) {
            return false to "Both applications must be directly on the Home screen"
        }
        if (dragged.isFixed || target.isFixed) {
            return false to "Fixed applications cannot be moved into a folder"
        }
        return true to null
    }

    /**
     * Checks whether dragging [dragged] onto an existing [folder] can add it to that folder.
     */
    fun canAddAppToFolder(dragged: AppItem, folder: FolderItem): Pair<Boolean, String?> {
        if (!dragged.isInstalled) {
            return false to "Uninstalled application cannot be added to a folder"
        }
        if (dragged.folderId == folder.id) {
            return false to "Application is already inside this folder"
        }
        if (!dragged.isOnHomeScreen || dragged.folderId != null) {
            return false to "Application must be directly on the Home screen"
        }
        if (dragged.isFixed) {
            return false to "Fixed application cannot be moved into a folder"
        }
        return true to null
    }

    /**
     * Plans moving an existing [FolderItem] on the Home screen as a single workspace item
     * without moving or altering its member apps individually.
     */
    fun planFolderMove(request: FolderMoveRequest): FolderMoveResult {
        if (request.gridColumns < 1) {
            return rejectFolder(request.folders, request.apps, "Grid columns must be at least 1")
        }
        if (request.pageCount < 1) {
            return rejectFolder(request.folders, request.apps, "Page count must be at least 1")
        }
        if (request.targetPageIndex !in 0 until request.pageCount) {
            return rejectFolder(
                request.folders,
                request.apps,
                "Target page ${request.targetPageIndex} is out of bounds (pageCount=${request.pageCount})"
            )
        }
        if (request.targetSlotIndex < 0) {
            return rejectFolder(request.folders, request.apps, "Target slot ${request.targetSlotIndex} cannot be negative")
        }
        if (request.maxSlotsPerPage != null && request.targetSlotIndex >= request.maxSlotsPerPage) {
            return rejectFolder(
                request.folders,
                request.apps,
                "Target slot ${request.targetSlotIndex} exceeds page capacity ${request.maxSlotsPerPage}"
            )
        }

        val movingFolder = request.folders.find { it.id == request.folderId }
            ?: return rejectFolder(request.folders, request.apps, "Folder ${request.folderId} not found")

        val preCheck = workspaceValidator.validateWorkspace(
            apps = request.apps,
            folders = request.folders,
            widgets = request.widgets,
            pageCount = request.pageCount,
            dockCount = maxOf(5, request.apps.count { it.isPinnedToDock }),
            drawerShuffleEnabled = false
        )
        if (!preCheck.isValid) {
            return rejectFolder(request.folders, request.apps, "Invalid workspace state: ${preCheck.errors.firstOrNull()}")
        }

        if (movingFolder.pageIndex == request.targetPageIndex && movingFolder.slotIndex == request.targetSlotIndex) {
            return FolderMoveResult(
                isValid = true,
                movedFolder = movingFolder,
                updatedFolders = request.folders,
                updatedApps = request.apps,
                changedHomePlacements = emptyMap()
            )
        }

        val staticBlockedOnTarget = buildStaticObstacles(
            pageIndex = request.targetPageIndex,
            apps = request.apps,
            folders = request.folders,
            widgets = request.widgets,
            excludeComponent = "" to "",
            excludeFolderId = request.folderId
        )

        if (request.targetSlotIndex in staticBlockedOnTarget) {
            val reason = when {
                request.folders.any { it.id != request.folderId && it.pageIndex == request.targetPageIndex && it.slotIndex == request.targetSlotIndex } ->
                    "Target slot is occupied by another folder"
                request.widgets.any { (request.targetPageIndex to request.targetSlotIndex) in it.occupiedCoords() } ->
                    "Target slot is occupied by a widget"
                else ->
                    "Target slot is occupied by a fixed application"
            }
            return rejectFolder(request.folders, request.apps, reason)
        }

        val movableOnTargetBySlot = mutableMapOf<Int, AppItem>()
        for (app in request.apps) {
            if (app.isOnHomeScreen && app.folderId == null && !app.isFixed && app.pageIndex == request.targetPageIndex) {
                movableOnTargetBySlot[app.slotIndex] = app
            }
        }

        val reassignedAppPositions = mutableMapOf<Pair<String, String>, Pair<Int, Int>>()
        val occupant = movableOnTargetBySlot[request.targetSlotIndex]
        if (occupant != null) {
            val shiftApplied = computeDirectionalShift(
                movingFromSamePage = movingFolder.pageIndex == request.targetPageIndex,
                sourceSlot = movingFolder.slotIndex,
                targetSlot = request.targetSlotIndex,
                staticBlocked = staticBlockedOnTarget,
                movableBySlot = movableOnTargetBySlot,
                maxSlotsPerPage = request.maxSlotsPerPage
            ) ?: return rejectFolder(request.folders, request.apps, "No available slot on target page to shift occupied items")

            for ((newSlot, shiftedApp) in shiftApplied) {
                reassignedAppPositions[shiftedApp.packageName to shiftedApp.activityName] =
                    request.targetPageIndex to newSlot
            }
        }

        val updatedFolders = request.folders.map { folder ->
            if (folder.id == request.folderId) {
                folder.copy(pageIndex = request.targetPageIndex, slotIndex = request.targetSlotIndex)
            } else {
                folder
            }
        }

        var candidateApps = request.apps.map { app ->
            val comp = app.packageName to app.activityName
            val updatedPos = reassignedAppPositions[comp]
            if (updatedPos != null) app.copy(pageIndex = updatedPos.first, slotIndex = updatedPos.second) else app
        }

        if (!request.preserveEmptySpaces && movingFolder.pageIndex != request.targetPageIndex) {
            candidateApps = compactSourcePageGaps(
                apps = candidateApps,
                sourcePageIndex = movingFolder.pageIndex,
                folders = updatedFolders,
                widgets = request.widgets
            )
        }

        val postCheck = workspaceValidator.validateWorkspace(
            apps = candidateApps,
            folders = updatedFolders,
            widgets = request.widgets,
            pageCount = request.pageCount,
            dockCount = maxOf(5, candidateApps.count { it.isPinnedToDock }),
            drawerShuffleEnabled = false
        )
        if (!postCheck.isValid) {
            return rejectFolder(request.folders, request.apps, postCheck.errors.joinToString("; "))
        }

        val origByComp = request.apps.associateBy { it.packageName to it.activityName }
        val changedApps = linkedMapOf<Pair<String, String>, Pair<Int, Int>>()
        for (app in candidateApps) {
            val comp = app.packageName to app.activityName
            val prev = origByComp[comp] ?: return rejectFolder(request.folders, request.apps, "Application identity lost")
            if (prev.isFixed && (prev.pageIndex != app.pageIndex || prev.slotIndex != app.slotIndex)) {
                return rejectFolder(request.folders, request.apps, "Fixed application ${app.componentKey} cannot move")
            }
            if (prev.dockRank != app.dockRank || prev.isPinnedToDock != app.isPinnedToDock || prev.folderId != app.folderId) {
                return rejectFolder(request.folders, request.apps, "Dock or folder state mutated unexpectedly")
            }
            if (prev.pageIndex != app.pageIndex || prev.slotIndex != app.slotIndex) {
                changedApps[comp] = app.pageIndex to app.slotIndex
            }
        }

        val movedResultFolder = updatedFolders.first { it.id == request.folderId }
        return FolderMoveResult(
            isValid = true,
            movedFolder = movedResultFolder,
            updatedFolders = updatedFolders,
            updatedApps = candidateApps,
            changedHomePlacements = changedApps
        )
    }

    private fun buildStaticObstacles(
        pageIndex: Int,
        apps: List<AppItem>,
        folders: List<FolderItem>,
        widgets: List<WidgetPlacement>,
        excludeComponent: Pair<String, String>,
        excludeFolderId: String? = null
    ): Set<Int> {
        val blocked = mutableSetOf<Int>()
        for (folder in folders) {
            if (folder.pageIndex == pageIndex && folder.id != excludeFolderId) {
                blocked.add(folder.slotIndex)
            }
        }
        for (widget in widgets) {
            for ((wPage, wSlot) in widget.occupiedCoords()) {
                if (wPage == pageIndex) {
                    blocked.add(wSlot)
                }
            }
        }
        for (app in apps) {
            val comp = app.packageName to app.activityName
            if (comp != excludeComponent &&
                app.isOnHomeScreen &&
                app.folderId == null &&
                app.isFixed &&
                app.pageIndex == pageIndex
            ) {
                blocked.add(app.slotIndex)
            }
        }
        return blocked
    }

    /**
     * Computes deterministic shifts for movable apps when [targetSlot] is occupied:
     * - Same-page drags shift along the non-blocked chain toward [sourceSlot] (absorbing empty gaps
     *   along the way or vacating [sourceSlot] itself, skipping fixed apps/folders/widgets).
     * - Cross-page drags shift forward (+1) from [targetSlot] to the nearest free non-blocked slot
     *   (or backward if bounded by [maxSlotsPerPage] and forward is full).
     *
     * Returns map of (newSlot -> AppItem) for shifted apps, or null if impossible.
     */
    private fun computeDirectionalShift(
        movingFromSamePage: Boolean,
        sourceSlot: Int,
        targetSlot: Int,
        staticBlocked: Set<Int>,
        movableBySlot: Map<Int, AppItem>,
        maxSlotsPerPage: Int?
    ): Map<Int, AppItem>? {
        val upperBound = maxSlotsPerPage ?: Int.MAX_VALUE

        if (movingFromSamePage) {
            val stepTowardSource = if (sourceSlot < targetSlot) -1 else 1
            val chain = collectShiftChain(
                startSlot = targetSlot,
                step = stepTowardSource,
                upperBound = upperBound,
                staticBlocked = staticBlocked,
                movableBySlot = movableBySlot
            )
            if (chain != null) {
                return applyChainShift(chain, movableBySlot)
            }
        }

        // Cross-page or fallback: push forward (+1) from targetSlot to nearest free non-blocked slot.
        val forwardChain = collectShiftChain(
            startSlot = targetSlot,
            step = 1,
            upperBound = upperBound,
            staticBlocked = staticBlocked,
            movableBySlot = movableBySlot
        )
        if (forwardChain != null) {
            return applyChainShift(forwardChain, movableBySlot)
        }

        // Bounded-page fallback: push backward (-1) if forward reached upperBound.
        val backwardChain = collectShiftChain(
            startSlot = targetSlot,
            step = -1,
            upperBound = upperBound,
            staticBlocked = staticBlocked,
            movableBySlot = movableBySlot
        )
        if (backwardChain != null) {
            return applyChainShift(backwardChain, movableBySlot)
        }

        return null
    }

    /**
     * Walks from [startSlot] in direction [step], skipping [staticBlocked], until a free slot
     * (not in [staticBlocked] and not in [movableBySlot]) is found.
     * Returns the ordered list of non-blocked slots `[startSlot, ..., freeSlot]`, or null if out of bounds.
     */
    private fun collectShiftChain(
        startSlot: Int,
        step: Int,
        upperBound: Int,
        staticBlocked: Set<Int>,
        movableBySlot: Map<Int, AppItem>
    ): List<Int>? {
        val chain = mutableListOf<Int>()
        var cursor = startSlot
        while (cursor >= 0 && cursor < upperBound) {
            if (cursor !in staticBlocked) {
                chain.add(cursor)
                if (cursor !in movableBySlot) {
                    return chain
                }
            }
            if (step > 0 && cursor == Int.MAX_VALUE) break
            cursor += step
        }
        return null
    }

    private fun applyChainShift(
        chain: List<Int>,
        movableBySlot: Map<Int, AppItem>
    ): Map<Int, AppItem> {
        val shifted = linkedMapOf<Int, AppItem>()
        // chain = [targetSlot, s1, s2, ..., freeSlot]
        // App at chain[i] moves to chain[i + 1].
        for (i in 0 until chain.size - 1) {
            val fromSlot = chain[i]
            val toSlot = chain[i + 1]
            val app = movableBySlot[fromSlot] ?: continue
            shifted[toSlot] = app
        }
        return shifted
    }

    private fun compactSourcePageGaps(
        apps: List<AppItem>,
        sourcePageIndex: Int,
        folders: List<FolderItem>,
        widgets: List<WidgetPlacement>
    ): List<AppItem> {
        val blocked = buildStaticObstacles(
            pageIndex = sourcePageIndex,
            apps = apps,
            folders = folders,
            widgets = widgets,
            excludeComponent = "" to ""
        )
        val movableSourceApps = apps
            .filter { it.isOnHomeScreen && it.folderId == null && !it.isFixed && it.pageIndex == sourcePageIndex }
            .sortedBy { it.slotIndex }

        var nextSlot = 0
        val compactedByComp = mutableMapOf<Pair<String, String>, Int>()
        for (app in movableSourceApps) {
            while (nextSlot in blocked) {
                nextSlot++
            }
            compactedByComp[app.packageName to app.activityName] = nextSlot
            nextSlot++
        }

        return apps.map { app ->
            val newSlot = compactedByComp[app.packageName to app.activityName]
            if (newSlot != null) app.copy(slotIndex = newSlot) else app
        }
    }

    private fun reject(originalApps: List<AppItem>, reason: String): PlacementMoveResult {
        return PlacementMoveResult(
            isValid = false,
            rejectionReason = reason,
            movedApp = null,
            updatedApps = originalApps,
            changedHomePlacements = emptyMap()
        )
    }

    private fun rejectFolder(
        originalFolders: List<FolderItem>,
        originalApps: List<AppItem>,
        reason: String
    ): FolderMoveResult {
        return FolderMoveResult(
            isValid = false,
            rejectionReason = reason,
            movedFolder = null,
            updatedFolders = originalFolders,
            updatedApps = originalApps,
            changedHomePlacements = emptyMap()
        )
    }
}

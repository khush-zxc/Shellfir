package com.example.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.example.domain.model.AppItem
import com.example.domain.model.FolderItem
import com.example.domain.model.OccupancyOption
import com.example.domain.placement.FolderMoveRequest
import com.example.domain.placement.FolderMoveResult
import com.example.domain.placement.PlacementMoveRequest
import com.example.domain.placement.PlacementMoveResult
import com.example.domain.placement.WorkspacePlacementPlanner
import com.example.domain.validation.WidgetPlacement
import kotlin.math.hypot

data class GridDropTarget(
    val pageIndex: Int,
    val slotIndex: Int
)

enum class DropActionType {
    MOVE_SLOT,
    CREATE_FOLDER,
    ADD_TO_FOLDER,
    MOVE_FOLDER,
    INVALID
}

data class PendingHomeMove(
    val app: AppItem,
    val originalPageIndex: Int,
    val originalSlotIndex: Int,
    val targetPageIndex: Int,
    val targetSlotIndex: Int,
    val previewHomeApps: List<AppItem>
)

data class PendingFolderMove(
    val folder: FolderItem,
    val originalPageIndex: Int,
    val originalSlotIndex: Int,
    val targetPageIndex: Int,
    val targetSlotIndex: Int,
    val previewFolders: List<FolderItem>,
    val previewHomeApps: List<AppItem>
)

@Stable
class HomeDragDropController(
    private val planner: WorkspacePlacementPlanner = WorkspacePlacementPlanner()
) {
    var draggedApp by mutableStateOf<AppItem?>(null)
        private set

    var draggedFolder by mutableStateOf<FolderItem?>(null)
        private set

    var sourcePageIndex by mutableIntStateOf(0)
        private set

    var sourceSlotIndex by mutableIntStateOf(0)
        private set

    /**
     * Continuously updated pointer offset in root coordinates.
     * Read ONLY inside draw/layout-phase lambdas (e.g. graphicsLayer) to avoid
     * recomposing the workspace grid on every pointer frame.
     */
    var pointerInRoot by mutableStateOf(Offset.Zero)
        private set

    var dragAnchorOffset by mutableStateOf(Offset.Zero)
        private set

    var hoveredTarget by mutableStateOf<GridDropTarget?>(null)
        private set

    var hoveredDropAction by mutableStateOf(DropActionType.MOVE_SLOT)
        private set

    var hoveredTargetAppForFolder by mutableStateOf<AppItem?>(null)
        private set

    var hoveredTargetFolder by mutableStateOf<FolderItem?>(null)
        private set

    var isFolderActionArmed by mutableStateOf(false)
        private set

    var isHoveredTargetValid by mutableStateOf(false)
        private set

    var hoveredRejectionReason by mutableStateOf<String?>(null)
        private set

    var previewPlan by mutableStateOf<PlacementMoveResult?>(null)
        private set

    var folderPreviewPlan by mutableStateOf<FolderMoveResult?>(null)
        private set

    var isPointerOutsideGrid by mutableStateOf(false)
        private set

    /**
     * -1 = left page edge hover, +1 = right page edge hover, 0 = none.
     */
    var edgeHoverDirection by mutableIntStateOf(0)
        private set

    var pendingMove by mutableStateOf<PendingHomeMove?>(null)
        private set

    var pendingFolderMove by mutableStateOf<PendingFolderMove?>(null)
        private set

    val isDragging: Boolean
        get() = draggedApp != null || draggedFolder != null

    // Snapshot of latest workspace data synced from NormalHomeScreen
    var currentPage: Int = 0
    var pageCount: Int = 1
    var gridColumns: Int = 4
    var preserveEmptySpaces: Boolean = true
    var allApps: List<AppItem> = emptyList()
    var homeApps: List<AppItem> = emptyList()
    var folders: List<FolderItem> = emptyList()
    var widgets: List<WidgetPlacement> = emptyList()

    var pagerBoundsInRoot: Rect = Rect.Zero
    var edgeZoneWidthPx: Float = 96f

    // Callbacks updated each composition
    var onCommitMove: ((AppItem, Int, Int, (Boolean, String?) -> Unit) -> Unit)? = null
    var onCreateFolderFromApps: ((AppItem, AppItem, (Boolean, String?) -> Unit) -> Unit)? = null
    var onAddAppToFolder: ((AppItem, FolderItem, (Boolean, String?) -> Unit) -> Unit)? = null
    var onMoveFolder: ((FolderItem, Int, Int, (Boolean, String?) -> Unit) -> Unit)? = null
    var onRestorePage: ((Int) -> Unit)? = null
    var onDragRejected: ((String) -> Unit)? = null

    private val gridBoundsByPage = mutableMapOf<Int, Rect>()
    private val slotBoundsByCoord = mutableMapOf<Pair<Int, Int>, Rect>()

    fun syncWorkspaceState(
        currentPage: Int,
        pageCount: Int,
        gridColumns: Int,
        occupancyOption: OccupancyOption,
        allApps: List<AppItem>,
        homeApps: List<AppItem>,
        folders: List<FolderItem>,
        widgets: List<WidgetPlacement>
    ) {
        this.currentPage = currentPage
        this.pageCount = pageCount.coerceAtLeast(1)
        this.gridColumns = gridColumns.coerceAtLeast(1)
        this.preserveEmptySpaces = occupancyOption != OccupancyOption.COMPACT_LAYOUT
        this.allApps = allApps
        this.homeApps = homeApps
        this.folders = folders
        this.widgets = widgets
    }

    fun registerGridBounds(pageIndex: Int, bounds: Rect) {
        gridBoundsByPage[pageIndex] = bounds
    }

    fun clearPageBounds(pageIndex: Int) {
        gridBoundsByPage.remove(pageIndex)
        slotBoundsByCoord.keys.removeAll { it.first == pageIndex }
    }

    fun registerSlotBounds(pageIndex: Int, slotIndex: Int, bounds: Rect) {
        slotBoundsByCoord[pageIndex to slotIndex] = bounds
    }

    fun findSlotAt(pageIndex: Int, rootPos: Offset): Int? {
        val gridBounds = gridBoundsByPage[pageIndex] ?: return null
        if (!gridBounds.contains(rootPos)) {
            return null
        }

        // Exact hit first
        for ((coord, rect) in slotBoundsByCoord) {
            if (coord.first == pageIndex && rect.contains(rootPos)) {
                return coord.second
            }
        }

        // Nearest cell within small gap tolerance (so inter-cell padding still feels continuous)
        var bestSlot: Int? = null
        var bestDist = Float.MAX_VALUE
        var maxTolerance = 0f
        for ((coord, rect) in slotBoundsByCoord) {
            if (coord.first != pageIndex) continue
            val dx = when {
                rootPos.x < rect.left -> rect.left - rootPos.x
                rootPos.x > rect.right -> rootPos.x - rect.right
                else -> 0f
            }
            val dy = when {
                rootPos.y < rect.top -> rect.top - rootPos.y
                rootPos.y > rect.bottom -> rootPos.y - rect.bottom
                else -> 0f
            }
            val dist = hypot(dx, dy)
            val tolerance = maxOf(rect.width, rect.height) * 0.45f
            if (dist < bestDist) {
                bestDist = dist
                bestSlot = coord.second
                maxTolerance = tolerance
            }
        }
        return if (bestSlot != null && bestDist <= maxTolerance) bestSlot else null
    }

    fun findHomeAppAt(pageIndex: Int, slotIndex: Int): AppItem? {
        return effectiveHomeApps().find { it.pageIndex == pageIndex && it.slotIndex == slotIndex }
    }

    fun findFolderAt(pageIndex: Int, slotIndex: Int): FolderItem? {
        return effectiveFolders().find { it.pageIndex == pageIndex && it.slotIndex == slotIndex }
    }

    fun computeAnchorOffset(pageIndex: Int, slotIndex: Int, pointerRoot: Offset): Offset {
        val rect = slotBoundsByCoord[pageIndex to slotIndex] ?: return Offset(48f, 48f)
        return Offset(
            x = (pointerRoot.x - rect.left).coerceIn(0f, rect.width),
            y = (pointerRoot.y - rect.top).coerceIn(0f, rect.height)
        )
    }

    fun startDrag(
        app: AppItem,
        initialPointerInRoot: Offset,
        dragAnchorOffsetInSlot: Offset
    ) {
        draggedApp = app
        draggedFolder = null
        sourcePageIndex = app.pageIndex
        sourceSlotIndex = app.slotIndex
        pointerInRoot = initialPointerInRoot
        dragAnchorOffset = dragAnchorOffsetInSlot
        isPointerOutsideGrid = false
        edgeHoverDirection = 0
        hoveredRejectionReason = null
        isFolderActionArmed = false
        hoveredTargetAppForFolder = null
        hoveredTargetFolder = null
        updateHoverForCurrentPointer(forceRecompute = true)
    }

    fun startFolderDrag(
        folder: FolderItem,
        initialPointerInRoot: Offset,
        dragAnchorOffsetInSlot: Offset
    ) {
        draggedApp = null
        draggedFolder = folder
        sourcePageIndex = folder.pageIndex
        sourceSlotIndex = folder.slotIndex
        pointerInRoot = initialPointerInRoot
        dragAnchorOffset = dragAnchorOffsetInSlot
        isPointerOutsideGrid = false
        edgeHoverDirection = 0
        hoveredRejectionReason = null
        isFolderActionArmed = false
        hoveredTargetAppForFolder = null
        hoveredTargetFolder = null
        updateHoverForCurrentPointer(forceRecompute = true)
    }

    fun updateDragPointer(newPointerInRoot: Offset) {
        if (!isDragging) return
        pointerInRoot = newPointerInRoot
        updateEdgeHoverDirection(newPointerInRoot)
        updateHoverForCurrentPointer(forceRecompute = false)
    }

    fun onPageSwitched(newPageIndex: Int) {
        currentPage = newPageIndex
        if (isDragging) {
            updateHoverForCurrentPointer(forceRecompute = true)
        }
    }

    /**
     * Called after the deliberate hover threshold expires while the pointer stays on the same
     * eligible app or folder cell. Arms folder creation or add-to-folder preview cleanly.
     */
    fun armFolderActionForCurrentTarget() {
        val app = draggedApp ?: return
        val target = hoveredTarget ?: return
        if (isPointerOutsideGrid) return

        val targetFolder = hoveredTargetFolder
        if (targetFolder != null && onAddAppToFolder != null) {
            val (canAdd, _) = planner.canAddAppToFolder(app, targetFolder)
            if (canAdd) {
                isFolderActionArmed = true
                hoveredDropAction = DropActionType.ADD_TO_FOLDER
                previewPlan = null
                isHoveredTargetValid = true
                hoveredRejectionReason = null
            }
            return
        }

        val targetApp = hoveredTargetAppForFolder
        if (targetApp != null && onCreateFolderFromApps != null &&
            targetApp.pageIndex == target.pageIndex && targetApp.slotIndex == target.slotIndex
        ) {
            val (canCreate, _) = planner.canCreateFolderFromApps(app, targetApp)
            if (canCreate) {
                isFolderActionArmed = true
                hoveredDropAction = DropActionType.CREATE_FOLDER
                // Clear shift preview so the stationary target app stays visibly anchored under the folder ring
                previewPlan = null
                isHoveredTargetValid = true
                hoveredRejectionReason = null
            }
        }
    }

    private fun updateEdgeHoverDirection(rootPos: Offset) {
        val pagerRect = pagerBoundsInRoot
        if (pagerRect.isEmpty || pageCount <= 1) {
            edgeHoverDirection = 0
            return
        }
        val withinVerticalWorkspace = rootPos.y >= pagerRect.top && rootPos.y <= pagerRect.bottom
        if (!withinVerticalWorkspace) {
            edgeHoverDirection = 0
            return
        }
        val effectiveZone = minOf(edgeZoneWidthPx, pagerRect.width * 0.2f)
        edgeHoverDirection = when {
            rootPos.x <= pagerRect.left + effectiveZone && currentPage > 0 -> -1
            rootPos.x >= pagerRect.right - effectiveZone && currentPage < pageCount - 1 -> 1
            else -> 0
        }
    }

    private fun updateHoverForCurrentPointer(forceRecompute: Boolean) {
        if (!isDragging) return
        val slot = findSlotAt(currentPage, pointerInRoot)
        if (slot == null) {
            isPointerOutsideGrid = true
            if (hoveredTarget != null || forceRecompute) {
                hoveredTarget = null
                hoveredDropAction = DropActionType.INVALID
                hoveredTargetAppForFolder = null
                hoveredTargetFolder = null
                isFolderActionArmed = false
                isHoveredTargetValid = false
                hoveredRejectionReason = null
                previewPlan = null
                folderPreviewPlan = null
            }
            return
        }

        isPointerOutsideGrid = false
        val newTarget = GridDropTarget(currentPage, slot)
        if (!forceRecompute && newTarget == hoveredTarget) {
            return
        }

        // Entering a new slot always disarms any pending folder hover action
        isFolderActionArmed = false
        hoveredTarget = newTarget

        val folderBeingDragged = draggedFolder
        if (folderBeingDragged != null) {
            hoveredTargetAppForFolder = null
            hoveredTargetFolder = null
            previewPlan = null
            val folderPlan = planner.planFolderMove(
                FolderMoveRequest(
                    folderId = folderBeingDragged.id,
                    targetPageIndex = newTarget.pageIndex,
                    targetSlotIndex = newTarget.slotIndex,
                    gridColumns = gridColumns,
                    pageCount = pageCount,
                    preserveEmptySpaces = preserveEmptySpaces,
                    apps = allApps,
                    folders = folders,
                    widgets = widgets
                )
            )
            folderPreviewPlan = if (folderPlan.isValid) folderPlan else null
            hoveredDropAction = if (folderPlan.isValid) DropActionType.MOVE_FOLDER else DropActionType.INVALID
            isHoveredTargetValid = folderPlan.isValid
            hoveredRejectionReason = if (folderPlan.isValid) null else folderPlan.rejectionReason
            return
        }

        val app = draggedApp ?: return
        folderPreviewPlan = null

        val stationaryFolder = folders.find { it.pageIndex == newTarget.pageIndex && it.slotIndex == newTarget.slotIndex }
        if (stationaryFolder != null && onAddAppToFolder != null) {
            val (canAdd, reason) = planner.canAddAppToFolder(app, stationaryFolder)
            hoveredTargetFolder = stationaryFolder
            hoveredTargetAppForFolder = null
            previewPlan = null
            hoveredDropAction = if (canAdd) DropActionType.ADD_TO_FOLDER else DropActionType.INVALID
            isHoveredTargetValid = canAdd
            hoveredRejectionReason = reason
            return
        }

        hoveredTargetFolder = null
        val stationaryApp = homeApps.find { it.pageIndex == newTarget.pageIndex && it.slotIndex == newTarget.slotIndex }
        val eligibleFolderTarget = if (stationaryApp != null && onCreateFolderFromApps != null) {
            val (canCreate, _) = planner.canCreateFolderFromApps(app, stationaryApp)
            if (canCreate) stationaryApp else null
        } else null
        hoveredTargetAppForFolder = eligibleFolderTarget

        val plan = planner.planMove(
            PlacementMoveRequest(
                packageName = app.packageName,
                activityName = app.activityName,
                targetPageIndex = newTarget.pageIndex,
                targetSlotIndex = newTarget.slotIndex,
                gridColumns = gridColumns,
                pageCount = pageCount,
                preserveEmptySpaces = preserveEmptySpaces,
                apps = allApps,
                folders = folders,
                widgets = widgets
            )
        )
        previewPlan = if (plan.isValid) plan else null
        hoveredDropAction = if (plan.isValid) DropActionType.MOVE_SLOT else DropActionType.INVALID
        isHoveredTargetValid = plan.isValid
        hoveredRejectionReason = if (plan.isValid) null else plan.rejectionReason
    }

    fun commitOrCancelOnDrop() {
        val originalPage = sourcePageIndex
        val originalSlot = sourceSlotIndex
        val target = hoveredTarget
        val outside = isPointerOutsideGrid
        val rejection = hoveredRejectionReason

        val folder = draggedFolder
        if (folder != null) {
            val folderPlan = folderPreviewPlan
            if (target == null || outside) {
                resetDragState()
                if (currentPage != originalPage) onRestorePage?.invoke(originalPage)
                return
            }
            if (folderPlan == null || !folderPlan.isValid || !isHoveredTargetValid) {
                resetDragState()
                if (currentPage != originalPage) onRestorePage?.invoke(originalPage)
                if (!rejection.isNullOrBlank()) onDragRejected?.invoke(rejection)
                return
            }
            if (target.pageIndex == originalPage && target.slotIndex == originalSlot) {
                resetDragState()
                return
            }

            val previewHome = folderPlan.updatedApps.filter { it.isOnHomeScreen && it.folderId == null }
            pendingFolderMove = PendingFolderMove(
                folder = folder,
                originalPageIndex = originalPage,
                originalSlotIndex = originalSlot,
                targetPageIndex = target.pageIndex,
                targetSlotIndex = target.slotIndex,
                previewFolders = folderPlan.updatedFolders,
                previewHomeApps = previewHome
            )
            resetDragState()

            val moveFolderCb = onMoveFolder
            if (moveFolderCb != null) {
                moveFolderCb(folder, target.pageIndex, target.slotIndex) { success, _ ->
                    pendingFolderMove = null
                    if (!success && currentPage != originalPage) {
                        onRestorePage?.invoke(originalPage)
                    }
                }
            } else {
                pendingFolderMove = null
                if (currentPage != originalPage) onRestorePage?.invoke(originalPage)
            }
            return
        }

        val app = draggedApp ?: return
        val plan = previewPlan
        val action = hoveredDropAction
        val armed = isFolderActionArmed
        val folderTargetApp = hoveredTargetAppForFolder
        val existingFolderTarget = hoveredTargetFolder

        // Invalid drop area (outside grid) -> silent safe cancel & restore original page/slot
        if (target == null || outside) {
            resetDragState()
            if (currentPage != originalPage) {
                onRestorePage?.invoke(originalPage)
            }
            return
        }

        // Deliberate folder creation on eligible target app
        if (armed && action == DropActionType.CREATE_FOLDER && folderTargetApp != null) {
            val createCb = onCreateFolderFromApps
            resetDragState()
            if (createCb != null) {
                createCb(app, folderTargetApp) { success, _ ->
                    if (!success && currentPage != originalPage) {
                        onRestorePage?.invoke(originalPage)
                    }
                }
            } else if (currentPage != originalPage) {
                onRestorePage?.invoke(originalPage)
            }
            return
        }

        // Deliberate add-to-existing-folder
        if (action == DropActionType.ADD_TO_FOLDER && existingFolderTarget != null) {
            if (!armed) {
                // Released before deliberate hover threshold -> cancel safely without mutating
                resetDragState()
                if (currentPage != originalPage) {
                    onRestorePage?.invoke(originalPage)
                }
                return
            }
            val addCb = onAddAppToFolder
            resetDragState()
            if (addCb != null) {
                addCb(app, existingFolderTarget) { success, _ ->
                    if (!success && currentPage != originalPage) {
                        onRestorePage?.invoke(originalPage)
                    }
                }
            } else if (currentPage != originalPage) {
                onRestorePage?.invoke(originalPage)
            }
            return
        }

        // Invalid obstacle on grid (fixed app / folder / widget) -> reject & restore
        if (plan == null || !plan.isValid || !isHoveredTargetValid) {
            resetDragState()
            if (currentPage != originalPage) {
                onRestorePage?.invoke(originalPage)
            }
            if (!rejection.isNullOrBlank()) {
                onDragRejected?.invoke(rejection)
            }
            return
        }

        // Dropped back onto original slot
        if (target.pageIndex == originalPage && target.slotIndex == originalSlot) {
            resetDragState()
            return
        }

        val previewHomeApps = plan.updatedApps.filter { it.isOnHomeScreen && it.folderId == null }
        pendingMove = PendingHomeMove(
            app = app,
            originalPageIndex = originalPage,
            originalSlotIndex = originalSlot,
            targetPageIndex = target.pageIndex,
            targetSlotIndex = target.slotIndex,
            previewHomeApps = previewHomeApps
        )
        resetDragState()

        val commitCallback = onCommitMove
        if (commitCallback != null) {
            commitCallback(app, target.pageIndex, target.slotIndex) { success, _ ->
                pendingMove = null
                if (!success && currentPage != originalPage) {
                    onRestorePage?.invoke(originalPage)
                }
            }
        } else {
            pendingMove = null
            if (currentPage != originalPage) {
                onRestorePage?.invoke(originalPage)
            }
        }
    }

    fun cancelDragAndRestorePage() {
        val originalPage = sourcePageIndex
        val wasDragging = isDragging
        resetDragState()
        if (wasDragging && currentPage != originalPage) {
            onRestorePage?.invoke(originalPage)
        }
    }

    fun cancelDragWithoutMessage() {
        resetDragState()
    }

    fun effectiveHomeApps(): List<AppItem> {
        val liveAppPreview = previewPlan?.takeIf { it.isValid }?.updatedApps
            ?.filter { it.isOnHomeScreen && it.folderId == null }
        if (liveAppPreview != null) return liveAppPreview

        val liveFolderPreview = folderPreviewPlan?.takeIf { it.isValid }?.updatedApps
            ?.filter { it.isOnHomeScreen && it.folderId == null }
        if (liveFolderPreview != null) return liveFolderPreview

        val pending = pendingMove
        if (pending != null) return pending.previewHomeApps

        val pendingFld = pendingFolderMove
        if (pendingFld != null) return pendingFld.previewHomeApps

        return homeApps
    }

    fun effectiveFolders(): List<FolderItem> {
        val liveFolderPreview = folderPreviewPlan?.takeIf { it.isValid }?.updatedFolders
        if (liveFolderPreview != null) return liveFolderPreview

        val pendingFld = pendingFolderMove
        if (pendingFld != null) return pendingFld.previewFolders

        return folders
    }

    private fun resetDragState() {
        draggedApp = null
        draggedFolder = null
        pointerInRoot = Offset.Zero
        dragAnchorOffset = Offset.Zero
        hoveredTarget = null
        hoveredDropAction = DropActionType.MOVE_SLOT
        hoveredTargetAppForFolder = null
        hoveredTargetFolder = null
        isFolderActionArmed = false
        isHoveredTargetValid = false
        hoveredRejectionReason = null
        previewPlan = null
        folderPreviewPlan = null
        isPointerOutsideGrid = false
        edgeHoverDirection = 0
    }
}

@Composable
fun rememberHomeDragDropController(): HomeDragDropController {
    return remember { HomeDragDropController() }
}

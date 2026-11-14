package com.example.ui.home

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.components.AppDock
import com.example.core.components.AppIconItem
import com.example.core.components.FolderIconItem
import com.example.core.components.ShellfriClock
import com.example.core.components.ShuffleControlBar
import com.example.domain.model.AppItem
import com.example.domain.model.BlurLevel
import com.example.domain.model.FolderItem
import com.example.domain.repository.IconRepository
import com.example.domain.repository.rememberAppIcon
import com.example.domain.validation.WidgetPlacement
import com.example.ui.theme.ShellDarkAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed class HomeGridCell(val key: String, val slotIndex: Int) {
    data class AppCell(val app: AppItem) : HomeGridCell("app_${app.componentKey}", app.slotIndex)
    data class FolderCell(val folder: FolderItem) : HomeGridCell("folder_${folder.id}", folder.slotIndex)
    data class WidgetCell(val widget: WidgetPlacement, val slot: Int) : HomeGridCell("widget_${widget.appWidgetId}_$slot", slot)
    data class EmptyCell(val pageIndex: Int, val slot: Int) : HomeGridCell("empty_${pageIndex}_$slot", slot)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NormalHomeScreen(
    uiState: HomeUiState,
    iconRepository: IconRepository,
    onAppClick: (AppItem) -> Unit,
    onAppLongClick: (AppItem) -> Unit,
    onFolderClick: (FolderItem) -> Unit,
    onFolderLongClick: (FolderItem) -> Unit,
    onModeToggle: () -> Unit,
    onManualShuffle: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleEditMode: (Boolean) -> Unit,
    onAddPage: () -> Unit,
    onRemovePage: (Int) -> Unit,
    onSetGridColumns: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onMoveApp: (AppItem, Int, Int, (Boolean, String?) -> Unit) -> Unit = { _, _, _, onResult -> onResult(false, null) },
    onCreateFolderFromApps: ((AppItem, AppItem, (Boolean, String?) -> Unit) -> Unit)? = null,
    onAddAppToFolder: ((AppItem, FolderItem, (Boolean, String?) -> Unit) -> Unit)? = null,
    onMoveFolder: ((FolderItem, Int, Int, (Boolean, String?) -> Unit) -> Unit)? = null,
    onDragRejected: (String) -> Unit = {},
    edgeHoverDelayMs: Long = 450L,
    folderHoverDelayMs: Long = 350L
) {
    val pageCount = uiState.pageCount.coerceAtLeast(1)
    val pagerState = rememberPagerState(
        initialPage = uiState.defaultPageIndex.coerceIn(0, pageCount - 1),
        pageCount = { pageCount }
    )
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current

    val dragController = rememberHomeDragDropController()
    var screenRootOffset by remember { mutableStateOf(Offset.Zero) }
    var pagerWrapperCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val latestOnAppLongClick by rememberUpdatedState(onAppLongClick)
    val latestOnFolderLongClick by rememberUpdatedState(onFolderLongClick)
    val latestOnToggleEditMode by rememberUpdatedState(onToggleEditMode)
    val latestOnMoveApp by rememberUpdatedState(onMoveApp)
    val latestOnCreateFolderFromApps by rememberUpdatedState(onCreateFolderFromApps)
    val latestOnAddAppToFolder by rememberUpdatedState(onAddAppToFolder)
    val latestOnMoveFolder by rememberUpdatedState(onMoveFolder)
    val latestOnDragRejected by rememberUpdatedState(onDragRejected)

    dragController.edgeZoneWidthPx = with(density) { 48.dp.toPx() }
    dragController.onCommitMove = { app, targetPage, targetSlot, onResult ->
        latestOnMoveApp(app, targetPage, targetSlot, onResult)
    }
    dragController.onCreateFolderFromApps = if (onCreateFolderFromApps != null) {
        { dragged, target, onResult ->
            latestOnCreateFolderFromApps?.invoke(dragged, target, onResult) ?: onResult(false, null)
        }
    } else null
    dragController.onAddAppToFolder = if (onAddAppToFolder != null) {
        { dragged, folder, onResult ->
            latestOnAddAppToFolder?.invoke(dragged, folder, onResult) ?: onResult(false, null)
        }
    } else null
    dragController.onMoveFolder = if (onMoveFolder != null) {
        { folder, targetPage, targetSlot, onResult ->
            latestOnMoveFolder?.invoke(folder, targetPage, targetSlot, onResult) ?: onResult(false, null)
        }
    } else null
    dragController.onRestorePage = { targetPage ->
        coroutineScope.launch {
            val safeTarget = targetPage.coerceIn(0, (uiState.pageCount - 1).coerceAtLeast(0))
            if (pagerState.currentPage != safeTarget) {
                pagerState.animateScrollToPage(safeTarget)
            }
            dragController.onPageSwitched(safeTarget)
        }
    }
    dragController.onDragRejected = { reason ->
        latestOnDragRejected(reason)
    }
    dragController.syncWorkspaceState(
        currentPage = pagerState.currentPage,
        pageCount = pageCount,
        gridColumns = uiState.gridColumns,
        occupancyOption = uiState.occupancyOption,
        allApps = uiState.allApps,
        homeApps = uiState.homeApps,
        folders = uiState.folders,
        widgets = uiState.widgets
    )

    // Cancel an in-progress drag cleanly on system Back without losing edit mode
    BackHandler(enabled = dragController.isDragging) {
        dragController.cancelDragAndRestorePage()
    }

    // Deliberate edge-hover cross-page switching
    LaunchedEffect(dragController.isDragging, dragController.edgeHoverDirection, pagerState.currentPage, pageCount) {
        val direction = dragController.edgeHoverDirection
        if (!dragController.isDragging || direction == 0) return@LaunchedEffect
        val targetPage = (pagerState.currentPage + direction).coerceIn(0, pageCount - 1)
        if (targetPage == pagerState.currentPage) return@LaunchedEffect

        delay(edgeHoverDelayMs)
        if (dragController.isDragging && dragController.edgeHoverDirection == direction) {
            pagerState.animateScrollToPage(targetPage)
            dragController.onPageSwitched(targetPage)
        }
    }

    // Deliberate hover threshold for creating a folder or adding to an existing folder
    LaunchedEffect(
        dragController.isDragging,
        dragController.hoveredTarget,
        dragController.hoveredTargetAppForFolder,
        dragController.hoveredTargetFolder
    ) {
        if (!dragController.isDragging || dragController.draggedApp == null) return@LaunchedEffect
        val hasFolderCandidate = dragController.hoveredTargetAppForFolder != null || dragController.hoveredTargetFolder != null
        if (!hasFolderCandidate || dragController.hoveredTarget == null) return@LaunchedEffect
        val expectedTarget = dragController.hoveredTarget
        delay(folderHoverDelayMs)
        if (dragController.isDragging && dragController.hoveredTarget == expectedTarget) {
            dragController.armFolderActionForCurrentTarget()
        }
    }

    val effectiveHomeApps = dragController.effectiveHomeApps()
    val effectiveFolders = dragController.effectiveFolders()
    val iconSizeDp = when (uiState.iconSize) {
        "SMALL" -> 46.dp
        "LARGE" -> 60.dp
        else -> 54.dp
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned { coords ->
                if (coords.isAttached) {
                    screenRootOffset = coords.positionInRoot()
                }
            }
            .testTag("normal_home_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Clock & Status Section (dragging here cancels safely)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                ShellfriClock(
                    shuffledAppCount = uiState.shuffledApps.size,
                    isShuffleActive = uiState.isShuffleEnabled,
                    onClockClick = onOpenSettings
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Fixed-footprint control area: Edit Workspace / Drag Cancel cleanly overlays
                // ShuffleControlBar so entering edit mode never shifts the home grid under the pointer.
                Box(modifier = Modifier.fillMaxWidth()) {
                    ShuffleControlBar(
                        homeMode = uiState.homeMode,
                        isShuffleEnabled = uiState.isShuffleEnabled,
                        shuffleInterval = uiState.shuffleInterval,
                        isShuffling = uiState.isShufflingAnimation,
                        onModeToggle = onModeToggle,
                        onManualShuffle = onManualShuffle,
                        onOpenSettings = onOpenSettings
                    )

                    androidx.compose.animation.AnimatedVisibility(
                        visible = uiState.isEditMode || dragController.isDragging,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        val isOutside = dragController.isDragging && dragController.isPointerOutsideGrid
                        val bannerBorderColor by animateColorAsState(
                            targetValue = if (isOutside) MaterialTheme.colorScheme.error else ShellDarkAccent,
                            animationSpec = spring(stiffness = 450f),
                            label = "edit_banner_border"
                        )
                        val bannerBgColor by animateColorAsState(
                            targetValue = if (isOutside) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            animationSpec = spring(stiffness = 450f),
                            label = "edit_banner_bg"
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(bannerBgColor)
                                .border(1.dp, bannerBorderColor, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("edit_mode_banner"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when {
                                    isOutside -> "RELEASE TO CANCEL"
                                    dragController.isDragging && dragController.isFolderActionArmed &&
                                        dragController.hoveredDropAction == DropActionType.CREATE_FOLDER -> "RELEASE TO CREATE FOLDER"
                                    dragController.isDragging && dragController.isFolderActionArmed &&
                                        dragController.hoveredDropAction == DropActionType.ADD_TO_FOLDER -> "RELEASE TO ADD TO FOLDER"
                                    dragController.isDragging -> "PAGE ${pagerState.currentPage + 1} • DRAG TO SLOT"
                                    else -> "EDIT WORKSPACE"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = if (isOutside) MaterialTheme.colorScheme.error else ShellDarkAccent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .then(if (dragController.isDragging) Modifier.testTag("drag_cancel_zone") else Modifier)
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                IconButton(
                                    onClick = onAddPage,
                                    enabled = !dragController.isDragging,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .testTag("edit_add_page_button")
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Add Page",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                if (pageCount > 1) {
                                    IconButton(
                                        onClick = { onRemovePage(pagerState.currentPage) },
                                        enabled = !dragController.isDragging,
                                        modifier = Modifier
                                            .size(30.dp)
                                            .testTag("edit_remove_page_button")
                                    ) {
                                        Icon(
                                            Icons.Default.Remove,
                                            contentDescription = "Remove Page",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_SET_WALLPAPER)
                                        context.startActivity(Intent.createChooser(intent, "Set Wallpaper"))
                                    },
                                    enabled = !dragController.isDragging,
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Image,
                                        contentDescription = "Wallpaper",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        val nextCols = if (uiState.gridColumns >= 6) 3 else uiState.gridColumns + 1
                                        onSetGridColumns(nextCols)
                                    },
                                    enabled = !dragController.isDragging,
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        Icons.Default.GridOn,
                                        contentDescription = "Grid Columns",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                TextButton(
                                    onClick = {
                                        dragController.cancelDragAndRestorePage()
                                        onToggleEditMode(false)
                                    },
                                    modifier = Modifier.testTag("edit_mode_done_button")
                                ) {
                                    Text(
                                        "DONE",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = ShellDarkAccent
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Horizontal Home Pages with persistent container-level long-press + drag tracking
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            pagerWrapperCoords = coords
                            dragController.pagerBoundsInRoot = coords.boundsInRoot()
                        }
                    }
                    .pointerInput(dragController) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val activePage = dragController.currentPage
                            val startRootPos = pagerWrapperCoords?.localToRoot(down.position) ?: down.position
                            val hitSlot = dragController.findSlotAt(activePage, startRootPos)
                            val hitApp = hitSlot?.let { dragController.findHomeAppAt(activePage, it) }
                            val hitFolder = if (hitApp == null) {
                                hitSlot?.let { dragController.findFolderAt(activePage, it) }
                            } else null

                            val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                            val touchSlop = viewConfiguration.touchSlop
                            var longPressTriggered = false
                            var gestureEndedEarly = false
                            var currentPos = down.position

                            try {
                                withTimeout(longPressTimeout) {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Main)
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: event.changes.firstOrNull()
                                        if (change == null || !change.pressed || change.isConsumed) {
                                            gestureEndedEarly = true
                                            break
                                        }
                                        currentPos = change.position
                                        if ((currentPos - down.position).getDistance() > touchSlop) {
                                            gestureEndedEarly = true
                                            break
                                        }
                                    }
                                }
                            } catch (_: PointerEventTimeoutCancellationException) {
                                longPressTriggered = !gestureEndedEarly
                            }

                            if (!longPressTriggered) {
                                return@awaitEachGesture
                            }

                            if (hitApp != null) {
                                if (hitApp.isFixed) {
                                    var movedPastSlop = false
                                    var wasCancelled = false
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: event.changes.firstOrNull()
                                        if (change == null) {
                                            wasCancelled = true
                                            break
                                        }
                                        if ((change.position - down.position).getDistance() > touchSlop) {
                                            movedPastSlop = true
                                        }
                                        if (!change.pressed || change.changedToUpIgnoreConsumed()) {
                                            change.consume()
                                            break
                                        }
                                        change.consume()
                                    }
                                    if (!wasCancelled) {
                                        if (movedPastSlop) {
                                            latestOnDragRejected("${hitApp.label} is fixed and cannot be moved")
                                        } else {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            latestOnAppLongClick(hitApp)
                                        }
                                    }
                                    return@awaitEachGesture
                                }

                                // Movable Home App: enter edit mode & start lightweight drag tracking
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                latestOnToggleEditMode(true)
                                val initialRoot = pagerWrapperCoords?.localToRoot(currentPos) ?: currentPos
                                val anchorOffset = dragController.computeAnchorOffset(
                                    pageIndex = hitApp.pageIndex,
                                    slotIndex = hitApp.slotIndex,
                                    pointerRoot = initialRoot
                                )
                                dragController.startDrag(
                                    app = hitApp,
                                    initialPointerInRoot = initialRoot,
                                    dragAnchorOffsetInSlot = anchorOffset
                                )

                                var movedPastSlop = false
                                var wasCancelled = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: event.changes.firstOrNull()
                                    if (change == null) {
                                        wasCancelled = true
                                        break
                                    }
                                    val pos = change.position
                                    if ((pos - down.position).getDistance() > touchSlop) {
                                        movedPastSlop = true
                                    }
                                    if (!change.pressed || change.changedToUpIgnoreConsumed()) {
                                        change.consume()
                                        break
                                    }
                                    change.consume()
                                    val rootPos = pagerWrapperCoords?.localToRoot(pos) ?: pos
                                    dragController.updateDragPointer(rootPos)
                                }

                                if (wasCancelled) {
                                    dragController.cancelDragAndRestorePage()
                                } else if (!movedPastSlop) {
                                    dragController.cancelDragWithoutMessage()
                                    latestOnAppLongClick(hitApp)
                                } else {
                                    dragController.commitOrCancelOnDrop()
                                }
                            } else if (hitFolder != null) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (onMoveFolder == null) {
                                    latestOnFolderLongClick(hitFolder)
                                    return@awaitEachGesture
                                }

                                latestOnToggleEditMode(true)
                                val initialRoot = pagerWrapperCoords?.localToRoot(currentPos) ?: currentPos
                                val anchorOffset = dragController.computeAnchorOffset(
                                    pageIndex = hitFolder.pageIndex,
                                    slotIndex = hitFolder.slotIndex,
                                    pointerRoot = initialRoot
                                )
                                dragController.startFolderDrag(
                                    folder = hitFolder,
                                    initialPointerInRoot = initialRoot,
                                    dragAnchorOffsetInSlot = anchorOffset
                                )

                                var movedPastSlop = false
                                var wasCancelled = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: event.changes.firstOrNull()
                                    if (change == null) {
                                        wasCancelled = true
                                        break
                                    }
                                    val pos = change.position
                                    if ((pos - down.position).getDistance() > touchSlop) {
                                        movedPastSlop = true
                                    }
                                    if (!change.pressed || change.changedToUpIgnoreConsumed()) {
                                        change.consume()
                                        break
                                    }
                                    change.consume()
                                    val rootPos = pagerWrapperCoords?.localToRoot(pos) ?: pos
                                    dragController.updateDragPointer(rootPos)
                                }

                                if (wasCancelled) {
                                    dragController.cancelDragAndRestorePage()
                                } else if (!movedPastSlop) {
                                    dragController.cancelDragWithoutMessage()
                                    latestOnFolderLongClick(hitFolder)
                                } else {
                                    dragController.commitOrCancelOnDrop()
                                }
                            } else {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                latestOnToggleEditMode(true)
                            }
                        }
                    }
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !dragController.isDragging,
                    modifier = Modifier.fillMaxSize()
                ) { pageIndex ->
                    val pageApps = remember(effectiveHomeApps, pageIndex) {
                        effectiveHomeApps.filter { it.pageIndex == pageIndex }.sortedBy { it.slotIndex }
                    }
                    val pageFolders = remember(effectiveFolders, pageIndex) {
                        effectiveFolders.filter { it.pageIndex == pageIndex }.sortedBy { it.slotIndex }
                    }
                    val pageWidgets = remember(uiState.widgets, pageIndex) {
                        uiState.widgets.filter { it.pageIndex == pageIndex }
                    }
                    val isEditOrDragging = uiState.isEditMode || dragController.isDragging
                    val gridCells = remember(
                        pageIndex,
                        pageApps,
                        pageFolders,
                        pageWidgets,
                        uiState.gridColumns,
                        isEditOrDragging
                    ) {
                        buildPageGridCells(
                            pageIndex = pageIndex,
                            pageApps = pageApps,
                            pageFolders = pageFolders,
                            pageWidgets = pageWidgets,
                            gridColumns = uiState.gridColumns,
                            includeTrailingEmptySlots = isEditOrDragging
                        )
                    }

                    DisposableEffect(pageIndex) {
                        onDispose {
                            dragController.clearPageBounds(pageIndex)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        if (gridCells.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "PAGE ${pageIndex + 1} EMPTY",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            letterSpacing = 1.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Swipe up for drawer or long press to configure",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(uiState.gridColumns),
                                userScrollEnabled = !dragController.isDragging,
                                contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(uiState.gridSpacing.verticalDp.dp),
                                horizontalArrangement = Arrangement.spacedBy(
                                    uiState.gridSpacing.horizontalDp.dp,
                                    Alignment.CenterHorizontally
                                ),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onGloballyPositioned { coords ->
                                        if (coords.isAttached) {
                                            dragController.registerGridBounds(pageIndex, coords.boundsInRoot())
                                        }
                                    }
                                    .testTag("home_grid_page_$pageIndex")
                            ) {
                                items(gridCells, key = { it.key }) { cell ->
                                    val hovered = dragController.hoveredTarget
                                    val isSlotHovered = dragController.isDragging &&
                                        hovered != null &&
                                        hovered.pageIndex == pageIndex &&
                                        hovered.slotIndex == cell.slotIndex
                                    val isCreateFolderArmedOnSlot = isSlotHovered &&
                                        dragController.isFolderActionArmed &&
                                        dragController.hoveredDropAction == DropActionType.CREATE_FOLDER
                                    val isAddToFolderArmedOnSlot = isSlotHovered &&
                                        dragController.isFolderActionArmed &&
                                        dragController.hoveredDropAction == DropActionType.ADD_TO_FOLDER
                                    val isFolderCandidateOnSlot = isSlotHovered &&
                                        (dragController.hoveredTargetAppForFolder != null || dragController.hoveredTargetFolder != null)
                                    val highlightColor = when {
                                        isCreateFolderArmedOnSlot || isAddToFolderArmedOnSlot -> ShellDarkAccent
                                        isSlotHovered && dragController.isHoveredTargetValid -> ShellDarkAccent
                                        isSlotHovered && !dragController.isHoveredTargetValid -> MaterialTheme.colorScheme.error
                                        else -> Color.Transparent
                                    }

                                    Box(
                                        modifier = Modifier
                                            .animateItem()
                                            .onGloballyPositioned { coords ->
                                                if (coords.isAttached) {
                                                    dragController.registerSlotBounds(
                                                        pageIndex = pageIndex,
                                                        slotIndex = cell.slotIndex,
                                                        bounds = coords.boundsInRoot()
                                                    )
                                                }
                                            }
                                            .clip(RoundedCornerShape(16.dp))
                                            .then(
                                                if (highlightColor != Color.Transparent) {
                                                    Modifier
                                                        .background(
                                                            highlightColor.copy(
                                                                alpha = if (isCreateFolderArmedOnSlot || isAddToFolderArmedOnSlot) 0.24f else 0.12f
                                                            )
                                                        )
                                                        .border(
                                                            width = if (isCreateFolderArmedOnSlot || isAddToFolderArmedOnSlot) 2.dp else 1.5.dp,
                                                            color = highlightColor,
                                                            shape = RoundedCornerShape(16.dp)
                                                        )
                                                } else {
                                                    Modifier
                                                }
                                            )
                                            .then(
                                                when {
                                                    isCreateFolderArmedOnSlot -> Modifier.testTag("folder_create_preview")
                                                    isAddToFolderArmedOnSlot -> Modifier.testTag("folder_add_preview")
                                                    isFolderCandidateOnSlot -> Modifier.testTag("folder_candidate_highlight")
                                                    else -> Modifier
                                                }
                                            )
                                            .testTag("home_slot_${pageIndex}_${cell.slotIndex}"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        when (cell) {
                                            is HomeGridCell.FolderCell -> {
                                                val appsInFolder = remember(uiState.allApps, cell.folder.id) {
                                                    uiState.allApps
                                                        .filter { it.folderId == cell.folder.id }
                                                        .sortedWith(
                                                            compareBy<AppItem> { if (it.folderRank >= 0) it.folderRank else Int.MAX_VALUE }
                                                                .thenBy { it.label.lowercase() }
                                                                .thenBy { it.componentKey }
                                                        )
                                                }
                                                val isDraggedFolder = dragController.draggedFolder?.id == cell.folder.id
                                                FolderIconItem(
                                                    folder = cell.folder,
                                                    appsInFolder = appsInFolder,
                                                    iconRepository = iconRepository,
                                                    iconSize = iconSizeDp,
                                                    onClick = {},
                                                    onLongClick = {},
                                                    showLabel = uiState.showLabels,
                                                    enableBuiltInClick = false,
                                                    isDraggedSource = isDraggedFolder,
                                                    isDropTargetArmed = isAddToFolderArmedOnSlot,
                                                    modifier = Modifier.clickable(enabled = !dragController.isDragging) {
                                                        onFolderClick(cell.folder)
                                                    }
                                                )
                                            }

                                            is HomeGridCell.AppCell -> {
                                                val iconBitmap = rememberAppIcon(
                                                    iconRepository,
                                                    cell.app.packageName,
                                                    cell.app.activityName
                                                )
                                                val isDraggedItem = dragController.draggedApp?.componentKey == cell.app.componentKey
                                                AppIconItem(
                                                    app = cell.app,
                                                    iconBitmap = iconBitmap,
                                                    onClick = {},
                                                    onLongClick = {},
                                                    iconSize = iconSizeDp,
                                                    isShuffling = uiState.isShufflingAnimation,
                                                    showLabel = uiState.showLabels,
                                                    blurLevel = if (uiState.blurOnShuffle && uiState.isShufflingAnimation) {
                                                        uiState.blurLevel
                                                    } else if (uiState.blurOnDistracting && cell.app.isShuffled) {
                                                        uiState.blurLevel
                                                    } else {
                                                        BlurLevel.DISABLED
                                                    },
                                                    enableBuiltInClick = false,
                                                    isDraggedSource = isDraggedItem,
                                                    modifier = Modifier.clickable(enabled = !dragController.isDragging) {
                                                        if (uiState.isEditMode) {
                                                            onAppLongClick(cell.app)
                                                        } else {
                                                            onAppClick(cell.app)
                                                        }
                                                    }
                                                )
                                            }

                                            is HomeGridCell.WidgetCell -> {
                                                Column(
                                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(iconSizeDp)
                                                            .clip(RoundedCornerShape(16.dp))
                                                            .background(
                                                                MaterialTheme.colorScheme.surfaceVariant.copy(
                                                                    alpha = if (isEditOrDragging) 0.5f else 0.15f
                                                                )
                                                            )
                                                            .border(
                                                                width = 1.dp,
                                                                color = MaterialTheme.colorScheme.outline.copy(
                                                                    alpha = if (isEditOrDragging) 0.7f else 0.2f
                                                                ),
                                                                shape = RoundedCornerShape(16.dp)
                                                            ),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Widgets,
                                                            contentDescription = "Widget space",
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                    if (uiState.showLabels) {
                                                        Spacer(modifier = Modifier.height(6.dp))
                                                        Text(
                                                            text = "Widget",
                                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp),
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                            }

                                            is HomeGridCell.EmptyCell -> {
                                                Column(
                                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(iconSizeDp)
                                                            .clip(RoundedCornerShape(16.dp))
                                                            .then(
                                                                if (isEditOrDragging) {
                                                                    Modifier
                                                                        .background(
                                                                            MaterialTheme.colorScheme.surfaceVariant.copy(
                                                                                alpha = if (isSlotHovered) 0.35f else 0.14f
                                                                            )
                                                                        )
                                                                        .border(
                                                                            width = 1.dp,
                                                                            color = if (isSlotHovered && dragController.isHoveredTargetValid) {
                                                                                ShellDarkAccent
                                                                            } else {
                                                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
                                                                            },
                                                                            shape = RoundedCornerShape(16.dp)
                                                                        )
                                                                } else {
                                                                    Modifier
                                                                }
                                                            )
                                                    )
                                                    if (uiState.showLabels) {
                                                        Spacer(modifier = Modifier.height(6.dp))
                                                        Spacer(modifier = Modifier.height(15.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Page edge visual drop/switch indicators during drag
                if (dragController.isDragging && pageCount > 1) {
                    if (pagerState.currentPage > 0) {
                        val isActive = dragController.edgeHoverDirection == -1
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight(0.65f)
                                .width(28.dp)
                                .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                                .background(
                                    if (isActive) ShellDarkAccent.copy(alpha = 0.35f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                )
                                .border(
                                    width = if (isActive) 1.5.dp else 1.dp,
                                    color = if (isActive) ShellDarkAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
                                )
                                .testTag("page_edge_prev"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronLeft,
                                contentDescription = "Previous Page",
                                tint = if (isActive) ShellDarkAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (pagerState.currentPage < pageCount - 1) {
                        val isActive = dragController.edgeHoverDirection == 1
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight(0.65f)
                                .width(28.dp)
                                .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                                .background(
                                    if (isActive) ShellDarkAccent.copy(alpha = 0.35f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                )
                                .border(
                                    width = if (isActive) 1.5.dp else 1.dp,
                                    color = if (isActive) ShellDarkAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
                                )
                                .testTag("page_edge_next"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Next Page",
                                tint = if (isActive) ShellDarkAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Page Indicator Dots
            if (pageCount > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag("page_indicator_row"),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    (0 until pageCount).forEach { idx ->
                        val isSelected = pagerState.currentPage == idx
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (isSelected) 8.dp else 5.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) ShellDarkAccent else MaterialTheme.colorScheme.outline
                                )
                                .clickable(enabled = !dragController.isDragging) {
                                    coroutineScope.launch { pagerState.animateScrollToPage(idx) }
                                }
                                .testTag("page_indicator_dot_$idx")
                        )
                    }
                }
            }

            // Persistent App Dock
            AppDock(
                dockApps = uiState.dockApps,
                iconRepository = iconRepository,
                onAppClick = onAppClick,
                onAppLongClick = onAppLongClick,
                onOpenDrawer = onOpenDrawer,
                dockCount = uiState.dockCount
            )
        }

        // Lightweight floating drag preview: reads pointerInRoot strictly in graphicsLayer
        // to avoid full-screen or grid recompositions on pointer movement.
        val draggedApp = dragController.draggedApp
        val draggedFolder = dragController.draggedFolder
        if (draggedApp != null) {
            val previewIconBitmap = rememberAppIcon(
                iconRepository,
                draggedApp.packageName,
                draggedApp.activityName
            )
            val isInvalidZone = dragController.isPointerOutsideGrid || !dragController.isHoveredTargetValid
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        val pointer = dragController.pointerInRoot
                        val anchor = dragController.dragAnchorOffset
                        translationX = pointer.x - screenRootOffset.x - anchor.x
                        translationY = pointer.y - screenRootOffset.y - anchor.y
                    }
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (isInvalidZone) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                        }
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (isInvalidZone) MaterialTheme.colorScheme.error else ShellDarkAccent,
                        shape = RoundedCornerShape(18.dp)
                    )
                    .testTag("drag_preview")
            ) {
                AppIconItem(
                    app = draggedApp,
                    iconBitmap = previewIconBitmap,
                    onClick = {},
                    iconSize = iconSizeDp,
                    showLabel = uiState.showLabels,
                    enableBuiltInClick = false,
                    isDragPreview = true
                )
            }
        } else if (draggedFolder != null) {
            val draggedFolderApps = remember(uiState.allApps, draggedFolder.id) {
                uiState.allApps
                    .filter { it.folderId == draggedFolder.id }
                    .sortedWith(
                        compareBy<AppItem> { if (it.folderRank >= 0) it.folderRank else Int.MAX_VALUE }
                            .thenBy { it.label.lowercase() }
                    )
            }
            val isInvalidZone = dragController.isPointerOutsideGrid || !dragController.isHoveredTargetValid
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        val pointer = dragController.pointerInRoot
                        val anchor = dragController.dragAnchorOffset
                        translationX = pointer.x - screenRootOffset.x - anchor.x
                        translationY = pointer.y - screenRootOffset.y - anchor.y
                    }
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (isInvalidZone) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                        }
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (isInvalidZone) MaterialTheme.colorScheme.error else ShellDarkAccent,
                        shape = RoundedCornerShape(18.dp)
                    )
                    .testTag("folder_drag_preview")
            ) {
                FolderIconItem(
                    folder = draggedFolder,
                    appsInFolder = draggedFolderApps,
                    iconRepository = iconRepository,
                    onClick = {},
                    iconSize = iconSizeDp,
                    showLabel = uiState.showLabels,
                    enableBuiltInClick = false,
                    isDragPreview = true
                )
            }
        }
    }
}

private fun buildPageGridCells(
    pageIndex: Int,
    pageApps: List<AppItem>,
    pageFolders: List<FolderItem>,
    pageWidgets: List<WidgetPlacement>,
    gridColumns: Int,
    includeTrailingEmptySlots: Boolean
): List<HomeGridCell> {
    val cols = gridColumns.coerceAtLeast(1)
    val appBySlot = pageApps.associateBy { it.slotIndex }
    val folderBySlot = pageFolders.associateBy { it.slotIndex }
    val widgetBySlot = mutableMapOf<Int, WidgetPlacement>()
    for (widget in pageWidgets) {
        for ((wPage, wSlot) in widget.occupiedCoords()) {
            if (wPage == pageIndex && wSlot >= 0) {
                widgetBySlot[wSlot] = widget
            }
        }
    }

    val maxOccupied = maxOf(
        pageApps.maxOfOrNull { it.slotIndex } ?: -1,
        pageFolders.maxOfOrNull { it.slotIndex } ?: -1,
        widgetBySlot.keys.maxOfOrNull { it } ?: -1
    )

    if (!includeTrailingEmptySlots && maxOccupied < 0) {
        return emptyList()
    }

    val slotCount = if (includeTrailingEmptySlots) {
        val occupiedRows = if (maxOccupied < 0) 0 else (maxOccupied / cols) + 1
        maxOf(4, occupiedRows + 1) * cols
    } else {
        maxOccupied + 1
    }

    return (0 until slotCount).map { slot ->
        folderBySlot[slot]?.let { HomeGridCell.FolderCell(it) }
            ?: appBySlot[slot]?.let { HomeGridCell.AppCell(it) }
            ?: widgetBySlot[slot]?.let { HomeGridCell.WidgetCell(it, slot) }
            ?: HomeGridCell.EmptyCell(pageIndex, slot)
    }
}

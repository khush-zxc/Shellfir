package com.example.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Output
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.components.AppIconItem
import com.example.domain.model.AppItem
import com.example.domain.model.FolderItem
import com.example.domain.repository.AppRepository
import com.example.domain.repository.IconRepository
import com.example.domain.repository.rememberAppIcon
import com.example.ui.theme.ShellDarkAccent
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderDialog(
    folder: FolderItem,
    appsInFolder: List<AppItem>,
    allApps: List<AppItem>,
    iconRepository: IconRepository,
    onAppClick: (AppItem) -> Unit,
    onRemoveAppFromFolder: (AppItem) -> Unit,
    onAddAppToFolder: (AppItem) -> Unit,
    onRenameFolder: (String, (Boolean, String?) -> Unit) -> Unit,
    onReorderApps: (List<AppItem>, (Boolean, String?) -> Unit) -> Unit = { _, onResult -> onResult(true, null) },
    onDeleteFolder: () -> Unit,
    onDismiss: () -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    val orderedFromState = remember(folder.id, appsInFolder) {
        appsInFolder.sortedWith(
            compareBy<AppItem> { if (it.folderRank >= 0) it.folderRank else Int.MAX_VALUE }
                .thenBy { it.label.lowercase() }
                .thenBy { it.componentKey }
        )
    }

    var localOrder by remember(folder.id, orderedFromState) { mutableStateOf(orderedFromState) }
    var isRenaming by remember(folder.id) { mutableStateOf(false) }
    var renameInput by remember(folder.id, folder.name) { mutableStateOf(folder.name) }
    var renameError by remember(folder.id) { mutableStateOf<String?>(null) }
    var isAddingApps by remember(folder.id) { mutableStateOf(false) }
    var isManageMode by remember(folder.id) { mutableStateOf(false) }

    // Internal drag-and-drop state
    var draggingApp by remember(folder.id) { mutableStateOf<AppItem?>(null) }
    var dragOriginalOrder by remember(folder.id) { mutableStateOf<List<AppItem>>(emptyList()) }
    var dragPointerInRoot by remember(folder.id) { mutableStateOf(Offset.Zero) }
    var dragAnchorOffset by remember(folder.id) { mutableStateOf(Offset.Zero) }
    var hoveredReorderSlot by remember(folder.id) { mutableStateOf<Int?>(null) }
    var isHoveringRemoveZone by remember(folder.id) { mutableStateOf(false) }

    var containerRootOffset by remember { mutableStateOf(Offset.Zero) }
    var gridBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    var removeZoneBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    val itemBoundsByKey = remember { mutableStateMapOf<String, Rect>() }

    val latestOnReorderApps by rememberUpdatedState(onReorderApps)
    val latestOnRemoveApp by rememberUpdatedState(onRemoveAppFromFolder)
    val latestOnRenameFolder by rememberUpdatedState(onRenameFolder)

    LaunchedEffect(folder.name, isRenaming) {
        if (!isRenaming) {
            renameInput = folder.name
            renameError = null
        }
    }

    // Open animation state
    val visibilityState = remember(folder.id) {
        MutableTransitionState(false).apply { targetState = true }
    }

    BackHandler(enabled = draggingApp != null || isRenaming || isAddingApps) {
        when {
            draggingApp != null -> {
                localOrder = dragOriginalOrder
                draggingApp = null
                dragOriginalOrder = emptyList()
                hoveredReorderSlot = null
                isHoveringRemoveZone = false
            }
            isRenaming -> {
                isRenaming = false
                renameInput = folder.name
                renameError = null
            }
            isAddingApps -> {
                isAddingApps = false
            }
        }
    }

    BasicAlertDialog(
        onDismissRequest = {
            if (draggingApp == null) {
                onDismiss()
            }
        }
    ) {
        AnimatedVisibility(
            visibleState = visibilityState,
            enter = fadeIn() + scaleIn(initialScale = 0.94f),
            exit = fadeOut() + scaleOut(targetScale = 0.94f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            containerRootOffset = coords.positionInRoot()
                        }
                    }
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .testTag("folder_dialog_${folder.id}")
                        .pointerInput(folder.id, isRenaming, isAddingApps, localOrder.map { it.componentKey }) {
                            if (isRenaming || isAddingApps || localOrder.isEmpty()) return@pointerInput

                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val downInRoot = containerRootOffset + down.position
                                val startItems = localOrder
                                val hitIndex = startItems.indexOfFirst { item ->
                                    itemBoundsByKey[item.componentKey]?.contains(downInRoot) == true
                                }
                                if (hitIndex < 0) return@awaitEachGesture

                                val hitApp = startItems[hitIndex]
                                val hitRect = itemBoundsByKey[hitApp.componentKey] ?: return@awaitEachGesture

                                // Wait for long press without exceeding touch slop
                                val longPressTimedOut = try {
                                    withTimeout(viewConfiguration.longPressTimeoutMillis) {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                                ?: return@withTimeout false
                                            if (change.changedToUpIgnoreConsumed() || !change.pressed) {
                                                return@withTimeout false
                                            }
                                            val movedDist = hypot(
                                                change.position.x - down.position.x,
                                                change.position.y - down.position.y
                                            )
                                            if (movedDist > viewConfiguration.touchSlop) {
                                                return@withTimeout false
                                            }
                                        }
                                        @Suppress("UNREACHABLE_CODE")
                                        false
                                    }
                                } catch (_: PointerEventTimeoutCancellationException) {
                                    true
                                }

                                if (!longPressTimedOut) return@awaitEachGesture

                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)

                                // Snapshot stationary slot rects at drag start to avoid target oscillation
                                val slotRects = startItems.mapIndexedNotNull { index, item ->
                                    itemBoundsByKey[item.componentKey]?.let { index to it }
                                }

                                dragOriginalOrder = startItems
                                draggingApp = hitApp
                                dragPointerInRoot = downInRoot
                                dragAnchorOffset = Offset(
                                    x = (downInRoot.x - hitRect.left).coerceIn(0f, hitRect.width),
                                    y = (downInRoot.y - hitRect.top).coerceIn(0f, hitRect.height)
                                )
                                hoveredReorderSlot = hitIndex
                                isHoveringRemoveZone = false
                                var isOutsideZones = false
                                var completedNaturally = false

                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()

                                    if (change.changedToUpIgnoreConsumed() || !change.pressed) {
                                        completedNaturally = true
                                        break
                                    }

                                    val currentRoot = containerRootOffset + change.position
                                    dragPointerInRoot = currentRoot

                                    val overRemove = removeZoneBoundsInRoot != Rect.Zero &&
                                        removeZoneBoundsInRoot.contains(currentRoot)

                                    if (overRemove) {
                                        isHoveringRemoveZone = true
                                        hoveredReorderSlot = null
                                        isOutsideZones = false
                                    } else {
                                        isHoveringRemoveZone = false
                                        val matchedSlot = slotRects.firstOrNull { (_, rect) ->
                                            rect.contains(currentRoot)
                                        }?.first
                                        val inGrid = gridBoundsInRoot != Rect.Zero && gridBoundsInRoot.contains(currentRoot)

                                        if (matchedSlot != null) {
                                            isOutsideZones = false
                                            hoveredReorderSlot = matchedSlot
                                            val currentIdx = localOrder.indexOfFirst { it.componentKey == hitApp.componentKey }
                                            if (currentIdx >= 0 && currentIdx != matchedSlot && matchedSlot < localOrder.size) {
                                                val mutable = localOrder.toMutableList()
                                                val moved = mutable.removeAt(currentIdx)
                                                mutable.add(matchedSlot, moved)
                                                localOrder = mutable
                                            }
                                        } else if (inGrid) {
                                            isOutsideZones = false
                                        } else {
                                            hoveredReorderSlot = null
                                            isOutsideZones = true
                                        }
                                    }
                                }

                                val capturedRemove = isHoveringRemoveZone
                                val originalList = dragOriginalOrder
                                val finalList = localOrder

                                draggingApp = null
                                dragOriginalOrder = emptyList()
                                hoveredReorderSlot = null
                                isHoveringRemoveZone = false

                                if (!completedNaturally) {
                                    localOrder = originalList
                                    return@awaitEachGesture
                                }

                                when {
                                    capturedRemove -> {
                                        localOrder = originalList
                                        latestOnRemoveApp(hitApp)
                                    }
                                    isOutsideZones -> {
                                        localOrder = originalList
                                    }
                                    finalList.map { it.componentKey } != originalList.map { it.componentKey } -> {
                                        latestOnReorderApps(finalList) { success, _ ->
                                            if (!success) {
                                                localOrder = originalList
                                            }
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Folder Title Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isRenaming) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        OutlinedTextField(
                                            value = renameInput,
                                            onValueChange = {
                                                renameInput = it
                                                renameError = null
                                            },
                                            singleLine = true,
                                            isError = renameError != null,
                                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = ShellDarkAccent,
                                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("folder_rename_input")
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        TextButton(
                                            onClick = {
                                                val validation = AppRepository.normalizeAndValidateFolderName(renameInput)
                                                val normalized = validation.getOrNull()
                                                if (normalized == null) {
                                                    renameError = validation.exceptionOrNull()?.message
                                                        ?: "Folder name cannot be empty"
                                                } else {
                                                    latestOnRenameFolder(normalized) { success, errorMsg ->
                                                        if (success) {
                                                            isRenaming = false
                                                            renameError = null
                                                        } else {
                                                            renameError = errorMsg ?: "Unable to rename folder"
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                                .testTag("folder_rename_save")
                                        ) {
                                            Text(
                                                text = "SAVE",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                                ),
                                                color = ShellDarkAccent
                                            )
                                        }
                                        TextButton(
                                            onClick = {
                                                isRenaming = false
                                                renameInput = folder.name
                                                renameError = null
                                            },
                                            modifier = Modifier
                                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                                .testTag("folder_rename_cancel")
                                        ) {
                                            Text(
                                                text = "CANCEL",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFamily = FontFamily.Monospace
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    renameError?.let { errorMsg ->
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = errorMsg,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.testTag("folder_rename_error")
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .clickable {
                                            renameInput = folder.name
                                            renameError = null
                                            isRenaming = true
                                        }
                                        .testTag("folder_rename_trigger")
                                ) {
                                    Text(
                                        text = folder.name.uppercase(),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.5.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Rename Folder",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            if (!isRenaming) {
                                Row {
                                    IconButton(
                                        onClick = { isAddingApps = !isAddingApps },
                                        modifier = Modifier.testTag("folder_add_apps_button")
                                    ) {
                                        Icon(
                                            imageVector = if (isAddingApps) Icons.Default.Close else Icons.Default.Add,
                                            contentDescription = if (isAddingApps) "Back to Apps" else "Add Apps",
                                            tint = ShellDarkAccent,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = onDismiss,
                                        modifier = Modifier.testTag("folder_close_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (isAddingApps) {
                            Text(
                                text = "SELECT APPS TO ADD TO FOLDER",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            val availableToAdd = allApps.filter {
                                it.folderId == null && !it.isFixed && !localOrder.any { f -> f.componentKey == it.componentKey }
                            }
                            if (availableToAdd.isEmpty()) {
                                Text(
                                    text = "NO ELIGIBLE APPS TO ADD",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 24.dp)
                                )
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(4),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(260.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    itemsIndexed(availableToAdd, key = { _, app -> app.componentKey }) { _, app ->
                                        val iconBitmap = rememberAppIcon(iconRepository, app.packageName, app.activityName)
                                        AppIconItem(
                                            app = app,
                                            iconBitmap = iconBitmap,
                                            onClick = { onAddAppToFolder(app) }
                                        )
                                    }
                                }
                            }
                        } else if (localOrder.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .testTag("folder_empty_state"),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "EMPTY FOLDER",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                            letterSpacing = 1.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(onClick = { isAddingApps = true }) {
                                        Text(
                                            text = "+ ADD APPLICATIONS",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = ShellDarkAccent
                                        )
                                    }
                                }
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                userScrollEnabled = draggingApp == null,
                                contentPadding = PaddingValues(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp)
                                    .onGloballyPositioned { coords ->
                                        if (coords.isAttached) {
                                            gridBoundsInRoot = coords.boundsInRoot()
                                        }
                                    }
                                    .testTag("folder_apps_grid")
                            ) {
                                itemsIndexed(localOrder, key = { _, app -> app.componentKey }) { index, app ->
                                    val iconBitmap = rememberAppIcon(iconRepository, app.packageName, app.activityName)
                                    val isDragged = draggingApp?.componentKey == app.componentKey
                                    val isReorderTarget = draggingApp != null && hoveredReorderSlot == index && !isDragged

                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .onGloballyPositioned { coords ->
                                                if (coords.isAttached) {
                                                    itemBoundsByKey[app.componentKey] = coords.boundsInRoot()
                                                }
                                            }
                                            .clip(RoundedCornerShape(14.dp))
                                            .then(
                                                if (isReorderTarget) {
                                                    Modifier
                                                        .border(
                                                            width = 1.5.dp,
                                                            color = ShellDarkAccent,
                                                            shape = RoundedCornerShape(14.dp)
                                                        )
                                                        .testTag("folder_reorder_target")
                                                } else {
                                                    Modifier
                                                }
                                            )
                                            .testTag("folder_app_slot_$index")
                                    ) {
                                        AppIconItem(
                                            app = app,
                                            iconBitmap = iconBitmap,
                                            onClick = {
                                                if (draggingApp == null && !isManageMode) {
                                                    onAppClick(app)
                                                    onDismiss()
                                                }
                                            },
                                            enableBuiltInClick = draggingApp == null && !isManageMode,
                                            isDraggedSource = isDragged
                                        )

                                        if (isManageMode && draggingApp == null) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .size(28.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.surface)
                                                    .border(1.dp, ShellDarkAccent, CircleShape)
                                                    .clickable { onRemoveAppFromFolder(app) }
                                                    .testTag("folder_remove_app_${app.componentKey}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Remove ${app.label} from folder",
                                                    tint = ShellDarkAccent,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Drop zone shown while dragging an app inside the folder to extract to Home
                            if (draggingApp != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                val zoneScale by animateFloatAsState(
                                    targetValue = if (isHoveringRemoveZone) 1.03f else 1f,
                                    animationSpec = spring(stiffness = 450f),
                                    label = "folder_remove_zone_scale"
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .graphicsLayer {
                                            scaleX = zoneScale
                                            scaleY = zoneScale
                                        }
                                        .onGloballyPositioned { coords ->
                                            if (coords.isAttached) {
                                                removeZoneBoundsInRoot = coords.boundsInRoot()
                                            }
                                        }
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (isHoveringRemoveZone) {
                                                ShellDarkAccent.copy(alpha = 0.18f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                            }
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isHoveringRemoveZone) ShellDarkAccent else MaterialTheme.colorScheme.outline,
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .testTag("folder_remove_drop_zone")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Output,
                                        contentDescription = "Remove to Home Screen",
                                        tint = if (isHoveringRemoveZone) ShellDarkAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isHoveringRemoveZone) "RELEASE TO REMOVE TO HOME" else "DROP HERE TO REMOVE TO HOME",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp
                                        ),
                                        color = if (isHoveringRemoveZone) ShellDarkAccent else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                removeZoneBoundsInRoot = Rect.Zero
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Footer Actions: Delete Folder & Manage / Drag Tip
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = onDeleteFolder,
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("folder_delete_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete Folder",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "DELETE",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                )
                            }

                            if (!isAddingApps && localOrder.isNotEmpty()) {
                                TextButton(
                                    onClick = { isManageMode = !isManageMode },
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("folder_manage_button")
                                ) {
                                    Text(
                                        text = if (isManageMode) "DONE" else "MANAGE",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = if (isManageMode) ShellDarkAccent else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (!isAddingApps && localOrder.isNotEmpty() && draggingApp == null) {
                            Text(
                                text = if (isManageMode) "TAP X TO REMOVE APP TO HOME" else "HOLD & DRAG TO REORDER OR REMOVE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Floating Drag Preview inside FolderDialog
                draggingApp?.let { activeDrag ->
                    val iconBitmap = rememberAppIcon(iconRepository, activeDrag.packageName, activeDrag.activityName)
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                translationX = dragPointerInRoot.x - containerRootOffset.x - dragAnchorOffset.x
                                translationY = dragPointerInRoot.y - containerRootOffset.y - dragAnchorOffset.y
                            }
                            .testTag("folder_internal_drag_preview")
                    ) {
                        AppIconItem(
                            app = activeDrag,
                            iconBitmap = iconBitmap,
                            onClick = {},
                            enableBuiltInClick = false,
                            isDragPreview = true
                        )
                    }
                }
            }
        }
    }
}

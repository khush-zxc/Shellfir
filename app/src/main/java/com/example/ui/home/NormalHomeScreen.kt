package com.example.ui.home

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.components.AppDock
import com.example.core.components.AppIconItem
import com.example.core.components.FolderIconItem
import com.example.core.components.ShellfriClock
import com.example.core.components.ShuffleControlBar
import com.example.domain.model.AppItem
import com.example.domain.model.FolderItem
import com.example.domain.repository.IconRepository
import com.example.domain.repository.rememberAppIcon
import com.example.ui.theme.ShellDarkAccent
import kotlinx.coroutines.launch

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
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(
        initialPage = uiState.defaultPageIndex.coerceIn(0, (uiState.pageCount - 1).coerceAtLeast(0)),
        pageCount = { uiState.pageCount.coerceAtLeast(1) }
    )
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .testTag("normal_home_screen")
    ) {
        // Clock & Status Section
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

            // Control Bar: Mode Toggle, Break Pattern, Settings
            ShuffleControlBar(
                homeMode = uiState.homeMode,
                isShuffleEnabled = uiState.isShuffleEnabled,
                shuffleInterval = uiState.shuffleInterval,
                isShuffling = uiState.isShufflingAnimation,
                onModeToggle = onModeToggle,
                onManualShuffle = onManualShuffle,
                onOpenSettings = onOpenSettings
            )
        }

        // Edit Mode Toolbar Banner
        AnimatedVisibility(visible = uiState.isEditMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, ShellDarkAccent, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "EDIT WORKSPACE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = ShellDarkAccent
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Add Page
                    IconButton(onClick = onAddPage, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "Add Page", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                    }

                    // Remove Page
                    if (uiState.pageCount > 1) {
                        IconButton(onClick = { onRemovePage(pagerState.currentPage) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Remove, contentDescription = "Remove Page", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        }
                    }

                    // Change Wallpaper
                    IconButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SET_WALLPAPER)
                            context.startActivity(Intent.createChooser(intent, "Set Wallpaper"))
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = "Wallpaper", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                    }

                    // Grid Columns cycle
                    IconButton(
                        onClick = {
                            val nextCols = if (uiState.gridColumns >= 6) 3 else uiState.gridColumns + 1
                            onSetGridColumns(nextCols)
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Default.GridOn, contentDescription = "Grid Columns", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                    }

                    // Done
                    TextButton(onClick = { onToggleEditMode(false) }) {
                        Text("DONE", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace), color = ShellDarkAccent)
                    }
                }
            }
        }

        // Horizontal Home Pages
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { onToggleEditMode(true) }
                    )
                }
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                // Filter apps & folders for this specific page
                val pageApps = uiState.homeApps.filter { it.pageIndex == pageIndex }.sortedBy { it.slotIndex }
                val pageFolders = uiState.folders.filter { it.pageIndex == pageIndex }.sortedBy { it.slotIndex }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    if (pageApps.isEmpty() && pageFolders.isEmpty()) {
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
                            contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(uiState.gridSpacing.verticalDp.dp),
                            horizontalArrangement = Arrangement.spacedBy(uiState.gridSpacing.horizontalDp.dp, Alignment.CenterHorizontally),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("home_grid_page_$pageIndex")
                        ) {
                            // Folders on this page
                            items(pageFolders, key = { "folder_${it.id}" }) { folder ->
                                val appsInFolder = uiState.allApps.filter { it.folderId == folder.id }
                                FolderIconItem(
                                    folder = folder,
                                    appsInFolder = appsInFolder,
                                    iconRepository = iconRepository,
                                    onClick = { onFolderClick(folder) },
                                    onLongClick = { onFolderLongClick(folder) },
                                    showLabel = uiState.showLabels,
                                    modifier = Modifier.animateItem()
                                )
                            }

                            // Applications on this page
                            items(pageApps, key = { it.componentKey }) { app ->
                                val iconBitmap = rememberAppIcon(iconRepository, app.packageName, app.activityName)
                                AppIconItem(
                                    app = app,
                                    iconBitmap = iconBitmap,
                                    onClick = { onAppClick(app) },
                                    onLongClick = { onAppLongClick(app) },
                                    isShuffling = uiState.isShufflingAnimation,
                                    showLabel = uiState.showLabels,
                                    blurLevel = if (uiState.blurOnShuffle && uiState.isShufflingAnimation) uiState.blurLevel else if (uiState.blurOnDistracting && app.isShuffled) uiState.blurLevel else com.example.domain.model.BlurLevel.DISABLED,
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                }
            }
        }

        // Page Indicator Dots
        if (uiState.pageCount > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                (0 until uiState.pageCount).forEach { idx ->
                    val isSelected = pagerState.currentPage == idx
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (isSelected) 8.dp else 5.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) ShellDarkAccent else MaterialTheme.colorScheme.outline
                            )
                            .clickable {
                                coroutineScope.launch { pagerState.animateScrollToPage(idx) }
                            }
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
}

package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.components.ShellfriWidgetManager
import com.example.data.backup.BackupSummary
import com.example.domain.model.HomeMode
import com.example.ui.home.AppConfigDialog
import com.example.ui.home.AppDrawerScreen
import com.example.ui.home.FocusHomeScreen
import com.example.ui.home.FocusModeConfigDialog
import com.example.ui.home.FolderDialog
import com.example.ui.home.HomeViewModel
import com.example.ui.home.NormalHomeScreen
import com.example.ui.home.OnboardingFlow
import com.example.ui.home.SettingsScreen
import com.example.ui.home.ShuffleHistoryDialog
import com.example.ui.home.ShuffledAppConfigDialog
import com.example.ui.theme.ShellDarkAccent
import com.example.ui.theme.ShellfriTheme
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels()
    private lateinit var widgetManager: ShellfriWidgetManager

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val packageName = intent?.data?.schemeSpecificPart
            viewModel.onPackageUpdated(packageName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        widgetManager = ShellfriWidgetManager(this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageChangeReceiver, filter)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val lifecycleOwner = LocalLifecycleOwner.current
            val coroutineScope = rememberCoroutineScope()

            var pendingRestorePreview by remember { mutableStateOf<Pair<String, BackupSummary>?>(null) }

            // SAF Document Exporter Launcher
            val exportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/json")
            ) { uri ->
                uri?.let { destUri ->
                    coroutineScope.launch {
                        try {
                            val jsonString = viewModel.backupRestoreHelper.generateBackupJson()
                            contentResolver.openOutputStream(destUri)?.use { stream ->
                                stream.write(jsonString.toByteArray(Charsets.UTF_8))
                            }
                            Toast.makeText(this@MainActivity, "Shellfri Backup Exported Successfully!", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Export Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            // SAF Document Importer Launcher — validates & opens preview before mutating storage
            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri ->
                uri?.let { srcUri ->
                    coroutineScope.launch {
                        try {
                            val jsonString = contentResolver.openInputStream(srcUri)?.use { stream ->
                                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                            }
                            if (jsonString.isNullOrBlank()) {
                                Toast.makeText(this@MainActivity, "Import Failed: Backup file is empty", Toast.LENGTH_LONG).show()
                            } else {
                                val summaryResult = viewModel.backupRestoreHelper.parseBackupSummary(jsonString)
                                summaryResult.fold(
                                    onSuccess = { summary ->
                                        pendingRestorePreview = jsonString to summary
                                    },
                                    onFailure = { error ->
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Invalid Backup: ${error.localizedMessage ?: "Unsupported format"}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Import Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        viewModel.checkScheduledShuffle()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            val darkTheme = when (uiState.themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemInDarkTheme()
            }

            ShellfriTheme(darkTheme = darkTheme) {
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(uiState.messageToast) {
                    uiState.messageToast?.let { message ->
                        snackbarHostState.showSnackbar(message)
                        viewModel.dismissToast()
                    }
                }

                // Handle back press
                BackHandler(
                    enabled = uiState.isDrawerOpen ||
                            uiState.isSettingsOpen ||
                            uiState.isShuffleHistoryOpen ||
                            uiState.isFocusConfigOpen ||
                            uiState.isManageShuffledAppsOpen ||
                            uiState.activeFolder != null ||
                            uiState.isEditMode
                ) {
                    when {
                        uiState.isDrawerOpen -> viewModel.setDrawerOpen(false)
                        uiState.isSettingsOpen -> viewModel.setSettingsOpen(false)
                        uiState.isShuffleHistoryOpen -> viewModel.setShuffleHistoryOpen(false)
                        uiState.isFocusConfigOpen -> viewModel.openFocusConfig(false)
                        uiState.isManageShuffledAppsOpen -> viewModel.openManageShuffledApps(false)
                        uiState.activeFolder != null -> viewModel.closeFolder()
                        uiState.isEditMode -> viewModel.setEditMode(false)
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .padding(top = 16.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            SnackbarHost(
                                hostState = snackbarHostState,
                                snackbar = { data ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .border(1.dp, ShellDarkAccent, RoundedCornerShape(14.dp))
                                            .padding(horizontal = 16.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = data.visuals.message.uppercase(),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                letterSpacing = 1.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            )
                        }
                    }
                ) { _ ->
                    if (uiState.isOnboardingOpen) {
                        OnboardingFlow(
                            allApps = uiState.allApps,
                            selectedInterval = uiState.shuffleInterval,
                            onToggleAppShuffled = { viewModel.toggleAppShuffled(it) },
                            onSetInterval = { viewModel.setShuffleInterval(it) },
                            onComplete = { viewModel.completeOnboarding() }
                        )
                    } else if (uiState.isSettingsOpen) {
                        // Full-Screen Shellfri Settings (Requirement 1)
                        SettingsScreen(
                            uiState = uiState,
                            onDismiss = { viewModel.setSettingsOpen(false) },
                            onSetShuffleEnabled = { viewModel.setShuffleEnabled(it) },
                            onSetShufflePaused = { viewModel.setShufflePaused(it) },
                            onSetShuffleInterval = { viewModel.setShuffleInterval(it) },
                            onSetShuffleStrategy = { viewModel.setShuffleStrategy(it) },
                            onSetOccupancyOption = { viewModel.setOccupancyOption(it) },
                            onSetCrossPageShuffle = { viewModel.setCrossPageShuffle(it) },
                            onSetDrawerShuffleEnabled = { viewModel.setDrawerShuffleEnabled(it) },
                            onOpenManageShuffledApps = { viewModel.openManageShuffledApps(true) },
                            onUndoLastShuffle = { viewModel.undoLastShuffle() },
                            onSetThemeMode = { viewModel.setThemeMode(it) },
                            onSetGridColumns = { viewModel.setGridColumns(it) },
                            onSetGridSpacing = { viewModel.setGridSpacing(it) },
                            onSetIconSize = { viewModel.setIconSize(it) },
                            onSetShowLabels = { viewModel.setShowLabels(it) },
                            onSetBlurLevel = { viewModel.setBlurLevel(it) },
                            onSetBlurOnShuffle = { viewModel.setBlurOnShuffle(it) },
                            onSetDockCount = { viewModel.setDockCount(it) },
                            onSetDockShowBackground = { viewModel.setDockShowBackground(it) },
                            onSetDockShowLabels = { viewModel.setDockShowLabels(it) },
                            onAddPage = { viewModel.addPage() },
                            onSetDefaultPageIndex = { viewModel.setDefaultPage(it) },
                            onOpenFocusConfig = { viewModel.openFocusConfig(true) },
                            onSetHideDistractingInFocus = { viewModel.setHideDistractingInFocus(it) },
                            onManualShuffle = { viewModel.manualShuffle() },
                            onOpenShuffleHistory = { viewModel.setShuffleHistoryOpen(true) },
                            onExportBackup = {
                                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                                exportLauncher.launch("shellfri_backup_$dateStr.json")
                            },
                            onImportBackup = {
                                importLauncher.launch("application/json")
                            },
                            onOpenOnboarding = { viewModel.openOnboarding() }
                        )
                    } else {
                        // Switch between Normal and Focus home modes
                        AnimatedContent(
                            targetState = uiState.homeMode,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "home_mode_transition"
                        ) { mode ->
                            when (mode) {
                                HomeMode.NORMAL -> {
                                    NormalHomeScreen(
                                        uiState = uiState,
                                        iconRepository = viewModel.iconRepository,
                                        onAppClick = { viewModel.launchApp(it) },
                                        onAppLongClick = { viewModel.setSelectedAppForConfig(it) },
                                        onFolderClick = { viewModel.openFolder(it) },
                                        onFolderLongClick = { viewModel.openFolder(it) },
                                        onModeToggle = { viewModel.toggleHomeMode() },
                                        onManualShuffle = { viewModel.manualShuffle() },
                                        onOpenDrawer = { viewModel.setDrawerOpen(true) },
                                        onOpenSettings = { viewModel.setSettingsOpen(true) },
                                        onToggleEditMode = { viewModel.setEditMode(it) },
                                        onAddPage = { viewModel.addPage() },
                                        onRemovePage = { viewModel.removePage(it) },
                                        onSetGridColumns = { viewModel.setGridColumns(it) },
                                        onMoveApp = { app, targetPage, targetSlot, onResult ->
                                            viewModel.moveAppOnHomeScreen(app, targetPage, targetSlot, onResult)
                                        },
                                        onCreateFolderFromApps = { dragged, target, onResult ->
                                            viewModel.createFolderFromHomeApps(dragged, target, onResult)
                                        },
                                        onAddAppToFolder = { app, folder, onResult ->
                                            viewModel.addAppToFolder(app, folder.id, onResult)
                                        },
                                        onMoveFolder = { folder, targetPage, targetSlot, onResult ->
                                            viewModel.moveFolderOnHomeScreen(folder, targetPage, targetSlot, onResult)
                                        },
                                        onDragRejected = { reason ->
                                            viewModel.showMessageToast(reason)
                                        }
                                    )
                                }
                                HomeMode.FOCUS -> {
                                    FocusHomeScreen(
                                        uiState = uiState,
                                        onAppClick = { viewModel.launchApp(it) },
                                        onAppLongClick = { viewModel.setSelectedAppForConfig(it) },
                                        onModeToggle = { viewModel.toggleHomeMode() },
                                        onOpenFocusConfig = { viewModel.openFocusConfig(true) },
                                        onOpenSettings = { viewModel.setSettingsOpen(true) }
                                    )
                                }
                            }
                        }

                        // Full-screen App Drawer
                        AppDrawerScreen(
                            isOpen = uiState.isDrawerOpen,
                            allApps = uiState.allApps,
                            iconRepository = viewModel.iconRepository,
                            gridColumns = uiState.gridColumns,
                            drawerShuffleEnabled = uiState.drawerShuffleEnabled,
                            searchQuery = uiState.searchQuery,
                            onSearchQueryChange = { viewModel.setSearchQuery(it) },
                            onAppClick = { viewModel.launchApp(it) },
                            onAppLongClick = { viewModel.setSelectedAppForConfig(it) },
                            onDismiss = { viewModel.setDrawerOpen(false) }
                        )

                        // Individual App Config Dialog
                        uiState.selectedAppForConfig?.let { app ->
                            AppConfigDialog(
                                app = app,
                                pageCount = uiState.pageCount,
                                folders = uiState.folders,
                                iconRepository = viewModel.iconRepository,
                                onDismiss = { viewModel.setSelectedAppForConfig(null) },
                                onToggleShuffled = { viewModel.toggleAppShuffled(app) },
                                onToggleFixed = { viewModel.toggleAppFixed(app) },
                                onToggleDock = { viewModel.toggleAppPinnedToDock(app) },
                                onMoveToPage = { viewModel.moveAppToPage(app, it) },
                                onAddToFolder = { viewModel.addAppToFolder(app, it) },
                                onCreateFolderWithApp = { folderName ->
                                    viewModel.createFolder(folderName, app.pageIndex, listOf(app.packageName to app.activityName))
                                },
                                onRemoveFromHomeScreen = { viewModel.removeAppFromHomeScreen(app) },
                                onLaunch = { viewModel.launchApp(app) }
                            )
                        }

                        // Folder Opening Dialog
                        uiState.activeFolder?.let { folder ->
                            val appsInFolder = uiState.allApps
                                .filter { it.folderId == folder.id }
                                .sortedWith(
                                    compareBy<com.example.domain.model.AppItem> {
                                        if (it.folderRank >= 0) it.folderRank else Int.MAX_VALUE
                                    }
                                        .thenBy { it.label.lowercase() }
                                        .thenBy { it.componentKey }
                                )
                            FolderDialog(
                                folder = folder,
                                appsInFolder = appsInFolder,
                                allApps = uiState.allApps,
                                iconRepository = viewModel.iconRepository,
                                onAppClick = { viewModel.launchApp(it) },
                                onRemoveAppFromFolder = { viewModel.removeAppFromFolder(it, folder.pageIndex) },
                                onAddAppToFolder = { viewModel.addAppToFolder(it, folder.id) },
                                onRenameFolder = { newName, onResult ->
                                    viewModel.renameFolder(folder.id, newName, onResult)
                                },
                                onReorderApps = { reordered, onResult ->
                                    viewModel.reorderFolderMembers(folder.id, reordered, onResult)
                                },
                                onDeleteFolder = { viewModel.deleteFolder(folder.id) },
                                onDismiss = { viewModel.closeFolder() }
                            )
                        }

                        // Focus Mode Apps Configuration Dialog
                        if (uiState.isFocusConfigOpen) {
                            FocusModeConfigDialog(
                                allApps = uiState.allApps,
                                onToggleVisibility = { app, isVis -> viewModel.toggleFocusVisibility(app, isVis) },
                                onToggleEssential = { app, isEss -> viewModel.toggleFocusEssential(app, isEss) },
                                onSelectAll = { viewModel.setAllFocusVisibility(uiState.allApps, true) },
                                onClearAll = { viewModel.setAllFocusVisibility(uiState.allApps, false) },
                                onDismiss = { viewModel.openFocusConfig(false) }
                            )
                        }

                        // Shuffled Apps Configuration Modal Dialog (Requirement 2)
                        if (uiState.isManageShuffledAppsOpen) {
                            ShuffledAppConfigDialog(
                                allApps = uiState.allApps,
                                repository = viewModel.repository,
                                onSaveSelection = { selectedSet -> viewModel.saveShuffledAppsSelection(selectedSet) },
                                onDismiss = { viewModel.openManageShuffledApps(false) }
                            )
                        }

                        // Shuffle History Log Dialog
                        if (uiState.isShuffleHistoryOpen) {
                            ShuffleHistoryDialog(
                                history = uiState.shuffleHistory,
                                onClearHistory = { viewModel.clearHistory() },
                                onDismiss = { viewModel.setShuffleHistoryOpen(false) }
                            )
                        }
                    }

                    pendingRestorePreview?.let { (rawJson, summary) ->
                        val dateText = remember(summary.timestamp) {
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(summary.timestamp))
                        }
                        AlertDialog(
                            onDismissRequest = { pendingRestorePreview = null },
                            title = {
                                Text(
                                    text = "RESTORE BACKUP?",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            },
                            text = {
                                Column {
                                    Text(
                                        text = "Schema v${summary.version} • $dateText",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Apps: ${summary.appCount} | Folders: ${summary.folderCount}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "Theme: ${summary.themeMode} | Grid: ${summary.gridColumns} cols | Pages: ${summary.pageCount}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        pendingRestorePreview = null
                                        coroutineScope.launch {
                                            val result = viewModel.backupRestoreHelper.restoreBackup(rawJson)
                                            result.fold(
                                                onSuccess = {
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "Shellfri Backup Restored Successfully!",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                },
                                                onFailure = { error ->
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "Restore Failed: ${error.localizedMessage ?: "Validation Error"}",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            )
                                        }
                                    }
                                ) {
                                    Text("RESTORE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingRestorePreview = null }) {
                                    Text("CANCEL", fontFamily = FontFamily.Monospace)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        widgetManager.startListening()
    }

    override fun onStop() {
        super.onStop()
        widgetManager.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(packageChangeReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}

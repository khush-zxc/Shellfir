package com.example.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.backup.BackupRestoreHelper
import com.example.data.database.ShellfriDatabase
import com.example.data.database.ShuffleHistoryEntity
import com.example.data.preferences.ShellfriPreferences
import com.example.domain.model.AppItem
import com.example.domain.model.BlurLevel
import com.example.domain.model.FolderItem
import com.example.domain.model.GridSpacing
import com.example.domain.model.HomeMode
import com.example.domain.model.OccupancyOption
import com.example.domain.model.ShuffleInterval
import com.example.domain.model.ShuffleStrategy
import com.example.domain.repository.AppRepository
import com.example.domain.repository.IconRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val homeMode: HomeMode = HomeMode.NORMAL,
    val allApps: List<AppItem> = emptyList(),
    val homeApps: List<AppItem> = emptyList(),
    val dockApps: List<AppItem> = emptyList(),
    val folders: List<FolderItem> = emptyList(),
    val shuffledApps: List<AppItem> = emptyList(),
    val isShuffleEnabled: Boolean = true,
    val isShufflePaused: Boolean = false,
    val shuffleInterval: ShuffleInterval = ShuffleInterval.MIN_30,
    val shuffleStrategy: ShuffleStrategy = ShuffleStrategy.FULL_RANDOM,
    val occupancyOption: OccupancyOption = OccupancyOption.PRESERVE_EMPTY_SPACES,
    val drawerShuffleEnabled: Boolean = false,
    val lastShuffleTime: Long = 0L,
    val shuffleHistory: List<ShuffleHistoryEntity> = emptyList(),
    val themeMode: String = "DARK",
    val gridColumns: Int = 4,
    val gridSpacing: GridSpacing = GridSpacing.COMFORTABLE,
    val iconSize: String = "MEDIUM",
    val showLabels: Boolean = true,
    val blurLevel: BlurLevel = BlurLevel.DISABLED,
    val blurOnShuffle: Boolean = true,
    val blurOnDistracting: Boolean = false,
    val blurOnFocus: Boolean = false,
    val pageCount: Int = 2,
    val defaultPageIndex: Int = 0,
    val crossPageShuffle: Boolean = false,
    val dockCount: Int = 5,
    val dockShowBackground: Boolean = true,
    val dockShowLabels: Boolean = false,
    val hasCompletedOnboarding: Boolean = true,
    val hideDistractingInFocus: Boolean = false,
    val focusSearchEnabled: Boolean = true,
    val isDrawerOpen: Boolean = false,
    val isSettingsOpen: Boolean = false,
    val isFocusConfigOpen: Boolean = false,
    val isManageShuffledAppsOpen: Boolean = false,
    val isEditMode: Boolean = false,
    val activeFolder: FolderItem? = null,
    val selectedAppForConfig: AppItem? = null,
    val isOnboardingOpen: Boolean = false,
    val isShuffleHistoryOpen: Boolean = false,
    val searchQuery: String = "",
    val messageToast: String? = null,
    val isShufflingAnimation: Boolean = false
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val database = ShellfriDatabase.getInstance(application)
    private val preferences = ShellfriPreferences(application)
    private val mutationCoordinator = com.example.domain.concurrency.WorkspaceMutationCoordinator.forDatabase(database)
    val iconRepository = IconRepository(application)
    val repository = AppRepository(
        context = application,
        installedAppDao = database.installedAppDao(),
        workspaceDao = database.workspaceDao(),
        shuffleHistoryDao = database.shuffleHistoryDao(),
        preferences = preferences,
        database = database,
        iconRepository = iconRepository,
        mutationCoordinator = mutationCoordinator
    )
    val backupRestoreHelper = BackupRestoreHelper(
        installedAppDao = database.installedAppDao(),
        workspaceDao = database.workspaceDao(),
        preferences = preferences,
        database = database,
        mutationCoordinator = mutationCoordinator,
        shuffleHistoryDao = database.shuffleHistoryDao(),
        isWidgetIdValid = { appWidgetId ->
            runCatching {
                android.appwidget.AppWidgetManager.getInstance(application).getAppWidgetInfo(appWidgetId) != null
            }.getOrDefault(false)
        }
    )

    private val _isDrawerOpen = MutableStateFlow(false)
    private val _isSettingsOpen = MutableStateFlow(false)
    private val _isFocusConfigOpen = MutableStateFlow(false)
    private val _isManageShuffledAppsOpen = MutableStateFlow(false)
    private val _isEditMode = MutableStateFlow(false)
    private val _activeFolder = MutableStateFlow<FolderItem?>(null)
    private val _selectedAppForConfig = MutableStateFlow<AppItem?>(null)
    private val _isOnboardingOpen = MutableStateFlow(false)
    private val _isShuffleHistoryOpen = MutableStateFlow(false)
    private val _searchQuery = MutableStateFlow("")
    private val _messageToast = MutableStateFlow<String?>(null)
    private val _isShufflingAnimation = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        combine(
            repository.allAppsFlow,
            repository.dockAppsFlow,
            repository.foldersFlow,
            repository.homeModeFlow,
            repository.shuffleIntervalFlow,
            repository.isShuffleEnabledFlow,
            preferences.isShufflePausedFlow,
            preferences.shuffleStrategyFlow,
            preferences.occupancyOptionFlow,
            preferences.drawerShuffleEnabledFlow
        ) { values -> values },
        combine(
            repository.themeModeFlow,
            repository.gridColumnsFlow,
            preferences.gridSpacingFlow,
            repository.iconSizeFlow,
            repository.showLabelsFlow,
            preferences.blurLevelFlow,
            preferences.blurOnShuffleFlow,
            preferences.blurOnDistractingFlow,
            preferences.blurOnFocusFlow,
            repository.pageCountFlow
        ) { values -> values },
        combine(
            repository.defaultPageIndexFlow,
            repository.crossPageShuffleFlow,
            repository.dockCountFlow,
            repository.dockShowBackgroundFlow,
            repository.dockShowLabelsFlow,
            repository.onboardingCompletedFlow,
            repository.lastShuffleTimeFlow,
            repository.shuffleHistoryFlow,
            repository.hideDistractingInFocusFlow,
            combine(
                _isDrawerOpen,
                _isSettingsOpen,
                _isFocusConfigOpen,
                _isManageShuffledAppsOpen,
                _isEditMode,
                _activeFolder,
                _selectedAppForConfig,
                _isOnboardingOpen,
                _isShuffleHistoryOpen,
                _searchQuery
            ) { v -> v }
        ) { values -> values },
        combine(_messageToast, _isShufflingAnimation) { t, a -> Pair(t, a) }
    ) { g1, g2, g3, g4 ->
        @Suppress("UNCHECKED_CAST")
        val allApps = g1[0] as List<AppItem>
        @Suppress("UNCHECKED_CAST")
        val rawDockApps = g1[1] as List<AppItem>
        @Suppress("UNCHECKED_CAST")
        val folders = g1[2] as List<FolderItem>
        val homeMode = g1[3] as HomeMode
        val shuffleInterval = g1[4] as ShuffleInterval
        val isShuffleEnabled = g1[5] as Boolean
        val isShufflePaused = g1[6] as Boolean
        val shuffleStrategy = g1[7] as ShuffleStrategy
        val occupancyOption = g1[8] as OccupancyOption
        val drawerShuffleEnabled = g1[9] as Boolean

        val themeMode = g2[0] as String
        val gridColumns = g2[1] as Int
        val gridSpacing = g2[2] as GridSpacing
        val iconSize = g2[3] as String
        val showLabels = g2[4] as Boolean
        val blurLevel = g2[5] as BlurLevel
        val blurOnShuffle = g2[6] as Boolean
        val blurOnDistracting = g2[7] as Boolean
        val blurOnFocus = g2[8] as Boolean
        val pageCount = g2[9] as Int

        val defaultPageIndex = g3[0] as Int
        val crossPageShuffle = g3[1] as Boolean
        val dockCount = (g3[2] as Int).coerceIn(3, 7)
        val dockShowBg = g3[3] as Boolean
        val dockShowLabels = g3[4] as Boolean
        val onboardingCompleted = g3[5] as Boolean
        val lastShuffleTime = g3[6] as Long
        @Suppress("UNCHECKED_CAST")
        val shuffleHistory = g3[7] as List<ShuffleHistoryEntity>
        val hideDistracting = g3[8] as Boolean

        @Suppress("UNCHECKED_CAST")
        val uiFlags = g3[9] as Array<Any?>
        val isDrawerOpen = uiFlags[0] as Boolean
        val isSettingsOpen = uiFlags[1] as Boolean
        val isFocusConfigOpen = uiFlags[2] as Boolean
        val isManageShuffledAppsOpen = uiFlags[3] as Boolean
        val isEditMode = uiFlags[4] as Boolean
        val activeFolder = uiFlags[5] as FolderItem?
        val selectedApp = uiFlags[6] as AppItem?
        val isOnboardingOpen = uiFlags[7] as Boolean
        val isShuffleHistoryOpen = uiFlags[8] as Boolean
        val query = uiFlags[9] as String

        val toast = g4.first
        val isShuffling = g4.second

        val homeApps = allApps.filter { it.isOnHomeScreen && it.folderId == null }
        val dockApps = rawDockApps.take(dockCount)
        val shuffledApps = allApps.filter { it.isShuffled }

        HomeUiState(
            homeMode = homeMode,
            allApps = allApps,
            homeApps = homeApps,
            dockApps = dockApps,
            folders = folders,
            shuffledApps = shuffledApps,
            isShuffleEnabled = isShuffleEnabled,
            isShufflePaused = isShufflePaused,
            shuffleInterval = shuffleInterval,
            shuffleStrategy = shuffleStrategy,
            occupancyOption = occupancyOption,
            drawerShuffleEnabled = drawerShuffleEnabled,
            lastShuffleTime = lastShuffleTime,
            shuffleHistory = shuffleHistory,
            themeMode = themeMode,
            gridColumns = gridColumns,
            gridSpacing = gridSpacing,
            iconSize = iconSize,
            showLabels = showLabels,
            blurLevel = blurLevel,
            blurOnShuffle = blurOnShuffle,
            blurOnDistracting = blurOnDistracting,
            blurOnFocus = blurOnFocus,
            pageCount = pageCount,
            defaultPageIndex = defaultPageIndex,
            crossPageShuffle = crossPageShuffle,
            dockCount = dockCount,
            dockShowBackground = dockShowBg,
            dockShowLabels = dockShowLabels,
            hasCompletedOnboarding = onboardingCompleted,
            hideDistractingInFocus = hideDistracting,
            isDrawerOpen = isDrawerOpen,
            isSettingsOpen = isSettingsOpen,
            isFocusConfigOpen = isFocusConfigOpen,
            isManageShuffledAppsOpen = isManageShuffledAppsOpen,
            isEditMode = isEditMode,
            activeFolder = activeFolder,
            selectedAppForConfig = selectedApp,
            isOnboardingOpen = isOnboardingOpen || !onboardingCompleted,
            isShuffleHistoryOpen = isShuffleHistoryOpen,
            searchQuery = query,
            messageToast = toast,
            isShufflingAnimation = isShuffling
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    init {
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch {
            repository.discoverAndSyncApps()
            checkScheduledShuffle()
        }
    }

    fun onPackageUpdated(packageName: String?) {
        if (!packageName.isNullOrBlank()) {
            iconRepository.invalidatePackage(packageName)
        }
        refreshApps()
    }

    fun checkScheduledShuffle() {
        viewModelScope.launch {
            val paused = preferences.isShufflePausedFlow.first()
            if (!paused) {
                val shuffled = repository.checkAndTriggerScheduledShuffle()
                if (shuffled) {
                    triggerShuffleVisualFeedback("Automatic pattern shuffle completed")
                }
            }
        }
    }

    fun manualShuffle() {
        viewModelScope.launch {
            _isShufflingAnimation.value = true
            val success = repository.performShuffle(triggerType = "MANUAL")
            if (success) {
                triggerShuffleVisualFeedback("Pattern broken! Apps randomized")
            } else {
                _messageToast.value = "No apps marked for shuffle. Tap 'Manage Shuffled Apps'."
            }
            kotlinx.coroutines.delay(400)
            _isShufflingAnimation.value = false
        }
    }

    fun undoLastShuffle() {
        viewModelScope.launch {
            val result = repository.undoLastShuffleDetailed()
            _messageToast.value = when (result.status) {
                com.example.domain.shuffle.UndoShuffleStatus.APPLIED ->
                    "Undo last shuffle successful!"
                com.example.domain.shuffle.UndoShuffleStatus.PARTIALLY_APPLIED ->
                    "Restored ${result.restoredHomeCount + result.restoredDrawerCount} layout item(s); ${result.skippedComponents.size} skipped due to later changes"
                com.example.domain.shuffle.UndoShuffleStatus.NO_SNAPSHOT ->
                    "No previous shuffle layout to restore"
                com.example.domain.shuffle.UndoShuffleStatus.UNAVAILABLE ->
                    result.failureReason ?: "Shuffle undo is no longer available"
                com.example.domain.shuffle.UndoShuffleStatus.FAILED ->
                    result.failureReason ?: "Unable to undo last shuffle"
            }
        }
    }

    private fun triggerShuffleVisualFeedback(message: String) {
        _messageToast.value = message
    }

    fun toggleHomeMode() {
        viewModelScope.launch {
            val current = uiState.value.homeMode
            val newMode = if (current == HomeMode.NORMAL) HomeMode.FOCUS else HomeMode.NORMAL
            repository.setHomeMode(newMode)
        }
    }

    fun setHomeMode(mode: HomeMode) {
        viewModelScope.launch { repository.setHomeMode(mode) }
    }

    fun launchApp(app: AppItem) {
        val success = repository.launchApp(app)
        if (!success) {
            _messageToast.value = "Unable to open ${app.label}"
        }
    }

    fun toggleAppShuffled(app: AppItem) {
        viewModelScope.launch {
            repository.toggleAppShuffled(app.packageName, app.activityName, !app.isShuffled)
        }
    }

    fun saveShuffledAppsSelection(selectedComponentKeys: Set<String>) {
        viewModelScope.launch {
            val allApps = uiState.value.allApps
            for (app in allApps) {
                val shouldShuffle = selectedComponentKeys.contains(app.componentKey)
                if (app.isShuffled != shouldShuffle) {
                    repository.toggleAppShuffled(app.packageName, app.activityName, shouldShuffle)
                }
            }
            _messageToast.value = "Saved ${selectedComponentKeys.size} shuffled applications"
        }
    }

    fun toggleAppFixed(app: AppItem) {
        viewModelScope.launch {
            repository.toggleAppFixed(app.packageName, app.activityName, !app.isFixed)
        }
    }

    fun toggleAppPinnedToDock(app: AppItem) {
        viewModelScope.launch {
            repository.toggleAppPinnedToDock(app.packageName, app.activityName, !app.isPinnedToDock)
        }
    }

    fun moveAppToPage(app: AppItem, targetPage: Int) {
        viewModelScope.launch {
            repository.setAppPageAndSlot(app.packageName, app.activityName, targetPage, app.slotIndex)
            _messageToast.value = "Moved ${app.label} to Page ${targetPage + 1}"
        }
    }

    fun removeAppFromHomeScreen(app: AppItem) {
        viewModelScope.launch {
            repository.removeAppFromHomeScreen(app.packageName, app.activityName)
            _messageToast.value = "Removed ${app.label} from Home Screen"
        }
    }

    fun addAppToHomeScreen(app: AppItem, pageIndex: Int) {
        viewModelScope.launch {
            repository.addAppToHomeScreen(app.packageName, app.activityName, pageIndex)
            _messageToast.value = "Added ${app.label} to Page ${pageIndex + 1}"
        }
    }

    // --- Page Management ---

    fun addPage() {
        viewModelScope.launch {
            val current = uiState.value.pageCount
            repository.addPage()
            _messageToast.value = "Added Home Page ${current + 1}"
        }
    }

    fun removePage(pageIndex: Int) {
        viewModelScope.launch {
            val removed = repository.removePage(pageIndex)
            if (!removed) {
                _messageToast.value = "At least one home page is required"
            } else {
                _messageToast.value = "Removed Page ${pageIndex + 1}"
            }
        }
    }

    fun setDefaultPage(index: Int) {
        viewModelScope.launch {
            repository.setDefaultPageIndex(index)
            _messageToast.value = "Default page set to Page ${index + 1}"
        }
    }

    fun setEditMode(enabled: Boolean) {
        _isEditMode.value = enabled
    }

    // --- Folder Operations ---

    fun createFolder(name: String, pageIndex: Int, components: List<Pair<String, String>>) {
        viewModelScope.launch {
            repository.createFolder(name, pageIndex, 0, components)
            _messageToast.value = "Folder '$name' created"
        }
    }

    fun renameFolder(folderId: String, newName: String) {
        viewModelScope.launch {
            repository.renameFolder(folderId, newName)
            _activeFolder.value = _activeFolder.value?.copy(name = newName)
            _messageToast.value = "Folder renamed to '$newName'"
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            repository.deleteFolder(folderId)
            _activeFolder.value = null
            _messageToast.value = "Folder deleted. Apps unpacked."
        }
    }

    fun addAppToFolder(app: AppItem, folderId: String) {
        viewModelScope.launch {
            repository.addAppToFolder(app.packageName, app.activityName, folderId)
            _messageToast.value = "Added ${app.label} to folder"
        }
    }

    fun removeAppFromFolder(app: AppItem, targetPage: Int) {
        viewModelScope.launch {
            repository.removeAppFromFolder(app.packageName, app.activityName, targetPage)
            _messageToast.value = "Removed ${app.label} from folder"
        }
    }

    fun openFolder(folder: FolderItem) {
        _activeFolder.value = folder
    }

    fun closeFolder() {
        _activeFolder.value = null
    }

    // --- Focus Mode Configuration ---

    fun toggleFocusVisibility(app: AppItem, isVisible: Boolean) {
        viewModelScope.launch {
            repository.toggleFocusVisibility(app.packageName, app.activityName, isVisible)
        }
    }

    fun setAllFocusVisibility(apps: List<AppItem>, isVisible: Boolean) {
        viewModelScope.launch {
            repository.setAllFocusVisibility(apps.map { it.packageName to it.activityName }, isVisible)
        }
    }

    fun toggleFocusEssential(app: AppItem, isEssential: Boolean) {
        viewModelScope.launch {
            repository.toggleFocusEssential(app.packageName, app.activityName, isEssential)
        }
    }

    fun openFocusConfig(open: Boolean) {
        _isFocusConfigOpen.value = open
    }

    fun openManageShuffledApps(open: Boolean) {
        _isManageShuffledAppsOpen.value = open
    }

    // --- Preferences & Advanced Shuffle Upgrades ---

    fun setGridColumns(cols: Int) {
        viewModelScope.launch { repository.setGridColumns(cols) }
    }

    fun setGridSpacing(spacing: GridSpacing) {
        viewModelScope.launch { preferences.setGridSpacing(spacing) }
    }

    fun setIconSize(size: String) {
        viewModelScope.launch { repository.setIconSize(size) }
    }

    fun setShowLabels(show: Boolean) {
        viewModelScope.launch { repository.setShowLabels(show) }
    }

    fun setBlurLevel(level: BlurLevel) {
        viewModelScope.launch { preferences.setBlurLevel(level) }
    }

    fun setBlurOnShuffle(enabled: Boolean) {
        viewModelScope.launch { preferences.setBlurOnShuffle(enabled) }
    }

    fun setBlurOnDistracting(enabled: Boolean) {
        viewModelScope.launch { preferences.setBlurOnDistracting(enabled) }
    }

    fun setBlurOnFocus(enabled: Boolean) {
        viewModelScope.launch { preferences.setBlurOnFocus(enabled) }
    }

    fun setShuffleStrategy(strategy: ShuffleStrategy) {
        viewModelScope.launch { preferences.setShuffleStrategy(strategy) }
    }

    fun setOccupancyOption(occupancy: OccupancyOption) {
        viewModelScope.launch { preferences.setOccupancyOption(occupancy) }
    }

    fun setDrawerShuffleEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setDrawerShuffleEnabled(enabled) }
    }

    fun setShufflePaused(paused: Boolean) {
        viewModelScope.launch { preferences.setShufflePaused(paused) }
    }

    fun setCrossPageShuffle(enabled: Boolean) {
        viewModelScope.launch { repository.setCrossPageShuffle(enabled) }
    }

    fun setDockCount(count: Int) {
        viewModelScope.launch { repository.setDockCount(count) }
    }

    fun setDockShowBackground(show: Boolean) {
        viewModelScope.launch { repository.setDockShowBackground(show) }
    }

    fun setDockShowLabels(show: Boolean) {
        viewModelScope.launch { repository.setDockShowLabels(show) }
    }

    fun setShuffleInterval(interval: ShuffleInterval) {
        viewModelScope.launch { repository.setShuffleInterval(interval) }
    }

    fun setShuffleEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setShuffleEnabled(enabled) }
    }

    fun setThemeMode(theme: String) {
        viewModelScope.launch { repository.setThemeMode(theme) }
    }

    fun setHideDistractingInFocus(hide: Boolean) {
        viewModelScope.launch { repository.setHideDistractingInFocus(hide) }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            repository.setOnboardingCompleted(true)
            _isOnboardingOpen.value = false
        }
    }

    fun openOnboarding() {
        _isOnboardingOpen.value = true
    }

    fun setDrawerOpen(open: Boolean) {
        _isDrawerOpen.value = open
        if (!open) {
            _searchQuery.value = ""
        }
    }

    fun setSettingsOpen(open: Boolean) {
        _isSettingsOpen.value = open
    }

    fun setShuffleHistoryOpen(open: Boolean) {
        _isShuffleHistoryOpen.value = open
    }

    fun setSelectedAppForConfig(app: AppItem?) {
        _selectedAppForConfig.value = app
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            _messageToast.value = "Shuffle history cleared"
        }
    }

    fun dismissToast() {
        _messageToast.value = null
    }
}

package com.example.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.BlurLevel
import com.example.domain.model.GridSpacing
import com.example.domain.model.HomeMode
import com.example.domain.model.OccupancyOption
import com.example.domain.model.ShuffleInterval
import com.example.domain.model.ShuffleStrategy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "shellfri_settings")

open class ShellfriPreferences(private val context: Context) {

    private object Keys {
        val HOME_MODE = stringPreferencesKey("home_mode")
        val SHUFFLE_INTERVAL = intPreferencesKey("shuffle_interval_min")
        val SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
        val SHUFFLE_PAUSED = booleanPreferencesKey("shuffle_paused")
        val SHUFFLE_STRATEGY = stringPreferencesKey("shuffle_strategy")
        val OCCUPANCY_OPTION = stringPreferencesKey("occupancy_option")
        val DRAWER_SHUFFLE_ENABLED = booleanPreferencesKey("drawer_shuffle_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val GRID_SPACING = stringPreferencesKey("grid_spacing")
        val ICON_SIZE = stringPreferencesKey("icon_size")
        val SHOW_LABELS = booleanPreferencesKey("show_labels")
        val BLUR_LEVEL = stringPreferencesKey("blur_level")
        val BLUR_ON_SHUFFLE = booleanPreferencesKey("blur_on_shuffle")
        val BLUR_ON_DISTRACTING = booleanPreferencesKey("blur_on_distracting")
        val BLUR_ON_FOCUS = booleanPreferencesKey("blur_on_focus")
        val PAGE_COUNT = intPreferencesKey("page_count")
        val DEFAULT_PAGE_INDEX = intPreferencesKey("default_page_index")
        val CROSS_PAGE_SHUFFLE = booleanPreferencesKey("cross_page_shuffle")
        val DOCK_COUNT = intPreferencesKey("dock_count")
        val DOCK_SHOW_BACKGROUND = booleanPreferencesKey("dock_show_background")
        val DOCK_SHOW_LABELS = booleanPreferencesKey("dock_show_labels")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val LAST_SHUFFLE_TIME = longPreferencesKey("last_shuffle_time")
        val HIDE_DISTRACTING_IN_FOCUS = booleanPreferencesKey("hide_distracting_in_focus")
        val FOCUS_SEARCH_ENABLED = booleanPreferencesKey("focus_search_enabled")
        val GESTURE_SWIPE_UP = stringPreferencesKey("gesture_swipe_up")
        val GESTURE_SWIPE_DOWN = stringPreferencesKey("gesture_swipe_down")
        val GESTURE_DOUBLE_TAP = stringPreferencesKey("gesture_double_tap")
        val PREVIOUS_LAYOUT_BACKUP = stringPreferencesKey("previous_layout_backup")
    }

    val homeModeFlow: Flow<HomeMode> = context.dataStore.data.map { prefs ->
        val name = prefs[Keys.HOME_MODE] ?: HomeMode.NORMAL.name
        runCatching { HomeMode.valueOf(name) }.getOrDefault(HomeMode.NORMAL)
    }

    val shuffleIntervalFlow: Flow<ShuffleInterval> = context.dataStore.data.map { prefs ->
        val minutes = prefs[Keys.SHUFFLE_INTERVAL] ?: ShuffleInterval.MIN_30.minutes
        ShuffleInterval.fromMinutes(minutes)
    }

    val isShuffleEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SHUFFLE_ENABLED] ?: true
    }

    val isShufflePausedFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SHUFFLE_PAUSED] ?: false
    }

    val shuffleStrategyFlow: Flow<ShuffleStrategy> = context.dataStore.data.map { prefs ->
        val name = prefs[Keys.SHUFFLE_STRATEGY] ?: ShuffleStrategy.FULL_RANDOM.name
        runCatching { ShuffleStrategy.valueOf(name) }.getOrDefault(ShuffleStrategy.FULL_RANDOM)
    }

    val occupancyOptionFlow: Flow<OccupancyOption> = context.dataStore.data.map { prefs ->
        val name = prefs[Keys.OCCUPANCY_OPTION] ?: OccupancyOption.PRESERVE_EMPTY_SPACES.name
        runCatching { OccupancyOption.valueOf(name) }.getOrDefault(OccupancyOption.PRESERVE_EMPTY_SPACES)
    }

    val drawerShuffleEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DRAWER_SHUFFLE_ENABLED] ?: false
    }

    val themeModeFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE] ?: "DARK"
    }

    val gridColumnsFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.GRID_COLUMNS] ?: 4
    }

    val gridSpacingFlow: Flow<GridSpacing> = context.dataStore.data.map { prefs ->
        val name = prefs[Keys.GRID_SPACING] ?: GridSpacing.COMFORTABLE.name
        runCatching { GridSpacing.valueOf(name) }.getOrDefault(GridSpacing.COMFORTABLE)
    }

    val iconSizeFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.ICON_SIZE] ?: "MEDIUM"
    }

    val showLabelsFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SHOW_LABELS] ?: true
    }

    val blurLevelFlow: Flow<BlurLevel> = context.dataStore.data.map { prefs ->
        val name = prefs[Keys.BLUR_LEVEL] ?: BlurLevel.DISABLED.name
        runCatching { BlurLevel.valueOf(name) }.getOrDefault(BlurLevel.DISABLED)
    }

    val blurOnShuffleFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BLUR_ON_SHUFFLE] ?: true
    }

    val blurOnDistractingFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BLUR_ON_DISTRACTING] ?: false
    }

    val blurOnFocusFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BLUR_ON_FOCUS] ?: false
    }

    val pageCountFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.PAGE_COUNT] ?: 2
    }

    val defaultPageIndexFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_PAGE_INDEX] ?: 0
    }

    val crossPageShuffleFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.CROSS_PAGE_SHUFFLE] ?: true
    }

    val dockCountFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.DOCK_COUNT] ?: 5
    }

    val dockShowBackgroundFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DOCK_SHOW_BACKGROUND] ?: true
    }

    val dockShowLabelsFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DOCK_SHOW_LABELS] ?: false
    }

    val onboardingCompletedFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_COMPLETED] ?: false
    }

    val lastShuffleTimeFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_SHUFFLE_TIME] ?: 0L
    }

    val hideDistractingInFocusFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HIDE_DISTRACTING_IN_FOCUS] ?: false
    }

    val focusSearchEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.FOCUS_SEARCH_ENABLED] ?: true
    }

    val gestureSwipeUpFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.GESTURE_SWIPE_UP] ?: "DRAWER"
    }

    val gestureSwipeDownFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.GESTURE_SWIPE_DOWN] ?: "NOTIFICATIONS"
    }

    val gestureDoubleTapFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.GESTURE_DOUBLE_TAP] ?: "NONE"
    }

    val previousLayoutBackupFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.PREVIOUS_LAYOUT_BACKUP] ?: ""
    }

    suspend fun setHomeMode(mode: HomeMode) {
        context.dataStore.edit { prefs -> prefs[Keys.HOME_MODE] = mode.name }
    }

    suspend fun setShuffleInterval(interval: ShuffleInterval) {
        context.dataStore.edit { prefs -> prefs[Keys.SHUFFLE_INTERVAL] = interval.minutes }
    }

    suspend fun setShuffleEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.SHUFFLE_ENABLED] = enabled }
    }

    suspend fun setShufflePaused(paused: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.SHUFFLE_PAUSED] = paused }
    }

    suspend fun setShuffleStrategy(strategy: ShuffleStrategy) {
        context.dataStore.edit { prefs -> prefs[Keys.SHUFFLE_STRATEGY] = strategy.name }
    }

    suspend fun setOccupancyOption(occupancy: OccupancyOption) {
        context.dataStore.edit { prefs -> prefs[Keys.OCCUPANCY_OPTION] = occupancy.name }
    }

    suspend fun setDrawerShuffleEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.DRAWER_SHUFFLE_ENABLED] = enabled }
    }

    suspend fun setThemeMode(theme: String) {
        context.dataStore.edit { prefs -> prefs[Keys.THEME_MODE] = theme }
    }

    suspend fun setGridColumns(cols: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.GRID_COLUMNS] = cols.coerceIn(3, 6) }
    }

    suspend fun setGridSpacing(spacing: GridSpacing) {
        context.dataStore.edit { prefs -> prefs[Keys.GRID_SPACING] = spacing.name }
    }

    suspend fun setIconSize(size: String) {
        context.dataStore.edit { prefs -> prefs[Keys.ICON_SIZE] = size }
    }

    suspend fun setShowLabels(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.SHOW_LABELS] = show }
    }

    suspend fun setBlurLevel(level: BlurLevel) {
        context.dataStore.edit { prefs -> prefs[Keys.BLUR_LEVEL] = level.name }
    }

    suspend fun setBlurOnShuffle(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.BLUR_ON_SHUFFLE] = enabled }
    }

    suspend fun setBlurOnDistracting(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.BLUR_ON_DISTRACTING] = enabled }
    }

    suspend fun setBlurOnFocus(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.BLUR_ON_FOCUS] = enabled }
    }

    suspend fun setPageCount(count: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.PAGE_COUNT] = count.coerceAtLeast(1) }
    }

    suspend fun setDefaultPageIndex(index: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.DEFAULT_PAGE_INDEX] = index.coerceAtLeast(0) }
    }

    suspend fun setCrossPageShuffle(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.CROSS_PAGE_SHUFFLE] = enabled }
    }

    suspend fun setDockCount(count: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.DOCK_COUNT] = count.coerceIn(3, 7) }
    }

    suspend fun setDockShowBackground(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.DOCK_SHOW_BACKGROUND] = show }
    }

    suspend fun setDockShowLabels(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.DOCK_SHOW_LABELS] = show }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setLastShuffleTime(timestamp: Long) {
        context.dataStore.edit { prefs -> prefs[Keys.LAST_SHUFFLE_TIME] = timestamp }
    }

    suspend fun setHideDistractingInFocus(hide: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.HIDE_DISTRACTING_IN_FOCUS] = hide }
    }

    suspend fun setFocusSearchEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.FOCUS_SEARCH_ENABLED] = enabled }
    }

    suspend fun setGestureSwipeUp(gesture: String) {
        context.dataStore.edit { prefs -> prefs[Keys.GESTURE_SWIPE_UP] = gesture }
    }

    suspend fun setGestureSwipeDown(gesture: String) {
        context.dataStore.edit { prefs -> prefs[Keys.GESTURE_SWIPE_DOWN] = gesture }
    }

    suspend fun setGestureDoubleTap(gesture: String) {
        context.dataStore.edit { prefs -> prefs[Keys.GESTURE_DOUBLE_TAP] = gesture }
    }

    suspend fun setPreviousLayoutBackup(json: String) {
        context.dataStore.edit { prefs -> prefs[Keys.PREVIOUS_LAYOUT_BACKUP] = json }
    }

    open suspend fun getCurrentBackupConfig(): BackupPreferencesConfig {
        val prefs = context.dataStore.data.first()
        val pageCount = (prefs[Keys.PAGE_COUNT] ?: 2).coerceAtLeast(1)
        return BackupPreferencesConfig(
            homeMode = runCatching { HomeMode.valueOf(prefs[Keys.HOME_MODE] ?: HomeMode.NORMAL.name) }
                .getOrDefault(HomeMode.NORMAL),
            shuffleInterval = ShuffleInterval.fromMinutes(prefs[Keys.SHUFFLE_INTERVAL] ?: ShuffleInterval.MIN_30.minutes),
            shuffleEnabled = prefs[Keys.SHUFFLE_ENABLED] ?: true,
            shufflePaused = prefs[Keys.SHUFFLE_PAUSED] ?: false,
            shuffleStrategy = runCatching { ShuffleStrategy.valueOf(prefs[Keys.SHUFFLE_STRATEGY] ?: ShuffleStrategy.FULL_RANDOM.name) }
                .getOrDefault(ShuffleStrategy.FULL_RANDOM),
            occupancyOption = runCatching { OccupancyOption.valueOf(prefs[Keys.OCCUPANCY_OPTION] ?: OccupancyOption.PRESERVE_EMPTY_SPACES.name) }
                .getOrDefault(OccupancyOption.PRESERVE_EMPTY_SPACES),
            drawerShuffleEnabled = prefs[Keys.DRAWER_SHUFFLE_ENABLED] ?: false,
            themeMode = prefs[Keys.THEME_MODE] ?: "DARK",
            gridColumns = (prefs[Keys.GRID_COLUMNS] ?: 4).coerceIn(3, 6),
            gridSpacing = runCatching { GridSpacing.valueOf(prefs[Keys.GRID_SPACING] ?: GridSpacing.COMFORTABLE.name) }
                .getOrDefault(GridSpacing.COMFORTABLE),
            iconSize = prefs[Keys.ICON_SIZE] ?: "MEDIUM",
            showLabels = prefs[Keys.SHOW_LABELS] ?: true,
            blurLevel = runCatching { BlurLevel.valueOf(prefs[Keys.BLUR_LEVEL] ?: BlurLevel.DISABLED.name) }
                .getOrDefault(BlurLevel.DISABLED),
            blurOnShuffle = prefs[Keys.BLUR_ON_SHUFFLE] ?: true,
            blurOnDistracting = prefs[Keys.BLUR_ON_DISTRACTING] ?: false,
            blurOnFocus = prefs[Keys.BLUR_ON_FOCUS] ?: false,
            pageCount = pageCount,
            defaultPageIndex = (prefs[Keys.DEFAULT_PAGE_INDEX] ?: 0).coerceIn(0, pageCount - 1),
            crossPageShuffle = prefs[Keys.CROSS_PAGE_SHUFFLE] ?: true,
            dockCount = (prefs[Keys.DOCK_COUNT] ?: 5).coerceIn(3, 7),
            dockShowBackground = prefs[Keys.DOCK_SHOW_BACKGROUND] ?: true,
            dockShowLabels = prefs[Keys.DOCK_SHOW_LABELS] ?: false,
            hideDistractingInFocus = prefs[Keys.HIDE_DISTRACTING_IN_FOCUS] ?: false,
            focusSearchEnabled = prefs[Keys.FOCUS_SEARCH_ENABLED] ?: true,
            gestureSwipeUp = prefs[Keys.GESTURE_SWIPE_UP] ?: "DRAWER",
            gestureSwipeDown = prefs[Keys.GESTURE_SWIPE_DOWN] ?: "NOTIFICATIONS",
            gestureDoubleTap = prefs[Keys.GESTURE_DOUBLE_TAP] ?: "NONE"
        )
    }

    open suspend fun applyBackupConfig(
        config: BackupPreferencesConfig,
        previousLayoutBackupJson: String? = null
    ) {
        context.dataStore.edit { prefs ->
            if (previousLayoutBackupJson != null) {
                prefs[Keys.PREVIOUS_LAYOUT_BACKUP] = previousLayoutBackupJson
            }
            prefs[Keys.HOME_MODE] = config.homeMode.name
            prefs[Keys.SHUFFLE_INTERVAL] = config.shuffleInterval.minutes
            prefs[Keys.SHUFFLE_ENABLED] = config.shuffleEnabled
            prefs[Keys.SHUFFLE_PAUSED] = config.shufflePaused
            prefs[Keys.SHUFFLE_STRATEGY] = config.shuffleStrategy.name
            prefs[Keys.OCCUPANCY_OPTION] = config.occupancyOption.name
            prefs[Keys.DRAWER_SHUFFLE_ENABLED] = config.drawerShuffleEnabled
            prefs[Keys.THEME_MODE] = config.themeMode
            prefs[Keys.GRID_COLUMNS] = config.gridColumns.coerceIn(3, 6)
            prefs[Keys.GRID_SPACING] = config.gridSpacing.name
            prefs[Keys.ICON_SIZE] = config.iconSize
            prefs[Keys.SHOW_LABELS] = config.showLabels
            prefs[Keys.BLUR_LEVEL] = config.blurLevel.name
            prefs[Keys.BLUR_ON_SHUFFLE] = config.blurOnShuffle
            prefs[Keys.BLUR_ON_DISTRACTING] = config.blurOnDistracting
            prefs[Keys.BLUR_ON_FOCUS] = config.blurOnFocus
            prefs[Keys.PAGE_COUNT] = config.pageCount.coerceAtLeast(1)
            prefs[Keys.DEFAULT_PAGE_INDEX] = config.defaultPageIndex.coerceIn(0, (config.pageCount - 1).coerceAtLeast(0))
            prefs[Keys.CROSS_PAGE_SHUFFLE] = config.crossPageShuffle
            prefs[Keys.DOCK_COUNT] = config.dockCount.coerceIn(3, 7)
            prefs[Keys.DOCK_SHOW_BACKGROUND] = config.dockShowBackground
            prefs[Keys.DOCK_SHOW_LABELS] = config.dockShowLabels
            prefs[Keys.HIDE_DISTRACTING_IN_FOCUS] = config.hideDistractingInFocus
            prefs[Keys.FOCUS_SEARCH_ENABLED] = config.focusSearchEnabled
            prefs[Keys.GESTURE_SWIPE_UP] = config.gestureSwipeUp
            prefs[Keys.GESTURE_SWIPE_DOWN] = config.gestureSwipeDown
            prefs[Keys.GESTURE_DOUBLE_TAP] = config.gestureDoubleTap
        }
    }
}

data class BackupPreferencesConfig(
    val homeMode: HomeMode,
    val shuffleInterval: ShuffleInterval,
    val shuffleEnabled: Boolean,
    val shufflePaused: Boolean,
    val shuffleStrategy: ShuffleStrategy,
    val occupancyOption: OccupancyOption,
    val drawerShuffleEnabled: Boolean,
    val themeMode: String,
    val gridColumns: Int,
    val gridSpacing: GridSpacing,
    val iconSize: String,
    val showLabels: Boolean,
    val blurLevel: BlurLevel,
    val blurOnShuffle: Boolean,
    val blurOnDistracting: Boolean,
    val blurOnFocus: Boolean,
    val pageCount: Int,
    val defaultPageIndex: Int,
    val crossPageShuffle: Boolean,
    val dockCount: Int,
    val dockShowBackground: Boolean,
    val dockShowLabels: Boolean,
    val hideDistractingInFocus: Boolean,
    val focusSearchEnabled: Boolean,
    val gestureSwipeUp: String,
    val gestureSwipeDown: String,
    val gestureDoubleTap: String
)

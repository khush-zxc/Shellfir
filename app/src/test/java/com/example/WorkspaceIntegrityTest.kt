package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.DrawerLayoutEntity
import com.example.data.database.FocusAppEntity
import com.example.data.database.FolderEntity
import com.example.data.database.FolderMemberEntity
import com.example.data.database.InstalledAppEntity
import com.example.data.database.ShellfriDatabase
import com.example.data.database.WorkspaceContainers
import com.example.data.database.WorkspaceItemEntity
import com.example.data.database.WorkspaceItemTypes
import com.example.data.database.WorkspacePageEntity
import com.example.data.preferences.ShellfriPreferences
import com.example.domain.repository.AppRepository
import com.example.domain.validation.WorkspaceValidator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkspaceIntegrityTest {

    private lateinit var context: Context
    private lateinit var db: ShellfriDatabase
    private lateinit var preferences: ShellfriPreferences
    private lateinit var repository: AppRepository
    private val validator = WorkspaceValidator()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ShellfriDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = ShellfriPreferences(context)
        repository = AppRepository(
            context = context,
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            shuffleHistoryDao = db.shuffleHistoryDao(),
            preferences = preferences,
            database = db
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val componentsByPkg = mutableMapOf<String, MutableSet<ComponentName>>()

    private fun registerLauncherActivity(pkg: String, activityName: String, label: String) {
        val shadowPm = shadowOf(context.packageManager)
        val component = ComponentName(pkg, activityName)
        componentsByPkg.getOrPut(pkg) { mutableSetOf() }.add(component)
        shadowPm.addActivityIfNotPresent(component)
        runCatching {
            val info = context.packageManager.getActivityInfo(component, 0)
            info.nonLocalizedLabel = label
            info.applicationInfo?.nonLocalizedLabel = label
            shadowPm.addOrUpdateActivity(info)
        }
        shadowPm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        )
        context.packageManager.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun uninstallPackage(pkg: String) {
        componentsByPkg[pkg].orEmpty().forEach { component ->
            context.packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    @Test
    fun `validator rejects duplicate component identities invalid pages ranks folders dock and widget collisions`() {
        val pages = listOf(WorkspacePageEntity("p0", pageIndex = 0, isDefault = true))
        val apps = listOf(
            InstalledAppEntity("com.a", "com.a.Main", "A"),
            InstalledAppEntity("com.b", "com.b.Main", "B")
        )

        // 1. Duplicate installed component identity
        val dupInstalled = validator.validateNormalizedWorkspace(
            installedApps = listOf(apps[0], apps[0]),
            pages = pages,
            workspaceItems = emptyList(),
            folders = emptyList(),
            folderMembers = emptyList(),
            drawerLayouts = emptyList()
        )
        assertFalse(dupInstalled.isValid)

        // 2. Invalid page reference & non-null dock pageId
        val badPageRef = validator.validateNormalizedWorkspace(
            installedApps = apps,
            pages = pages,
            workspaceItems = listOf(
                WorkspaceItemEntity(
                    itemId = "bad_page",
                    itemType = WorkspaceItemTypes.APP,
                    containerType = WorkspaceContainers.HOME,
                    pageId = "missing_page",
                    packageName = "com.a",
                    activityName = "com.a.Main",
                    rank = 0
                ),
                WorkspaceItemEntity(
                    itemId = "bad_dock_page",
                    itemType = WorkspaceItemTypes.APP,
                    containerType = WorkspaceContainers.DOCK,
                    pageId = "p0",
                    packageName = "com.b",
                    activityName = "com.b.Main",
                    rank = 0
                )
            ),
            folders = emptyList(),
            folderMembers = emptyList(),
            drawerLayouts = emptyList()
        )
        assertFalse(badPageRef.isValid)
        assertTrue(badPageRef.errors.any { it.contains("missing pageId") })
        assertTrue(badPageRef.errors.any { it.contains("must not reference pageId") })

        // 3. Duplicate placement ranks in HOME and DOCK
        val dupRanks = validator.validateNormalizedWorkspace(
            installedApps = apps,
            pages = pages,
            workspaceItems = listOf(
                WorkspaceItemEntity("h1", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "p0", "com.a", "com.a.Main", rank = 2),
                WorkspaceItemEntity("h2", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "p0", "com.b", "com.b.Main", rank = 2)
            ),
            folders = emptyList(),
            folderMembers = emptyList(),
            drawerLayouts = emptyList()
        )
        assertFalse(dupRanks.isValid)
        assertTrue(dupRanks.errors.any { it.contains("Collision on HOME page") })

        // 4. Invalid folder references
        val badFolder = validator.validateNormalizedWorkspace(
            installedApps = apps,
            pages = pages,
            workspaceItems = listOf(
                WorkspaceItemEntity("f_item", WorkspaceItemTypes.FOLDER, WorkspaceContainers.HOME, "p0", folderId = "missing_folder", rank = 0)
            ),
            folders = emptyList(),
            folderMembers = listOf(
                FolderMemberEntity("missing_folder", "com.a", "com.a.Main", rank = 0)
            ),
            drawerLayouts = emptyList()
        )
        assertFalse(badFolder.isValid)
        assertTrue(badFolder.errors.any { it.contains("missing folderId") })

        // 5. Widget span collision and invalid appWidgetId
        val widgetCollision = validator.validateNormalizedWorkspace(
            installedApps = apps,
            pages = pages,
            workspaceItems = listOf(
                WorkspaceItemEntity("w1", WorkspaceItemTypes.WIDGET, WorkspaceContainers.HOME, "p0", appWidgetId = 10, columnSpan = 2, rowSpan = 1, rank = 0),
                WorkspaceItemEntity("h1", WorkspaceItemTypes.APP, WorkspaceContainers.HOME, "p0", "com.a", "com.a.Main", rank = 1)
            ),
            folders = emptyList(),
            folderMembers = emptyList(),
            drawerLayouts = emptyList()
        )
        assertFalse(widgetCollision.isValid)
        assertTrue(widgetCollision.errors.any { it.contains("Collision on HOME page") })
    }

    @Test
    fun `multiple launcher activities from one package behave independently across discovery flags and folders`() = runBlocking {
        registerLauncherActivity("com.suite.app", "com.suite.app.NotesActivity", "Suite Notes")
        registerLauncherActivity("com.suite.app", "com.suite.app.CamActivity", "Suite Camera")

        repository.discoverAndSyncApps()

        val apps = repository.allAppsFlow.first()
        assertEquals(2, apps.size)
        val notes = apps.first { it.activityName == "com.suite.app.NotesActivity" }
        val cam = apps.first { it.activityName == "com.suite.app.CamActivity" }

        // Independent home/dock/focus/drawer flags
        repository.toggleAppShuffled("com.suite.app", "com.suite.app.NotesActivity", true)
        repository.toggleAppPinnedToDock("com.suite.app", "com.suite.app.CamActivity", true)
        repository.toggleFocusVisibility("com.suite.app", "com.suite.app.NotesActivity", true)
        repository.toggleAppDrawerShuffled("com.suite.app", "com.suite.app.CamActivity", true)

        val updated = repository.allAppsFlow.first()
        val updatedNotes = updated.first { it.activityName == "com.suite.app.NotesActivity" }
        val updatedCam = updated.first { it.activityName == "com.suite.app.CamActivity" }

        assertTrue(updatedNotes.isShuffled)
        assertFalse(updatedNotes.isPinnedToDock)
        assertTrue(updatedNotes.isVisibleInFocus)
        assertFalse(updatedNotes.isDrawerShuffled)

        assertFalse(updatedCam.isShuffled)
        assertTrue(updatedCam.isPinnedToDock)
        assertFalse(updatedCam.isVisibleInFocus)
        assertTrue(updatedCam.isDrawerShuffled)
    }

    @Test
    fun `uninstall archives placement and reinstall restores exact component without colliding`() = runBlocking {
        registerLauncherActivity("com.keep.app", "com.keep.app.Main", "Keep")
        registerLauncherActivity("com.temp.app", "com.temp.app.Main", "Temp")
        repository.discoverAndSyncApps()

        repository.setAppPageAndSlot("com.temp.app", "com.temp.app.Main", pageIndex = 0, slotIndex = 5)
        repository.toggleAppShuffled("com.temp.app", "com.temp.app.Main", true)

        // Uninstall com.temp.app
        uninstallPackage("com.temp.app")
        repository.discoverAndSyncApps()

        // Active items must no longer reference com.temp.app, while archived record keeps placement
        assertEquals(1, repository.allAppsFlow.first().size)
        assertNull(db.workspaceDao().getWorkspaceItemsList().find { it.packageName == "com.temp.app" })
        val archived = db.installedAppDao().getAllAppsList().first { it.packageName == "com.temp.app" }
        assertFalse(archived.isInstalled)
        assertNotNull(archived.archivedPlacementJson)

        // Reinstall with DIFFERENT activityName must NOT restore previous component's archive
        registerLauncherActivity("com.temp.app", "com.temp.app.NewEntry", "Temp New")
        repository.discoverAndSyncApps()
        val newComp = repository.allAppsFlow.first().first { it.packageName == "com.temp.app" }
        assertEquals("com.temp.app.NewEntry", newComp.activityName)
        assertFalse(newComp.isShuffled)

        // Now install original activityName -> exact archived placement & shuffle flag restored
        registerLauncherActivity("com.temp.app", "com.temp.app.Main", "Temp")
        repository.discoverAndSyncApps()
        val restoredComp = repository.allAppsFlow.first().first {
            it.packageName == "com.temp.app" && it.activityName == "com.temp.app.Main"
        }
        assertTrue(restoredComp.isShuffled)
        assertEquals(5, restoredComp.slotIndex)
    }

    @Test
    fun `dock ordering stays independent from home slots and shuffle snapshot undo restores pre-shuffle layout`() = runBlocking {
        registerLauncherActivity("com.one", "com.one.Main", "One")
        registerLauncherActivity("com.two", "com.two.Main", "Two")
        registerLauncherActivity("com.three", "com.three.Main", "Three")
        registerLauncherActivity("com.dock", "com.dock.Main", "Dock")
        repository.discoverAndSyncApps()

        repository.toggleAppPinnedToDock("com.dock", "com.dock.Main", true)
        repository.toggleAppShuffled("com.one", "com.one.Main", true)
        repository.toggleAppShuffled("com.two", "com.two.Main", true)
        repository.toggleAppShuffled("com.three", "com.three.Main", true)
        repository.toggleAppDrawerShuffled("com.one", "com.one.Main", true)
        repository.toggleAppDrawerShuffled("com.two", "com.two.Main", true)
        preferences.setDrawerShuffleEnabled(true)

        val beforeApps = repository.allAppsFlow.first().associateBy { it.componentKey }
        val beforeDockRank = beforeApps["com.dock/com.dock.Main"]!!.dockRank
        val beforeHomeSlots = mapOf(
            "com.one/com.one.Main" to beforeApps["com.one/com.one.Main"]!!.slotIndex,
            "com.two/com.two.Main" to beforeApps["com.two/com.two.Main"]!!.slotIndex,
            "com.three/com.three.Main" to beforeApps["com.three/com.three.Main"]!!.slotIndex
        )

        assertTrue(repository.performShuffle(triggerType = "MANUAL"))

        val afterShuffle = repository.allAppsFlow.first().associateBy { it.componentKey }
        // Dock rank stays independent & HOME placement is preserved alongside DOCK
        assertEquals(beforeDockRank, afterShuffle["com.dock/com.dock.Main"]!!.dockRank)
        assertTrue(afterShuffle["com.dock/com.dock.Main"]!!.isPinnedToDock)
        assertTrue(afterShuffle["com.dock/com.dock.Main"]!!.isOnHomeScreen)
        assertEquals(beforeApps["com.dock/com.dock.Main"]!!.slotIndex, afterShuffle["com.dock/com.dock.Main"]!!.slotIndex)

        // Undo restores snapshot exactly
        assertTrue(repository.undoLastShuffle())
        val afterUndo = repository.allAppsFlow.first().associateBy { it.componentKey }
        for ((key, slot) in beforeHomeSlots) {
            assertEquals(slot, afterUndo[key]!!.slotIndex)
        }
    }
}

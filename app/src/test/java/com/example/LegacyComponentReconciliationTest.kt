package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.DrawerLayoutEntity
import com.example.data.database.FocusAppEntity
import com.example.data.database.FolderEntity
import com.example.data.database.FolderMemberEntity
import com.example.data.database.InstalledAppDao
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyComponentReconciliationTest {

    private lateinit var context: Context
    private lateinit var db: ShellfriDatabase
    private lateinit var preferences: ShellfriPreferences
    private lateinit var repository: AppRepository
    private val validator = WorkspaceValidator()
    private val registeredComponentsByPkg = mutableMapOf<String, MutableSet<ComponentName>>()

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

    private fun registerLauncherActivity(
        pkg: String,
        activityName: String,
        label: String,
        priority: Int = 0,
        category: String = Intent.CATEGORY_LAUNCHER,
        isSystem: Boolean = false
    ) {
        val shadowPm = shadowOf(context.packageManager)
        val component = ComponentName(pkg, activityName)
        registeredComponentsByPkg.getOrPut(pkg) { mutableSetOf() }.add(component)
        shadowPm.addActivityIfNotPresent(component)
        runCatching {
            val info = context.packageManager.getActivityInfo(component, 0)
            info.nonLocalizedLabel = label
            info.applicationInfo?.let { appInfo ->
                appInfo.nonLocalizedLabel = label
                if (isSystem) {
                    appInfo.flags = appInfo.flags or android.content.pm.ApplicationInfo.FLAG_SYSTEM
                }
            }
            shadowPm.addOrUpdateActivity(info)
        }
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(category)
            this.priority = priority
        }
        shadowPm.addIntentFilterForActivity(component, filter)
        context.packageManager.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun uninstallPackage(pkg: String) {
        registeredComponentsByPkg[pkg].orEmpty().forEach { component ->
            context.packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun createAndMigrateV3Database(
        dbName: String,
        seedBlock: (SQLiteDatabase) -> Unit
    ): ShellfriDatabase {
        context.deleteDatabase(dbName)
        val helper = object : SQLiteOpenHelper(context, dbName, null, 3) {
            override fun onCreate(sqlDb: SQLiteDatabase) {
                sqlDb.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `apps` (
                        `packageName` TEXT NOT NULL PRIMARY KEY,
                        `activityName` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `isShuffled` INTEGER NOT NULL,
                        `isFixed` INTEGER NOT NULL,
                        `slotIndex` INTEGER NOT NULL,
                        `pageIndex` INTEGER NOT NULL,
                        `folderId` TEXT,
                        `isPinnedToDock` INTEGER NOT NULL,
                        `isOnHomeScreen` INTEGER NOT NULL,
                        `isVisibleInFocus` INTEGER NOT NULL,
                        `isEssentialInFocus` INTEGER NOT NULL,
                        `isHidden` INTEGER NOT NULL,
                        `isSystemApp` INTEGER NOT NULL,
                        `customCategory` TEXT NOT NULL,
                        `lastUsedTimestamp` INTEGER NOT NULL,
                        `drawerSlotIndex` INTEGER NOT NULL,
                        `isDrawerShuffled` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                sqlDb.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `folders` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `pageIndex` INTEGER NOT NULL,
                        `slotIndex` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                sqlDb.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `shuffle_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `shuffledAppCount` INTEGER NOT NULL,
                        `triggerType` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                seedBlock(sqlDb)
            }

            override fun onUpgrade(sqlDb: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.close()
        return ShellfriDatabase.buildDatabase(context, dbName)
    }

    @Test
    fun `migrated blank activity with home placement reconciles metadata and layout without duplicates`() = runBlocking {
        val dbName = "reconcile_home_v3_v4.db"
        val migratedDb = createAndMigrateV3Database(dbName) { sqlDb ->
            sqlDb.execSQL(
                """
                INSERT INTO `apps` VALUES (
                    'com.legacy.homeapp', '', 'Old Label',
                    1, 0, 7, 2, NULL, 0, 1, 0, 0, 1, 0, 'Productivity', 987654321, -1, 0
                )
                """.trimIndent()
            )
        }
        try {
            val migratedRepo = AppRepository(
                context = context,
                installedAppDao = migratedDb.installedAppDao(),
                workspaceDao = migratedDb.workspaceDao(),
                shuffleHistoryDao = migratedDb.shuffleHistoryDao(),
                preferences = preferences,
                database = migratedDb
            )

            registerLauncherActivity("com.legacy.homeapp", "com.legacy.homeapp.LaunchActivity", "Home App")
            // Also register an unrelated new app that would have stolen page/rank if Step 1 freed slots early
            registerLauncherActivity("com.brandnew.app", "com.brandnew.app.Main", "Brand New")

            migratedRepo.discoverAndSyncApps()

            val apps = migratedDb.installedAppDao().getAllAppsList()
            assertEquals(2, apps.size)
            assertNull(apps.find { it.packageName == "com.legacy.homeapp" && it.activityName == "" })

            val reconciled = apps.single { it.packageName == "com.legacy.homeapp" }
            assertEquals("com.legacy.homeapp.LaunchActivity", reconciled.activityName)
            assertEquals("Home App", reconciled.label)
            assertEquals("Productivity", reconciled.customCategory)
            assertEquals(987654321L, reconciled.lastUsedTimestamp)
            assertTrue(reconciled.isInstalled)
            assertNull(reconciled.archivedPlacementJson)

            val homeItems = migratedDb.workspaceDao().getWorkspaceItemsList()
                .filter { it.packageName == "com.legacy.homeapp" }
            assertEquals(1, homeItems.size)
            val homeItem = homeItems.single()
            assertEquals("com.legacy.homeapp.LaunchActivity", homeItem.activityName)
            assertEquals(WorkspaceContainers.HOME, homeItem.containerType)
            assertEquals("page_2", homeItem.pageId)
            assertEquals(7, homeItem.rank)
            assertTrue(homeItem.isShuffled)
            assertFalse(homeItem.isFixed)
            assertTrue(homeItem.isHidden)

            val validation = validator.validateNormalizedWorkspace(
                installedApps = apps,
                pages = migratedDb.workspaceDao().getPagesList(),
                workspaceItems = migratedDb.workspaceDao().getWorkspaceItemsList(),
                folders = migratedDb.workspaceDao().getFoldersList(),
                folderMembers = migratedDb.workspaceDao().getFolderMembersList(),
                drawerLayouts = migratedDb.workspaceDao().getDrawerLayoutsList(),
                focusApps = migratedDb.workspaceDao().getFocusAppsList()
            )
            assertTrue("Expected valid workspace: ${validation.errors}", validation.isValid)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `migrated blank activity with dock placement reconciles dock rank and flags`() = runBlocking {
        val dbName = "reconcile_dock_v3_v4.db"
        val migratedDb = createAndMigrateV3Database(dbName) { sqlDb ->
            sqlDb.execSQL(
                """
                INSERT INTO `apps` VALUES (
                    'com.legacy.firstdock', 'com.legacy.firstdock.Main', 'First Dock',
                    0, 1, 0, 0, NULL, 1, 1, 1, 1, 0, 1, 'Comm', 100, -1, 0
                )
                """.trimIndent()
            )
            sqlDb.execSQL(
                """
                INSERT INTO `apps` VALUES (
                    'com.legacy.dockapp', '', 'Dock Legacy',
                    0, 1, 4, 0, NULL, 1, 1, 0, 0, 1, 0, 'QuickDock', 55555, -1, 0
                )
                """.trimIndent()
            )
        }
        try {
            val migratedRepo = AppRepository(
                context = context,
                installedAppDao = migratedDb.installedAppDao(),
                workspaceDao = migratedDb.workspaceDao(),
                shuffleHistoryDao = migratedDb.shuffleHistoryDao(),
                preferences = preferences,
                database = migratedDb
            )
            registerLauncherActivity("com.legacy.firstdock", "com.legacy.firstdock.Main", "First Dock", isSystem = true)
            registerLauncherActivity("com.legacy.dockapp", "com.legacy.dockapp.EntryActivity", "Dock App")

            migratedRepo.discoverAndSyncApps()

            assertNull(
                migratedDb.installedAppDao().getAllAppsList()
                    .find { it.packageName == "com.legacy.dockapp" && it.activityName == "" }
            )
            val dockItems = migratedDb.workspaceDao().getWorkspaceItemsList()
                .filter { it.containerType == WorkspaceContainers.DOCK }
                .sortedBy { it.rank }
            assertEquals(2, dockItems.size)
            val targetDock = dockItems.single { it.packageName == "com.legacy.dockapp" }
            assertEquals("com.legacy.dockapp.EntryActivity", targetDock.activityName)
            assertEquals(1, targetDock.rank)
            assertNull(targetDock.pageId)
            assertTrue(targetDock.isFixed)
            assertTrue(targetDock.isHidden)

            val reconciledApp = migratedDb.installedAppDao().getAllAppsList()
                .single { it.packageName == "com.legacy.dockapp" }
            assertEquals("QuickDock", reconciledApp.customCategory)
            assertEquals(55555L, reconciledApp.lastUsedTimestamp)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `migrated blank activity preserves folder membership ordering drawer and focus configuration`() = runBlocking {
        val dbName = "reconcile_folder_drawer_focus_v3_v4.db"
        val migratedDb = createAndMigrateV3Database(dbName) { sqlDb ->
            sqlDb.execSQL("INSERT INTO `folders` VALUES ('f_work', 'Work', 0, 2, 7777)")
            sqlDb.execSQL(
                """
                INSERT INTO `apps` VALUES (
                    'com.alpha.first', 'com.alpha.first.Main', 'Alpha',
                    0, 0, 0, 0, 'f_work', 0, 0, 0, 0, 0, 0, 'Work', 111, 0, 0
                )
                """.trimIndent()
            )
            sqlDb.execSQL(
                """
                INSERT INTO `apps` VALUES (
                    'com.legacy.folderapp', '', 'Legacy Folder App',
                    1, 0, 1, 0, 'f_work', 0, 0, 1, 1, 1, 0, 'WorkApps', 22222, 6, 1
                )
                """.trimIndent()
            )
        }
        try {
            val migratedRepo = AppRepository(
                context = context,
                installedAppDao = migratedDb.installedAppDao(),
                workspaceDao = migratedDb.workspaceDao(),
                shuffleHistoryDao = migratedDb.shuffleHistoryDao(),
                preferences = preferences,
                database = migratedDb
            )
            registerLauncherActivity("com.alpha.first", "com.alpha.first.Main", "Alpha")
            registerLauncherActivity("com.legacy.folderapp", "com.legacy.folderapp.WorkMain", "Work App")

            migratedRepo.discoverAndSyncApps()

            // Legacy blank record removed
            assertNull(
                migratedDb.installedAppDao().getAllAppsList()
                    .find { it.packageName == "com.legacy.folderapp" && it.activityName == "" }
            )

            // Folder membership & exact rank ordering preserved (0 = alpha, 1 = legacy.folderapp)
            val members = migratedDb.workspaceDao().getFolderMembersList()
                .filter { it.folderId == "f_work" }
                .sortedBy { it.rank }
            assertEquals(2, members.size)
            assertEquals("com.alpha.first", members[0].packageName)
            assertEquals(0, members[0].rank)
            assertEquals("com.legacy.folderapp", members[1].packageName)
            assertEquals("com.legacy.folderapp.WorkMain", members[1].activityName)
            assertEquals(1, members[1].rank)
            assertTrue(members[1].isShuffled)
            assertFalse(members[1].isFixed)

            // Drawer config transferred & legacy row deleted
            val drawers = migratedDb.workspaceDao().getDrawerLayoutsList()
                .filter { it.packageName == "com.legacy.folderapp" }
            assertEquals(1, drawers.size)
            assertEquals("com.legacy.folderapp.WorkMain", drawers.single().activityName)
            assertEquals(6, drawers.single().drawerRank)
            assertTrue(drawers.single().isDrawerShuffled)
            assertTrue(drawers.single().isHidden)
            assertFalse(drawers.single().isFixed)

            // Focus config transferred & legacy row deleted
            val focusRows = migratedDb.workspaceDao().getFocusAppsList()
                .filter { it.packageName == "com.legacy.folderapp" }
            assertEquals(1, focusRows.size)
            assertEquals("com.legacy.folderapp.WorkMain", focusRows.single().activityName)
            assertTrue(focusRows.single().isVisibleInFocus)
            assertTrue(focusRows.single().isEssentialInFocus)

            val validation = validator.validateNormalizedWorkspace(
                installedApps = migratedDb.installedAppDao().getAllAppsList(),
                pages = migratedDb.workspaceDao().getPagesList(),
                workspaceItems = migratedDb.workspaceDao().getWorkspaceItemsList(),
                folders = migratedDb.workspaceDao().getFoldersList(),
                folderMembers = migratedDb.workspaceDao().getFolderMembersList(),
                drawerLayouts = migratedDb.workspaceDao().getDrawerLayoutsList(),
                focusApps = migratedDb.workspaceDao().getFocusAppsList()
            )
            assertTrue("Expected valid workspace: ${validation.errors}", validation.isValid)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `ambiguous multi activity package preserves legacy record and archived placement without guessing`() = runBlocking {
        db.workspaceDao().upsertPage(WorkspacePageEntity("page_0", pageIndex = 0, isDefault = true))
        db.installedAppDao().upsertApp(
            InstalledAppEntity(
                packageName = "com.suite.ambiguous",
                activityName = "",
                label = "Suite Legacy",
                customCategory = "SuiteCat",
                lastUsedTimestamp = 4242L,
                isInstalled = true
            )
        )
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity(
                itemId = "home_com.suite.ambiguous_",
                itemType = WorkspaceItemTypes.APP,
                containerType = WorkspaceContainers.HOME,
                pageId = "page_0",
                packageName = "com.suite.ambiguous",
                activityName = "",
                rank = 4,
                isFixed = true
            )
        )
        db.workspaceDao().upsertDrawerLayout(
            DrawerLayoutEntity("com.suite.ambiguous", "", drawerRank = 9, isDrawerShuffled = true, isHidden = true, isFixed = true)
        )
        db.workspaceDao().upsertFocusApp(
            FocusAppEntity("com.suite.ambiguous", "", isVisibleInFocus = true, isEssentialInFocus = true, customOrder = 3)
        )

        // Two equal-priority launcher activities -> strictly ambiguous
        registerLauncherActivity("com.suite.ambiguous", "com.suite.ambiguous.AlphaActivity", "Suite Alpha")
        registerLauncherActivity("com.suite.ambiguous", "com.suite.ambiguous.BetaActivity", "Suite Beta")

        repository.discoverAndSyncApps()

        val allApps = db.installedAppDao().getAllAppsList()
        val legacy = allApps.firstOrNull { it.packageName == "com.suite.ambiguous" && it.activityName == "" }
        assertNotNull("Ambiguous legacy record must be preserved", legacy)
        assertFalse(legacy!!.isInstalled)
        assertNotNull("Legacy archived placement must be preserved", legacy.archivedPlacementJson)
        assertEquals("SuiteCat", legacy.customCategory)
        assertEquals(4242L, legacy.lastUsedTimestamp)

        // Neither discovered activity may steal the legacy config
        val alpha = allApps.single { it.activityName == "com.suite.ambiguous.AlphaActivity" }
        val beta = allApps.single { it.activityName == "com.suite.ambiguous.BetaActivity" }
        assertTrue(alpha.isInstalled)
        assertTrue(beta.isInstalled)
        assertEquals("General", alpha.customCategory)
        assertEquals("General", beta.customCategory)

        val alphaItem = db.workspaceDao().getWorkspaceItemsList().single { it.activityName == "com.suite.ambiguous.AlphaActivity" }
        val betaItem = db.workspaceDao().getWorkspaceItemsList().single { it.activityName == "com.suite.ambiguous.BetaActivity" }
        assertFalse(alphaItem.isFixed)
        assertFalse(betaItem.isFixed)

        // Legacy drawer & focus remain preserved
        assertNotNull(db.workspaceDao().getDrawerLayoutsList().find { it.packageName == "com.suite.ambiguous" && it.activityName == "" })
        assertNotNull(db.workspaceDao().getFocusAppsList().find { it.packageName == "com.suite.ambiguous" && it.activityName == "" })
    }

    @Test
    fun `unambiguous primary launch activity in multi activity package reconciles legacy record`() = runBlocking {
        db.workspaceDao().upsertPage(WorkspacePageEntity("page_0", pageIndex = 0, isDefault = true))
        db.installedAppDao().upsertApp(
            InstalledAppEntity(
                packageName = "com.suite.primary",
                activityName = "",
                label = "Suite Primary",
                customCategory = "Media",
                lastUsedTimestamp = 8888L,
                isInstalled = true
            )
        )
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity(
                itemId = "home_com.suite.primary_",
                itemType = WorkspaceItemTypes.APP,
                containerType = WorkspaceContainers.HOME,
                pageId = "page_0",
                packageName = "com.suite.primary",
                activityName = "",
                rank = 3,
                isFixed = true
            )
        )

        registerLauncherActivity("com.suite.primary", "com.suite.primary.SecondaryActivity", "Secondary", priority = 0)
        registerLauncherActivity("com.suite.primary", "com.suite.primary.MainActivity", "Primary", priority = 100)

        repository.discoverAndSyncApps()

        val allApps = db.installedAppDao().getAllAppsList()
        assertNull(allApps.find { it.packageName == "com.suite.primary" && it.activityName == "" })
        val primary = allApps.single { it.activityName == "com.suite.primary.MainActivity" }
        val secondary = allApps.single { it.activityName == "com.suite.primary.SecondaryActivity" }
        assertEquals("Media", primary.customCategory)
        assertEquals(8888L, primary.lastUsedTimestamp)
        assertEquals("General", secondary.customCategory)

        val primaryItem = db.workspaceDao().getWorkspaceItemsList().single { it.activityName == "com.suite.primary.MainActivity" }
        assertEquals(3, primaryItem.rank)
        assertTrue(primaryItem.isFixed)
    }

    @Test
    fun `exact component record takes precedence over legacy blank record without overwriting`() = runBlocking {
        db.workspaceDao().upsertPage(WorkspacePageEntity("page_0", pageIndex = 0, isDefault = true))
        // Exact existing record
        db.installedAppDao().upsertApps(
            listOf(
                InstalledAppEntity(
                    packageName = "com.precedence.app",
                    activityName = "com.precedence.app.MainActivity",
                    label = "Exact App",
                    customCategory = "ExactCategory",
                    lastUsedTimestamp = 7000L,
                    isInstalled = true
                ),
                // Stale legacy blank record for the same package
                InstalledAppEntity(
                    packageName = "com.precedence.app",
                    activityName = "",
                    label = "Legacy App",
                    customCategory = "LegacyCategory",
                    lastUsedTimestamp = 100L,
                    isInstalled = true
                )
            )
        )
        db.workspaceDao().upsertWorkspaceItems(
            listOf(
                WorkspaceItemEntity(
                    itemId = "home_com.precedence.app_com.precedence.app.MainActivity",
                    itemType = WorkspaceItemTypes.APP,
                    containerType = WorkspaceContainers.HOME,
                    pageId = "page_0",
                    packageName = "com.precedence.app",
                    activityName = "com.precedence.app.MainActivity",
                    rank = 5,
                    isFixed = true,
                    isShuffled = false
                ),
                WorkspaceItemEntity(
                    itemId = "home_com.precedence.app_",
                    itemType = WorkspaceItemTypes.APP,
                    containerType = WorkspaceContainers.HOME,
                    pageId = "page_0",
                    packageName = "com.precedence.app",
                    activityName = "",
                    rank = 1,
                    isFixed = false,
                    isShuffled = true
                )
            )
        )
        db.workspaceDao().upsertDrawerLayouts(
            listOf(
                DrawerLayoutEntity("com.precedence.app", "com.precedence.app.MainActivity", drawerRank = 2, isFixed = true),
                DrawerLayoutEntity("com.precedence.app", "", drawerRank = 8, isDrawerShuffled = true)
            )
        )
        db.workspaceDao().upsertFocusApps(
            listOf(
                FocusAppEntity("com.precedence.app", "com.precedence.app.MainActivity", isVisibleInFocus = false, isEssentialInFocus = false),
                FocusAppEntity("com.precedence.app", "", isVisibleInFocus = true, isEssentialInFocus = true)
            )
        )

        registerLauncherActivity("com.precedence.app", "com.precedence.app.MainActivity", "Exact App")

        repository.discoverAndSyncApps()

        val exactApp = db.installedAppDao().getAllAppsList()
            .single { it.packageName == "com.precedence.app" && it.activityName == "com.precedence.app.MainActivity" }
        assertEquals("ExactCategory", exactApp.customCategory)
        assertEquals(7000L, exactApp.lastUsedTimestamp)

        val exactItem = db.workspaceDao().getWorkspaceItemsList()
            .single { it.packageName == "com.precedence.app" && it.activityName == "com.precedence.app.MainActivity" }
        assertEquals(5, exactItem.rank)
        assertTrue(exactItem.isFixed)
        assertFalse(exactItem.isShuffled)

        val exactDrawer = db.workspaceDao().getDrawerLayoutsList()
            .single { it.packageName == "com.precedence.app" && it.activityName == "com.precedence.app.MainActivity" }
        assertEquals(2, exactDrawer.drawerRank)
        assertTrue(exactDrawer.isFixed)

        val exactFocus = db.workspaceDao().getFocusAppsList()
            .single { it.packageName == "com.precedence.app" && it.activityName == "com.precedence.app.MainActivity" }
        assertFalse(exactFocus.isVisibleInFocus)
        assertFalse(exactFocus.isEssentialInFocus)

        // Legacy record was not merged over the exact record and remains archived rather than active
        val legacyRow = db.installedAppDao().getAllAppsList()
            .firstOrNull { it.packageName == "com.precedence.app" && it.activityName == "" }
        assertNotNull(legacyRow)
        assertFalse(legacyRow!!.isInstalled)
        assertNull(
            db.workspaceDao().getWorkspaceItemsList()
                .find { it.packageName == "com.precedence.app" && it.activityName == "" }
        )
    }

    @Test
    fun `failed reconciliation transaction or corrupt archive leaves legacy record intact`() = runBlocking {
        db.workspaceDao().upsertPage(WorkspacePageEntity("page_0", pageIndex = 0, isDefault = true))
        db.installedAppDao().upsertApp(
            InstalledAppEntity(
                packageName = "com.fail.tx",
                activityName = "",
                label = "Fail Tx",
                customCategory = "CriticalData",
                lastUsedTimestamp = 3333L,
                isInstalled = true
            )
        )
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity(
                itemId = "home_com.fail.tx_",
                itemType = WorkspaceItemTypes.APP,
                containerType = WorkspaceContainers.HOME,
                pageId = "page_0",
                packageName = "com.fail.tx",
                activityName = "",
                rank = 2,
                isFixed = true
            )
        )
        db.workspaceDao().upsertDrawerLayout(DrawerLayoutEntity("com.fail.tx", "", drawerRank = 5, isDrawerShuffled = true))
        db.workspaceDao().upsertFocusApp(FocusAppEntity("com.fail.tx", "", isVisibleInFocus = true, isEssentialInFocus = true))

        registerLauncherActivity("com.fail.tx", "com.fail.tx.MainActivity", "Fail Tx")

        // Simulate failure during legacy deletion step inside the Room transaction
        val delegateDao = db.installedAppDao()
        val failingDao = object : InstalledAppDao by delegateDao {
            override suspend fun deleteByComponent(packageName: String, activityName: String) {
                throw IllegalStateException("Simulated SQL failure during reconciliation")
            }
        }
        val failingRepo = AppRepository(
            context = context,
            installedAppDao = failingDao,
            workspaceDao = db.workspaceDao(),
            shuffleHistoryDao = db.shuffleHistoryDao(),
            preferences = preferences,
            database = db
        )

        try {
            failingRepo.discoverAndSyncApps()
            fail("Expected simulated reconciliation failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Simulated SQL failure"))
        }

        // Verify Room transaction rolled back completely: legacy record and all its data are intact,
        // and no partial target component exists.
        val appsAfterTxFail = db.installedAppDao().getAllAppsList()
        assertEquals(1, appsAfterTxFail.size)
        assertEquals("", appsAfterTxFail.single().activityName)
        assertEquals("CriticalData", appsAfterTxFail.single().customCategory)
        assertTrue(appsAfterTxFail.single().isInstalled)

        val itemsAfterTxFail = db.workspaceDao().getWorkspaceItemsList()
        assertEquals(1, itemsAfterTxFail.size)
        assertEquals("", itemsAfterTxFail.single().activityName)
        assertEquals(2, itemsAfterTxFail.single().rank)

        val drawersAfterTxFail = db.workspaceDao().getDrawerLayoutsList()
        assertEquals(1, drawersAfterTxFail.size)
        assertEquals("", drawersAfterTxFail.single().activityName)
        assertEquals(5, drawersAfterTxFail.single().drawerRank)

        val focusAfterTxFail = db.workspaceDao().getFocusAppsList()
        assertEquals(1, focusAfterTxFail.size)
        assertEquals("", focusAfterTxFail.single().activityName)
        assertTrue(focusAfterTxFail.single().isEssentialInFocus)

        // Also verify corrupt archivedPlacementJson aborts reconciliation and preserves legacy record
        db.installedAppDao().upsertApp(
            InstalledAppEntity(
                packageName = "com.fail.archive",
                activityName = "",
                label = "Corrupt Archive",
                customCategory = "ArchiveCat",
                lastUsedTimestamp = 999L,
                isInstalled = false,
                archivedPlacementJson = "{not_valid_json"
            )
        )
        registerLauncherActivity("com.fail.archive", "com.fail.archive.Main", "Corrupt Archive")

        repository.discoverAndSyncApps()

        val corruptLegacy = db.installedAppDao().getAllAppsList()
            .firstOrNull { it.packageName == "com.fail.archive" && it.activityName == "" }
        assertNotNull("Legacy record with corrupt archive must remain intact", corruptLegacy)
        assertEquals("{not_valid_json", corruptLegacy!!.archivedPlacementJson)
        assertNull(
            db.installedAppDao().getAllAppsList()
                .find { it.packageName == "com.fail.archive" && it.activityName == "com.fail.archive.Main" }
        )
    }

    @Test
    fun `reconciled app preserves uninstall and reinstall restoration lifecycle`() = runBlocking {
        db.workspaceDao().upsertPage(WorkspacePageEntity("page_0", pageIndex = 0, isDefault = true))
        db.installedAppDao().upsertApp(
            InstalledAppEntity(
                packageName = "com.lifecycle.app",
                activityName = "",
                label = "Lifecycle",
                customCategory = "Social",
                lastUsedTimestamp = 1234L,
                isInstalled = true
            )
        )
        db.workspaceDao().upsertWorkspaceItem(
            WorkspaceItemEntity(
                itemId = "home_com.lifecycle.app_",
                itemType = WorkspaceItemTypes.APP,
                containerType = WorkspaceContainers.HOME,
                pageId = "page_0",
                packageName = "com.lifecycle.app",
                activityName = "",
                rank = 4,
                isShuffled = true
            )
        )

        // Step 1: Discover and reconcile legacy blank record -> real activity
        registerLauncherActivity("com.lifecycle.app", "com.lifecycle.app.Main", "Lifecycle")
        repository.discoverAndSyncApps()

        val reconciled = repository.allAppsFlow.first().single { it.packageName == "com.lifecycle.app" }
        assertEquals("com.lifecycle.app.Main", reconciled.activityName)
        assertEquals(4, reconciled.slotIndex)
        assertTrue(reconciled.isShuffled)

        // Step 2: Uninstall package -> archived under real component
        uninstallPackage("com.lifecycle.app")
        repository.discoverAndSyncApps()
        assertTrue(repository.allAppsFlow.first().isEmpty())
        val archived = db.installedAppDao().getAllAppsList().single { it.packageName == "com.lifecycle.app" }
        assertEquals("com.lifecycle.app.Main", archived.activityName)
        assertFalse(archived.isInstalled)
        assertNotNull(archived.archivedPlacementJson)

        // Step 3: Reinstall same component -> placement & flags restored
        registerLauncherActivity("com.lifecycle.app", "com.lifecycle.app.Main", "Lifecycle")
        repository.discoverAndSyncApps()
        val restored = repository.allAppsFlow.first().single { it.packageName == "com.lifecycle.app" }
        assertEquals(4, restored.slotIndex)
        assertTrue(restored.isShuffled)
        assertEquals("Social", restored.customCategory)
    }
}

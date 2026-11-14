package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.backup.BackupRestoreHelper
import com.example.data.database.DrawerLayoutEntity
import com.example.data.database.FocusAppEntity
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
class FolderManagementRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: ShellfriDatabase
    private lateinit var preferences: ShellfriPreferences
    private lateinit var repository: AppRepository
    private lateinit var backupRestoreHelper: BackupRestoreHelper
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
        backupRestoreHelper = BackupRestoreHelper(
            installedAppDao = db.installedAppDao(),
            workspaceDao = db.workspaceDao(),
            preferences = preferences,
            database = db
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun registerLauncherActivity(pkg: String, activityName: String, label: String) {
        val shadowPm = shadowOf(context.packageManager)
        val component = ComponentName(pkg, activityName)
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

    private suspend fun seedWorkspace(
        pageCount: Int = 2,
        apps: List<Triple<String, String, Pair<Int, Int>>> = listOf(
            Triple("com.alpha", "com.alpha.Main", 0 to 0),
            Triple("com.beta", "com.beta.Main", 0 to 1),
            Triple("com.gamma", "com.gamma.Main", 0 to 2),
            Triple("com.suite", "com.suite.Primary", 0 to 3),
            Triple("com.suite", "com.suite.Secondary", 1 to 0)
        )
    ) {
        val pages = (0 until pageCount).map { idx ->
            WorkspacePageEntity(
                pageId = "page_$idx",
                pageIndex = idx,
                isDefault = idx == 0,
                createdAt = 1000L + idx
            )
        }
        db.workspaceDao().upsertPages(pages)
        preferences.setPageCount(pageCount)

        val installed = apps.mapIndexed { idx, (pkg, act, _) ->
            registerLauncherActivity(pkg, act, "App $idx")
            InstalledAppEntity(
                packageName = pkg,
                activityName = act,
                label = "App $idx",
                isInstalled = true
            )
        }
        db.installedAppDao().upsertApps(installed)

        val homeItems = apps.map { (pkg, act, coord) ->
            WorkspaceItemEntity(
                itemId = AppRepository.homeItemId(pkg, act),
                itemType = WorkspaceItemTypes.APP,
                containerType = WorkspaceContainers.HOME,
                pageId = "page_${coord.first}",
                packageName = pkg,
                activityName = act,
                rank = coord.second
            )
        }
        db.workspaceDao().upsertWorkspaceItems(homeItems)
        db.workspaceDao().upsertDrawerLayouts(apps.map { (pkg, act, _) -> DrawerLayoutEntity(pkg, act) })
        db.workspaceDao().upsertFocusApps(apps.map { (pkg, act, _) -> FocusAppEntity(pkg, act) })
    }

    private suspend fun assertNormalizedWorkspaceValid() {
        val result = validator.validateNormalizedWorkspace(
            installedApps = db.installedAppDao().getAllAppsList(),
            pages = db.workspaceDao().getPagesList(),
            workspaceItems = db.workspaceDao().getWorkspaceItemsList(),
            folders = db.workspaceDao().getFoldersList(),
            folderMembers = db.workspaceDao().getFolderMembersList(),
            drawerLayouts = db.workspaceDao().getDrawerLayoutsList(),
            focusApps = db.workspaceDao().getFocusAppsList(),
            dockCount = preferences.dockCountFlow.first()
        )
        assertTrue("Normalized workspace invalid: ${result.errors}", result.isValid)
    }

    @Test
    fun `createFolderFromHomeApps preserves target slot, ranks members 0 and 1, preserves Dock and component identity`() = runBlocking {
        seedWorkspace()
        // Pin Primary component of com.suite to Dock, leave Secondary unpinned
        repository.toggleAppPinnedToDock("com.suite", "com.suite.Primary", true)

        // Drag com.suite/Primary (0,3) onto com.beta (0,1) -> folder lands at (0,1), Beta rank 0, Suite/Primary rank 1
        val result = repository.createFolderFromHomeApps(
            draggedPackageName = "com.suite",
            draggedActivityName = "com.suite.Primary",
            targetPackageName = "com.beta",
            targetActivityName = "com.beta.Main"
        )
        assertTrue("Expected creation to succeed: ${result.failureReason}", result.success)
        val folderId = result.folderId!!
        assertNormalizedWorkspaceValid()

        val folders = repository.foldersFlow.first()
        val created = folders.single { it.id == folderId }
        assertEquals(0, created.pageIndex)
        assertEquals(1, created.slotIndex)
        assertEquals(AppRepository.DEFAULT_FOLDER_NAME, created.name)

        val byComp = repository.allAppsFlow.first().associateBy { it.componentKey }
        val beta = byComp["com.beta/com.beta.Main"]!!
        assertEquals(folderId, beta.folderId)
        assertEquals(0, beta.folderRank)
        assertFalse(beta.isOnHomeScreen)

        val primary = byComp["com.suite/com.suite.Primary"]!!
        assertEquals(folderId, primary.folderId)
        assertEquals(1, primary.folderRank)
        assertFalse(primary.isOnHomeScreen)
        assertTrue(primary.isPinnedToDock)
        assertEquals(0, primary.dockRank)

        // Sibling activity from same package stayed cleanly untouched on Home page 1
        val secondary = byComp["com.suite/com.suite.Secondary"]!!
        assertNull(secondary.folderId)
        assertTrue(secondary.isOnHomeScreen)
        assertEquals(1, secondary.pageIndex)
        assertEquals(0, secondary.slotIndex)
        assertFalse(secondary.isPinnedToDock)
    }

    @Test
    fun `createFolderFromHomeApps rejects fixed apps, self-folder, and rolls back on sqlite write failure`() = runBlocking {
        seedWorkspace()
        repository.toggleAppFixed("com.alpha", "com.alpha.Main", true)

        assertFalse(
            repository.createFolderFromHomeApps("com.alpha", "com.alpha.Main", "com.beta", "com.beta.Main").success
        )
        assertFalse(
            repository.createFolderFromHomeApps("com.beta", "com.beta.Main", "com.alpha", "com.alpha.Main").success
        )
        assertFalse(
            repository.createFolderFromHomeApps("com.beta", "com.beta.Main", "com.beta", "com.beta.Main").success
        )

        // Simulate write failure during folder creation
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_folder_create
            BEFORE INSERT ON folder_members
            BEGIN
                SELECT RAISE(ABORT, 'simulated folder write error');
            END;
            """.trimIndent()
        )
        val failed = repository.createFolderFromHomeApps("com.beta", "com.beta.Main", "com.gamma", "com.gamma.Main")
        assertFalse(failed.success)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS abort_folder_create")

        // No half-created folder or orphaned home items
        assertTrue(db.workspaceDao().getFoldersList().isEmpty())
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `renameFolderResult validates blank and overlength names, trims whitespace, allows duplicates, and retains valid name on failure`() = runBlocking {
        seedWorkspace()
        val f1 = repository.createFolderFromHomeApps("com.alpha", "com.alpha.Main", "com.beta", "com.beta.Main").folderId!!
        val f2 = repository.createFolderFromHomeApps("com.suite", "com.suite.Primary", "com.gamma", "com.gamma.Main").folderId!!

        // Reject blank / whitespace
        assertFalse(repository.renameFolderResult(f1, "   ").success)
        assertFalse(repository.renameFolderResult(f1, "").success)
        // Reject > MAX_FOLDER_NAME_LENGTH
        val tooLong = "A".repeat(AppRepository.MAX_FOLDER_NAME_LENGTH + 1)
        assertFalse(repository.renameFolderResult(f1, tooLong).success)

        // Valid rename trims whitespace and preserves folderId
        val validRename = repository.renameFolderResult(f1, "  Work Apps  ")
        assertTrue(validRename.success)
        assertEquals("Work Apps", repository.foldersFlow.first().first { it.id == f1 }.name)

        // Duplicate folder names are permitted
        assertTrue(repository.renameFolderResult(f2, "Work Apps").success)
        assertNormalizedWorkspaceValid()

        // Failure on sqlite trigger retains previous valid name (Room @Insert REPLACE triggers BEFORE INSERT)
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_folder_rename
            BEFORE INSERT ON folders
            BEGIN
                SELECT RAISE(ABORT, 'simulated rename error');
            END;
            """.trimIndent()
        )
        val failedRename = repository.renameFolderResult(f1, "New Name")
        assertFalse(failedRename.success)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS abort_folder_rename")
        assertEquals("Work Apps", repository.foldersFlow.first().first { it.id == f1 }.name)
    }

    @Test
    fun `reorderFolderMembers persists deterministic ranks without altering Home or Dock and rejects invalid inputs`() = runBlocking {
        seedWorkspace()
        repository.toggleAppPinnedToDock("com.alpha", "com.alpha.Main", true)
        val folderId = repository.createFolderFromHomeApps("com.alpha", "com.alpha.Main", "com.beta", "com.beta.Main").folderId!!
        assertTrue(repository.addAppToFolderResult("com.gamma", "com.gamma.Main", folderId).success)

        // Initial order: beta(0), alpha(1), gamma(2). Reorder to: gamma(0), alpha(1), beta(2)
        val reorderResult = repository.reorderFolderMembers(
            folderId = folderId,
            orderedComponents = listOf(
                "com.gamma" to "com.gamma.Main",
                "com.alpha" to "com.alpha.Main",
                "com.beta" to "com.beta.Main"
            )
        )
        assertTrue("Reorder failed: ${reorderResult.failureReason}", reorderResult.success)
        assertNormalizedWorkspaceValid()

        val membersAfter = repository.allAppsFlow.first()
            .filter { it.folderId == folderId }
            .sortedBy { it.folderRank }
            .map { it.packageName to it.folderRank }
        assertEquals(
            listOf("com.gamma" to 0, "com.alpha" to 1, "com.beta" to 2),
            membersAfter
        )

        // Alpha still has its Dock placement
        val alpha = repository.allAppsFlow.first().first { it.packageName == "com.alpha" }
        assertTrue(alpha.isPinnedToDock)
        assertEquals(0, alpha.dockRank)

        // Reject duplicate or mismatched member list
        assertFalse(
            repository.reorderFolderMembers(
                folderId,
                listOf("com.gamma" to "com.gamma.Main", "com.gamma" to "com.gamma.Main", "com.beta" to "com.beta.Main")
            ).success
        )
        assertFalse(
            repository.reorderFolderMembers(
                folderId,
                listOf("com.gamma" to "com.gamma.Main", "com.beta" to "com.beta.Main")
            ).success
        )
    }

    @Test
    fun `removeAppFromFolderResult packs 3-to-2 members and auto-dissolves at 1 remaining member while preserving Dock`() = runBlocking {
        seedWorkspace()
        repository.toggleAppPinnedToDock("com.alpha", "com.alpha.Main", true)
        val folderId = repository.createFolderFromHomeApps("com.alpha", "com.alpha.Main", "com.beta", "com.beta.Main").folderId!!
        assertTrue(repository.addAppToFolderResult("com.gamma", "com.gamma.Main", folderId).success)

        // Remove beta -> 2 members remain (alpha, gamma), re-packed 0..1; beta restored to Home without uninstall
        val removeFirst = repository.removeAppFromFolderResult("com.beta", "com.beta.Main", targetPage = 1)
        assertTrue(removeFirst.success)
        assertNormalizedWorkspaceValid()

        val afterFirst = repository.allAppsFlow.first().associateBy { it.packageName }
        assertNull(afterFirst["com.beta"]!!.folderId)
        assertTrue(afterFirst["com.beta"]!!.isOnHomeScreen)
        assertTrue(afterFirst["com.beta"]!!.isInstalled)
        assertEquals(1, afterFirst["com.beta"]!!.pageIndex)
        assertEquals(folderId, afterFirst["com.alpha"]!!.folderId)
        assertEquals(0, afterFirst["com.alpha"]!!.folderRank)
        assertEquals(folderId, afterFirst["com.gamma"]!!.folderId)
        assertEquals(1, afterFirst["com.gamma"]!!.folderRank)
        assertTrue(repository.foldersFlow.first().any { it.id == folderId })

        // Remove gamma -> 1 member (alpha) remains -> folder auto-dissolves, alpha unpacked to folder's Home slot (0,1), Dock preserved
        val removeSecond = repository.removeAppFromFolderResult("com.gamma", "com.gamma.Main", targetPage = 0)
        assertTrue(removeSecond.success)
        assertNormalizedWorkspaceValid()
        assertTrue(repository.foldersFlow.first().isEmpty())

        val afterDissolve = repository.allAppsFlow.first().associateBy { it.packageName }
        val alpha = afterDissolve["com.alpha"]!!
        assertNull(alpha.folderId)
        assertEquals(-1, alpha.folderRank)
        assertTrue(alpha.isOnHomeScreen)
        assertEquals(0, alpha.pageIndex)
        assertEquals(1, alpha.slotIndex)
        assertTrue(alpha.isPinnedToDock)
        assertEquals(0, alpha.dockRank)

        // Calling deleteFolder after auto-dissolution is a safe no-op
        repository.deleteFolder(folderId)
        assertNormalizedWorkspaceValid()
    }

    @Test
    fun `moveFolderOnHomeScreen relocates folder and shifts occupied apps without altering members or Dock`() = runBlocking {
        seedWorkspace()
        val folderId = repository.createFolderFromHomeApps("com.alpha", "com.alpha.Main", "com.beta", "com.beta.Main").folderId!!
        // Folder is at (0, 1). com.gamma is at (0, 2). Move folder -> (0, 2)
        val moveResult = repository.moveFolderOnHomeScreen(
            folderId = folderId,
            targetPageIndex = 0,
            targetSlotIndex = 2,
            gridColumns = 4,
            preserveEmptySpaces = true
        )
        assertTrue("Folder move failed: ${moveResult.failureReason}", moveResult.success)
        assertNormalizedWorkspaceValid()

        val movedFolder = repository.foldersFlow.first().single { it.id == folderId }
        assertEquals(0, movedFolder.pageIndex)
        assertEquals(2, movedFolder.slotIndex)
        val byPkg = repository.allAppsFlow.first().associateBy { it.packageName }
        assertEquals(1, byPkg["com.gamma"]!!.slotIndex)
        assertEquals(folderId, byPkg["com.alpha"]!!.folderId)
        assertEquals(folderId, byPkg["com.beta"]!!.folderId)
    }

    @Test
    fun `backup restore and shuffle undo remain compatible with reordered C2 folders`() = runBlocking {
        seedWorkspace()
        val folderId = repository.createFolderFromHomeApps("com.alpha", "com.alpha.Main", "com.beta", "com.beta.Main").folderId!!
        assertTrue(repository.addAppToFolderResult("com.gamma", "com.gamma.Main", folderId).success)
        assertTrue(repository.renameFolderResult(folderId, "FocusKit").success)
        assertTrue(
            repository.reorderFolderMembers(
                folderId,
                listOf("com.gamma" to "com.gamma.Main", "com.alpha" to "com.alpha.Main", "com.beta" to "com.beta.Main")
            ).success
        )

        // Mark home apps (com.suite/Primary & Secondary) shuffled and run shuffle + undo
        repository.toggleAppShuffled("com.suite", "com.suite.Primary", true)
        repository.toggleAppShuffled("com.suite", "com.suite.Secondary", true)
        assertTrue(repository.performShuffle("MANUAL"))
        assertTrue(repository.undoLastShuffle())

        // Folder & reordered ranks survived shuffle + undo
        assertEquals(
            listOf("com.gamma", "com.alpha", "com.beta"),
            repository.allAppsFlow.first().filter { it.folderId == folderId }.sortedBy { it.folderRank }.map { it.packageName }
        )

        // Backup & restore roundtrip preserves folder name & member order
        val backupJson = backupRestoreHelper.generateBackupJson()
        db.workspaceDao().deleteAllFolderMembers()
        db.workspaceDao().deleteAllWorkspaceItems()
        db.workspaceDao().deleteAllFolders()
        assertTrue(backupRestoreHelper.restoreBackup(backupJson).isSuccess)
        assertNormalizedWorkspaceValid()

        val restoredFolder = repository.foldersFlow.first().single { it.id == folderId }
        assertEquals("FocusKit", restoredFolder.name)
        assertEquals(
            listOf("com.gamma", "com.alpha", "com.beta"),
            repository.allAppsFlow.first().filter { it.folderId == folderId }.sortedBy { it.folderRank }.map { it.packageName }
        )
    }
}

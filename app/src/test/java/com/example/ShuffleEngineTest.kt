package com.example

import com.example.domain.model.AppItem
import com.example.domain.model.ShuffleStrategy
import com.example.domain.shuffle.ShuffleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleEngineTest {

    private val engine = ShuffleEngine()

    @Test
    fun testEmptySelectionHandled() {
        val result = engine.shuffleLayout(emptyList())
        assertTrue("Empty input must return empty list", result.isEmpty())
    }

    @Test
    fun testNoShuffledAppsHandled() {
        val apps = listOf(
            AppItem(packageName = "app1", activityName = "", label = "App 1", slotIndex = 0, isShuffled = false),
            AppItem(packageName = "app2", activityName = "", label = "App 2", slotIndex = 1, isShuffled = false)
        )
        val result = engine.shuffleLayout(apps)
        assertEquals("List should be unchanged when no apps are marked for shuffle", apps, result)
    }

    @Test
    fun testSingleSelectedApplicationHandled() {
        val apps = listOf(
            AppItem(packageName = "app1", activityName = "", label = "App 1", slotIndex = 0, isShuffled = true),
            AppItem(packageName = "app2", activityName = "", label = "App 2", slotIndex = 1, isShuffled = false)
        )
        val result = engine.shuffleLayout(apps)
        assertEquals("Single selected app should remain safely in its position", 0, result.first { it.packageName == "app1" }.slotIndex)
    }

    @Test
    fun testNoDuplicatePositions() {
        val apps = (0 until 12).map { i ->
            AppItem(
                packageName = "app_$i",
                activityName = "",
                label = "App $i",
                slotIndex = i,
                isShuffled = i % 3 == 0,
                isFixed = i % 3 == 1
            )
        }

        val result = engine.shuffleLayout(apps)
        val slotIndices = result.map { it.slotIndex }
        assertEquals("Total count should match", apps.size, slotIndices.size)
        assertEquals("No duplicate positions allowed", slotIndices.size, slotIndices.distinct().size)
    }

    @Test
    fun testFixedApplicationsNeverMove() {
        val apps = listOf(
            AppItem(packageName = "fixed1", activityName = "", label = "Phone", slotIndex = 0, isFixed = true),
            AppItem(packageName = "shuffled1", activityName = "", label = "Instagram", slotIndex = 1, isShuffled = true),
            AppItem(packageName = "shuffled2", activityName = "", label = "YouTube", slotIndex = 2, isShuffled = true),
            AppItem(packageName = "fixed2", activityName = "", label = "Settings", slotIndex = 3, isFixed = true)
        )

        for (seed in 1..20) {
            val result = engine.shuffleLayout(apps, crossPageShuffle = false, random = Random(seed))
            val fixed1 = result.first { it.packageName == "fixed1" }
            val fixed2 = result.first { it.packageName == "fixed2" }
            assertEquals("Fixed app 1 position must never change", 0, fixed1.slotIndex)
            assertEquals("Fixed app 2 position must never change", 3, fixed2.slotIndex)
        }
    }

    @Test
    fun testPreviousLayoutIsNotRepeatedWhenAlternativesExist() {
        val apps = listOf(
            AppItem(packageName = "app1", activityName = "", label = "Instagram", slotIndex = 4, isShuffled = true),
            AppItem(packageName = "app2", activityName = "", label = "Reddit", slotIndex = 8, isShuffled = true),
            AppItem(packageName = "app3", activityName = "", label = "YouTube", slotIndex = 12, isShuffled = true)
        )

        val originalSlots = apps.map { it.packageName to it.slotIndex }.toMap()

        var nonIdenticalCount = 0
        for (seed in 1..30) {
            val result = engine.shuffleLayout(apps, crossPageShuffle = false, random = Random(seed))
            val newSlots = result.map { it.packageName to it.slotIndex }.toMap()
            if (newSlots != originalSlots) {
                nonIdenticalCount++
            }
        }

        assertEquals("Layout must never repeat previous permutation when alternatives exist", 30, nonIdenticalCount)
    }

    @Test
    fun testSelectedApplicationsCanMove() {
        val apps = listOf(
            AppItem(packageName = "insta", activityName = "", label = "Instagram", slotIndex = 1, isShuffled = true),
            AppItem(packageName = "yt", activityName = "", label = "YouTube", slotIndex = 2, isShuffled = true)
        )

        val result = engine.shuffleLayout(apps, crossPageShuffle = false, random = Random(42))
        val newInstaSlot = result.first { it.packageName == "insta" }.slotIndex
        val newYtSlot = result.first { it.packageName == "yt" }.slotIndex

        assertEquals("Instagram must have moved to YouTube's previous slot", 2, newInstaSlot)
        assertEquals("YouTube must have moved to Instagram's previous slot", 1, newYtSlot)
    }

    @Test
    fun testAppsInFoldersAreImmuneToShuffle() {
        val apps = listOf(
            AppItem(packageName = "folder_app", activityName = "", label = "App In Folder", slotIndex = 0, folderId = "folder_123", isShuffled = true),
            AppItem(packageName = "home_app1", activityName = "", label = "App 1", slotIndex = 1, isShuffled = true),
            AppItem(packageName = "home_app2", activityName = "", label = "App 2", slotIndex = 2, isShuffled = true)
        )

        for (seed in 1..10) {
            val result = engine.shuffleLayout(apps, crossPageShuffle = false, random = Random(seed))
            val folderApp = result.first { it.packageName == "folder_app" }
            assertEquals("Apps inside folders must maintain their slot and folder", 0, folderApp.slotIndex)
            assertEquals("folder_123", folderApp.folderId)
        }
    }

    @Test
    fun testPagePreservationWhenCrossPageDisabled() {
        val apps = listOf(
            AppItem(packageName = "p0_app1", activityName = "", label = "P0 App 1", pageIndex = 0, slotIndex = 0, isShuffled = true),
            AppItem(packageName = "p0_app2", activityName = "", label = "P0 App 2", pageIndex = 0, slotIndex = 1, isShuffled = true),
            AppItem(packageName = "p1_app1", activityName = "", label = "P1 App 1", pageIndex = 1, slotIndex = 0, isShuffled = true),
            AppItem(packageName = "p1_app2", activityName = "", label = "P1 App 2", pageIndex = 1, slotIndex = 1, isShuffled = true)
        )

        val result = engine.shuffleLayout(apps, crossPageShuffle = false, random = Random(99))
        val p0Apps = result.filter { it.packageName.startsWith("p0") }
        val p1Apps = result.filter { it.packageName.startsWith("p1") }

        assertTrue("Page 0 apps must stay on page 0", p0Apps.all { it.pageIndex == 0 })
        assertTrue("Page 1 apps must stay on page 1", p1Apps.all { it.pageIndex == 1 })
    }

    @Test
    fun testDrawerShuffleIndependent() {
        val apps = listOf(
            AppItem(packageName = "d1", activityName = "", label = "Drawer App 1", isDrawerShuffled = true),
            AppItem(packageName = "d2", activityName = "", label = "Drawer App 2", isDrawerShuffled = true),
            AppItem(packageName = "fixed_d", activityName = "", label = "Fixed App", isFixed = true)
        )

        val result = engine.shuffleDrawerLayout(apps, Random(123))
        assertEquals("Total app count preserved", 3, result.size)
        val fixedApp = result.first { it.packageName == "fixed_d" }
        assertEquals("Fixed app in drawer remains untouched", -1, fixedApp.drawerSlotIndex)
    }

    @Test
    fun testPositionRotationStrategy() {
        val apps = listOf(
            AppItem(packageName = "a1", activityName = "", label = "App 1", slotIndex = 10, isShuffled = true),
            AppItem(packageName = "a2", activityName = "", label = "App 2", slotIndex = 20, isShuffled = true),
            AppItem(packageName = "a3", activityName = "", label = "App 3", slotIndex = 30, isShuffled = true)
        )

        val result = engine.shuffleLayout(apps, strategy = ShuffleStrategy.POSITION_ROTATION)
        assertEquals(30, result.first { it.packageName == "a1" }.slotIndex)
        assertEquals(10, result.first { it.packageName == "a2" }.slotIndex)
        assertEquals(20, result.first { it.packageName == "a3" }.slotIndex)
    }

    @Test
    fun testSmartShuffleGuaranteesEveryEligibleAppMovesUnlikeFullRandom() {
        val apps = (0 until 6).map { i ->
            AppItem(
                packageName = "smart_$i",
                activityName = "Main",
                label = "Smart $i",
                pageIndex = 0,
                slotIndex = i,
                isShuffled = true
            )
        }

        // Across many seeds, SMART_SHUFFLE must ensure zero apps stay in their exact previous slot
        for (seed in 1..25) {
            val smartResult = engine.executeShuffle(
                com.example.domain.shuffle.ShuffleRequest(
                    apps = apps,
                    strategy = ShuffleStrategy.SMART_SHUFFLE,
                    crossPageShuffle = false,
                    pageCount = 1
                ),
                random = Random(seed)
            )
            assertTrue(smartResult.isValid)
            for (original in apps) {
                val moved = smartResult.apps.first { it.packageName == original.packageName }
                assertNotEquals(
                    "SMART_SHUFFLE must not leave ${original.packageName} in same slot (seed=$seed)",
                    original.slotIndex,
                    moved.slotIndex
                )
            }
        }
    }

    @Test
    fun testSmartShuffleAvoidsRecentPositionsWhenAlternativesExist() {
        val apps = (0 until 4).map { i ->
            AppItem(
                packageName = "app_$i",
                activityName = "Main",
                label = "App $i",
                pageIndex = 0,
                slotIndex = i,
                isShuffled = true
            )
        }
        val recent = mapOf(
            "app_0" to listOf(0 to 1, 0 to 2),
            "app_1" to listOf(0 to 2, 0 to 3)
        )

        val result = engine.executeShuffle(
            com.example.domain.shuffle.ShuffleRequest(
                apps = apps,
                strategy = ShuffleStrategy.SMART_SHUFFLE,
                crossPageShuffle = false,
                pageCount = 1,
                recentPositions = recent
            ),
            random = Random(7)
        )
        assertTrue(result.isValid)
        val app0Slot = result.apps.first { it.packageName == "app_0" }.slotIndex
        assertEquals("app_0 should prefer non-recent slot 3", 3, app0Slot)
    }

    @Test
    fun testCrossPageShuffleMovesAppsAcrossPages() {
        val apps = listOf(
            AppItem(packageName = "p0_a", activityName = "Main", label = "A", pageIndex = 0, slotIndex = 0, isShuffled = true),
            AppItem(packageName = "p0_b", activityName = "Main", label = "B", pageIndex = 0, slotIndex = 1, isShuffled = true),
            AppItem(packageName = "p1_c", activityName = "Main", label = "C", pageIndex = 1, slotIndex = 0, isShuffled = true),
            AppItem(packageName = "p1_d", activityName = "Main", label = "D", pageIndex = 1, slotIndex = 1, isShuffled = true)
        )

        val anyMovedCrossPage = (1..20).any { seed ->
            val res = engine.executeShuffle(
                com.example.domain.shuffle.ShuffleRequest(
                    apps = apps,
                    strategy = ShuffleStrategy.FULL_RANDOM,
                    crossPageShuffle = true,
                    pageCount = 2
                ),
                random = Random(seed)
            )
            assertTrue(res.isValid)
            res.apps.any { app ->
                val orig = apps.first { it.packageName == app.packageName }
                app.pageIndex != orig.pageIndex
            }
        }
        assertTrue("Cross-page shuffle should move apps between pages", anyMovedCrossPage)
    }

    @Test
    fun testCompactLayoutPreservesFixedAppsAndAvoidsFolderCollision() {
        val folders = listOf(
            com.example.domain.model.FolderItem(id = "f1", name = "Social", pageIndex = 0, slotIndex = 0)
        )
        val apps = listOf(
            AppItem(packageName = "fixed", activityName = "Main", label = "Fixed", pageIndex = 0, slotIndex = 2, isFixed = true),
            AppItem(packageName = "m1", activityName = "Main", label = "M1", pageIndex = 0, slotIndex = 5, isShuffled = true),
            AppItem(packageName = "m2", activityName = "Main", label = "M2", pageIndex = 0, slotIndex = 9, isShuffled = true)
        )

        val result = engine.executeShuffle(
            com.example.domain.shuffle.ShuffleRequest(
                apps = apps,
                folders = folders,
                strategy = ShuffleStrategy.FULL_RANDOM,
                occupancy = com.example.domain.model.OccupancyOption.COMPACT_LAYOUT,
                crossPageShuffle = false,
                pageCount = 1
            ),
            random = Random(42)
        )

        assertTrue(result.isValid)
        assertEquals(2, result.apps.first { it.packageName == "fixed" }.slotIndex)
        val movedSlots = result.apps.filter { it.packageName != "fixed" }.map { it.slotIndex }.toSet()
        // Folder occupies slot 0, fixed occupies slot 2 -> movable apps must compact into slots 1 and 3
        assertEquals(setOf(1, 3), movedSlots)
    }

    @Test
    fun testInvalidPreExistingCollisionPreservesOriginalLayout() {
        // Two apps already colliding at (0, 0) -> validation rejects and returns original list untouched
        val corrupted = listOf(
            AppItem(packageName = "a", activityName = "Main", label = "A", pageIndex = 0, slotIndex = 0, isShuffled = true),
            AppItem(packageName = "b", activityName = "Main", label = "B", pageIndex = 0, slotIndex = 0, isShuffled = true)
        )

        val result = engine.executeShuffle(
            com.example.domain.shuffle.ShuffleRequest(
                apps = corrupted,
                strategy = ShuffleStrategy.FULL_RANDOM,
                crossPageShuffle = false,
                pageCount = 1
            )
        )

        assertEquals(false, result.isValid)
        assertTrue(result.validationErrors.isNotEmpty())
        assertEquals(corrupted, result.apps)
    }
}

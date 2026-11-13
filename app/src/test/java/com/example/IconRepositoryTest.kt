package com.example

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.domain.repository.IconRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IconRepositoryTest {

    private fun testBitmap(size: Int = 16): ImageBitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).asImageBitmap()

    @Test
    fun `component based cache keys distinguish activities within same package`() = runBlocking {
        val bitmapA = testBitmap()
        val bitmapB = testBitmap()
        val repo = IconRepository(
            maxCacheBytes = 1024 * 1024,
            customLoader = { pkg, activity ->
                when ("$pkg/$activity") {
                    "com.example.multi/ActivityA" -> IconRepository.CachedIcon(bitmapA, 1024)
                    "com.example.multi/ActivityB" -> IconRepository.CachedIcon(bitmapB, 1024)
                    else -> null
                }
            }
        )

        val loadedA = repo.loadIcon("com.example.multi", "ActivityA")
        val loadedB = repo.loadIcon("com.example.multi", "ActivityB")

        assertSame(bitmapA, loadedA)
        assertSame(bitmapB, loadedB)
        assertEquals("com.example.multi/ActivityA", repo.cacheKey("com.example.multi", "ActivityA"))
    }

    @Test
    fun `bounded byte LRU cache evicts oldest icon when capacity exceeded`() = runBlocking {
        val loaderCalls = AtomicInteger(0)
        val repo = IconRepository(
            maxCacheBytes = 2500, // Fits 2 icons of 1024 bytes each, evicts 1st when 3rd added
            customLoader = { _, _ ->
                loaderCalls.incrementAndGet()
                IconRepository.CachedIcon(testBitmap(), 1024)
            }
        )

        repo.loadIcon("pkg.one", "Main")
        repo.loadIcon("pkg.two", "Main")
        repo.loadIcon("pkg.three", "Main")

        assertNull("Oldest entry pkg.one should have been evicted by byte limit", repo.getCachedIcon("pkg.one", "Main"))
        assertNotNull(repo.getCachedIcon("pkg.two", "Main"))
        assertNotNull(repo.getCachedIcon("pkg.three", "Main"))
        assertEquals(2048, repo.currentCacheBytes())
        assertEquals(3, loaderCalls.get())
    }

    @Test
    fun `concurrent requests for same component coalesce into single load`() = runBlocking {
        val loaderCalls = AtomicInteger(0)
        val repo = IconRepository(
            maxCacheBytes = 1024 * 1024,
            customLoader = { _, _ ->
                loaderCalls.incrementAndGet()
                delay(40)
                IconRepository.CachedIcon(testBitmap(24), 2304)
            }
        )

        val results = (1..10).map {
            async { repo.loadIcon("com.concurrent.app", "LauncherActivity") }
        }.awaitAll()

        assertEquals("Duplicate concurrent loads must be coalesced to 1", 1, loaderCalls.get())
        assertEquals(10, results.size)
        val first = results.first()
        assertNotNull(first)
        results.forEach { assertSame(first, it) }
    }

    @Test
    fun `invalidatePackage removes all component icons for updated package only`() = runBlocking {
        val repo = IconRepository(
            maxCacheBytes = 1024 * 1024,
            customLoader = { _, _ ->
                IconRepository.CachedIcon(testBitmap(), 1024)
            }
        )

        repo.loadIcon("com.target.app", "Main")
        repo.loadIcon("com.target.app", "Share")
        repo.loadIcon("com.other.app", "Main")

        repo.invalidatePackage("com.target.app")

        assertNull(repo.getCachedIcon("com.target.app", "Main"))
        assertNull(repo.getCachedIcon("com.target.app", "Share"))
        assertNotNull(repo.getCachedIcon("com.other.app", "Main"))
    }
}

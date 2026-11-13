package com.example.domain.repository

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.domain.model.AppItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

@Stable
class IconRepository(
    context: Context? = null,
    maxCacheBytes: Int = defaultCacheSizeBytes(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxIconPx: Int = 192,
    private val customLoader: (suspend (packageName: String, activityName: String) -> CachedIcon?)? = null
) {
    data class CachedIcon(
        val imageBitmap: ImageBitmap,
        val byteCount: Int
    )

    private val packageManager: PackageManager? = context?.packageManager
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    private val cacheLock = Any()

    private val lruCache = object : LruCache<String, CachedIcon>(maxCacheBytes.coerceAtLeast(1024)) {
        override fun sizeOf(key: String, value: CachedIcon): Int {
            return value.byteCount.coerceAtLeast(1)
        }
    }

    fun cacheKey(packageName: String, activityName: String = ""): String {
        return if (activityName.isNotBlank()) "$packageName/$activityName" else packageName
    }

    fun getCachedIcon(packageName: String, activityName: String = ""): ImageBitmap? {
        val key = cacheKey(packageName, activityName)
        return synchronized(cacheLock) {
            lruCache.get(key)?.imageBitmap
        }
    }

    suspend fun loadIcon(packageName: String, activityName: String = ""): ImageBitmap? {
        if (packageName.isBlank()) return null
        val key = cacheKey(packageName, activityName)

        synchronized(cacheLock) {
            lruCache.get(key)?.imageBitmap?.let { return it }
        }

        val deferred = obtainOrCreateInFlight(key, packageName, activityName)
        return deferred.await()
    }

    fun preloadIcons(apps: List<AppItem>) {
        if (apps.isEmpty()) return
        scope.launch {
            for (app in apps) {
                loadIcon(app.packageName, app.activityName)
            }
        }
    }

    fun invalidatePackage(packageName: String) {
        if (packageName.isBlank()) return
        val prefix = "$packageName/"
        synchronized(cacheLock) {
            val keysToRemove = lruCache.snapshot().keys.filter { key ->
                key == packageName || key.startsWith(prefix)
            }
            keysToRemove.forEach { lruCache.remove(it) }
        }
        inFlight.keys.filter { it == packageName || it.startsWith(prefix) }.forEach { key ->
            inFlight.remove(key)?.cancel()
        }
    }

    fun clearCache() {
        synchronized(cacheLock) {
            lruCache.evictAll()
        }
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
    }

    fun currentCacheBytes(): Int = synchronized(cacheLock) { lruCache.size() }

    private fun obtainOrCreateInFlight(
        key: String,
        packageName: String,
        activityName: String
    ): Deferred<ImageBitmap?> {
        inFlight[key]?.let { existing ->
            if (existing.isActive || existing.isCompleted) return existing
        }

        val placeholder = CompletableDeferred<ImageBitmap?>()
        val raced = inFlight.putIfAbsent(key, placeholder)
        if (raced != null && (raced.isActive || raced.isCompleted)) {
            return raced
        }

        val actualJob = scope.async {
            try {
                val loaded = decodeAndCache(key, packageName, activityName)
                placeholder.complete(loaded)
                loaded
            } catch (t: Throwable) {
                placeholder.complete(null)
                null
            } finally {
                inFlight.remove(key, placeholder)
            }
        }
        if (raced != null) {
            // Replaced stale/cancelled entry
            inFlight[key] = placeholder
        }
        // Keep reference active so cancellation propagates cleanly if invalidated
        actualJob.invokeOnCompletion { cause ->
            if (cause != null && !placeholder.isCompleted) {
                placeholder.cancel()
            }
        }
        return placeholder
    }

    private suspend fun decodeAndCache(
        key: String,
        packageName: String,
        activityName: String
    ): ImageBitmap? = withContext(ioDispatcher) {
        synchronized(cacheLock) {
            lruCache.get(key)?.imageBitmap?.let { return@withContext it }
        }

        val cachedIcon = if (customLoader != null) {
            customLoader.invoke(packageName, activityName)
        } else {
            loadFromPackageManager(packageName, activityName)
        }

        if (cachedIcon != null) {
            synchronized(cacheLock) {
                lruCache.put(key, cachedIcon)
            }
        }
        cachedIcon?.imageBitmap
    }

    private fun loadFromPackageManager(packageName: String, activityName: String): CachedIcon? {
        val pm = packageManager ?: return null
        return try {
            val drawable: Drawable = if (activityName.isNotBlank()) {
                runCatching {
                    pm.getActivityIcon(ComponentName(packageName, activityName))
                }.getOrElse {
                    pm.getApplicationIcon(packageName)
                }
            } else {
                pm.getApplicationIcon(packageName)
            }
            val bitmap = drawableToBoundedBitmap(drawable, maxIconPx)
            CachedIcon(
                imageBitmap = bitmap.asImageBitmap(),
                byteCount = bitmap.allocationByteCount.coerceAtLeast(bitmap.byteCount)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun drawableToBoundedBitmap(drawable: Drawable, maxPx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val src = drawable.bitmap
            if (src.width <= maxPx && src.height <= maxPx) {
                return src
            }
            val scale = minOf(maxPx.toFloat() / src.width, maxPx.toFloat() / src.height)
            val targetW = (src.width * scale).toInt().coerceAtLeast(1)
            val targetH = (src.height * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(src, targetW, targetH, true)
        }

        val rawW = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val rawH = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        val scale = if (rawW > maxPx || rawH > maxPx) {
            minOf(maxPx.toFloat() / rawW, maxPx.toFloat() / rawH)
        } else {
            1f
        }
        val width = (rawW * scale).toInt().coerceAtLeast(1)
        val height = (rawH * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    companion object {
        fun defaultCacheSizeBytes(): Int {
            val maxMemoryBytes = Runtime.getRuntime().maxMemory().coerceAtLeast(16L * 1024L * 1024L)
            return (maxMemoryBytes / 8L)
                .coerceIn(8L * 1024L * 1024L, 32L * 1024L * 1024L)
                .toInt()
        }
    }
}

@Composable
fun rememberAppIcon(
    iconRepository: IconRepository,
    packageName: String,
    activityName: String = ""
): ImageBitmap? {
    val icon by produceState<ImageBitmap?>(
        initialValue = iconRepository.getCachedIcon(packageName, activityName),
        key1 = iconRepository,
        key2 = packageName,
        key3 = activityName
    ) {
        if (value == null) {
            value = iconRepository.loadIcon(packageName, activityName)
        }
    }
    return icon
}

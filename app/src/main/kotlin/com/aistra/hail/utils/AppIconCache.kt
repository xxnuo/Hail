package com.aistra.hail.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.widget.ImageView
import androidx.collection.LruCache
import com.aistra.hail.HailApp
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import kotlinx.coroutines.*
import me.zhanghai.android.appiconloader.AppIconLoader
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * @author Rikka
 * Source
 * https://raw.githubusercontent.com/RikkaApps/Shizuku/master/manager/src/main/java/moe/shizuku/manager/utils/AppIconCache.kt
 */
object AppIconCache : CoroutineScope {
    private data class IconRequest(val packageName: String, val grayscale: Boolean)

    private class AppIconLruCache constructor(maxSize: Int) :
        LruCache<Triple<String, Int, Int>, Bitmap>(maxSize) {

        override fun sizeOf(key: Triple<String, Int, Int>, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    override val coroutineContext: CoroutineContext get() = Dispatchers.Main

    private val lruCache: LruCache<Triple<String, Int, Int>, Bitmap>

    private val dispatcher: CoroutineDispatcher

    private var appIconLoaders = mutableMapOf<Int, AppIconLoader>()

    private data class IconConfig(val iconPack: String, val shrinkNonAdaptiveIcons: Boolean)

    private var iconConfig: IconConfig

    private val cf by lazy { ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }

    init {
        // Initialize app icon lru cache
        val maxMemory = Runtime.getRuntime().maxMemory() / 1024
        val availableCacheSize = (maxMemory / 4).toInt()
        lruCache = AppIconLruCache(availableCacheSize)

        // Initialize load icon scheduler
        val availableProcessorsCount = try {
            Runtime.getRuntime().availableProcessors()
        } catch (ignored: Exception) {
            1
        }
        val threadCount = 1.coerceAtLeast(availableProcessorsCount / 2)
        val loadIconExecutor: Executor = Executors.newFixedThreadPool(threadCount)
        dispatcher = loadIconExecutor.asCoroutineDispatcher()
        iconConfig = currentIconConfig()
    }

    private fun get(packageName: String, userId: Int, size: Int): Bitmap? {
        return lruCache[Triple(packageName, userId, size)]
    }

    private fun put(packageName: String, userId: Int, size: Int, bitmap: Bitmap) {
        if (get(packageName, userId, size) == null) {
            lruCache.put(Triple(packageName, userId, size), bitmap)
        }
    }

    fun clear() {
        lruCache.evictAll()
        launch(dispatcher) {
            diskCacheRoot().deleteRecursively()
        }
    }

    @SuppressLint("NewApi")
    fun getOrLoadBitmap(context: Context, info: ApplicationInfo, userId: Int, size: Int): Bitmap {
        val currentConfig = ensureCurrentConfig()
        val cachedBitmap = get(info.packageName, userId, size)
        if (cachedBitmap != null) {
            return cachedBitmap
        }
        val diskCacheFile = diskCacheFile(context, info, userId, size, currentConfig)
        val diskBitmap = loadFromDisk(diskCacheFile)
        if (diskBitmap != null) {
            put(info.packageName, userId, size, diskBitmap)
            return diskBitmap
        }
        var loader = appIconLoaders[size]
        if (loader == null) {
            loader = AppIconLoader(size, currentConfig.shrinkNonAdaptiveIcons, context)
            appIconLoaders[size] = loader
        }
        val bitmap = IconPack.loadIcon(info.packageName) ?: loader.loadIcon(info, false)
        put(info.packageName, userId, size, bitmap)
        saveToDisk(diskCacheFile, bitmap)
        return bitmap
    }

    @JvmStatic
    fun loadIconBitmapAsync(
        context: Context,
        info: ApplicationInfo,
        userId: Int,
        view: ImageView,
        setColorFilter: Boolean = false
    ): Job {
        view.tag = IconRequest(info.packageName, setColorFilter)
        return launch {
            val size = view.measuredWidth.let {
                if (it > 0) it else context.resources.getDimensionPixelSize(R.dimen.app_icon_size)
            }
            ensureCurrentConfig()
            val cachedBitmap = get(info.packageName, userId, size)
            if (cachedBitmap != null) {
                if (!view.isCurrentRequest(info.packageName)) return@launch
                view.setImageBitmap(cachedBitmap)
                view.applyCurrentGrayscale()
                return@launch
            }

            val bitmap = try {
                withContext(dispatcher) {
                    getOrLoadBitmap(context, info, userId, size)
                }
            } catch (e: CancellationException) {
                // do nothing if canceled
                return@launch
            } catch (e: Throwable) {
                null
            }

            if (!view.isCurrentRequest(info.packageName)) return@launch
            if (bitmap != null) {
                view.setImageBitmap(bitmap)
            } else {
                view.setImageDrawable(if (HTarget.O) context.packageManager.defaultActivityIcon else null)
            }
            view.applyCurrentGrayscale()
        }
    }

    fun setGrayscale(view: ImageView, enabled: Boolean) {
        (view.tag as? IconRequest)?.let {
            view.tag = it.copy(grayscale = enabled)
        }
        view.applyCurrentGrayscale(enabled)
    }

    private fun ImageView.isCurrentRequest(packageName: String): Boolean =
        (tag as? IconRequest)?.packageName == packageName

    private fun ImageView.applyCurrentGrayscale(defaultValue: Boolean = false) {
        val enabled = (tag as? IconRequest)?.grayscale ?: defaultValue
        colorFilter = if (enabled) cf else null
    }

    private fun diskCacheRoot(context: Context = HailApp.app): File =
        File(context.cacheDir, "app_icons")

    private fun diskCacheFile(context: Context, info: ApplicationInfo, userId: Int, size: Int, config: IconConfig): File {
        val appUpdatedAt = HPackages.getUnhiddenPackageInfoOrNull(info.packageName)?.lastUpdateTime ?: 0
        val iconPackUpdatedAt = if (config.iconPack == HailData.ACTION_NONE) 0
        else HPackages.getUnhiddenPackageInfoOrNull(config.iconPack)?.lastUpdateTime ?: 0
        val key = listOf(
            2,
            info.packageName,
            userId,
            size,
            appUpdatedAt,
            config.iconPack,
            iconPackUpdatedAt,
            config.shrinkNonAdaptiveIcons
        ).joinToString("|")
        return File(diskCacheRoot(context), "${key.sha256()}.png")
    }

    private fun currentIconConfig() = IconConfig(HailData.iconPack, HailData.synthesizeAdaptiveIcons)

    private fun ensureCurrentConfig(): IconConfig {
        val currentConfig = currentIconConfig()
        if (iconConfig != currentConfig) {
            iconConfig = currentConfig
            lruCache.evictAll()
            appIconLoaders.clear()
        }
        return currentConfig
    }

    private fun loadFromDisk(file: File): Bitmap? = runCatching {
        if (!file.isFile) return null
        BitmapFactory.decodeFile(file.path)
    }.getOrNull()

    private fun saveToDisk(file: File, bitmap: Bitmap) {
        runCatching {
            val dir = file.parentFile ?: return
            if (!dir.exists() && !dir.mkdirs()) return
            val tempFile = File.createTempFile(file.name, ".tmp", dir)
            val success = tempFile.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            if (success) {
                if (file.exists()) file.delete()
                tempFile.renameTo(file)
            } else {
                tempFile.delete()
            }
        }
    }

    private fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        val chars = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = HEX_CHARS[value ushr 4]
            chars[index * 2 + 1] = HEX_CHARS[value and 0x0f]
        }
        return String(chars)
    }

    private const val HEX_CHARS = "0123456789abcdef"
}

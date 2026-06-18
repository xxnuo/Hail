package com.aistra.hail.app

import android.content.pm.ApplicationInfo
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.utils.HPackages

class AppInfo(
    val packageName: String,
    var pinned: Boolean = false,
    var whitelisted: Boolean = false,
    val tagIdList: MutableList<Int> = mutableListOf(0)
) {
    enum class State { NOT_FOUND, UNFROZEN, FROZEN }

    @Volatile
    private var cachedName: String? = null

    val applicationInfo: ApplicationInfo? get() = HPackages.getInstalledApplicationInfoOrNull(packageName)
    val name: String get() = cachedName ?: (applicationInfo?.loadLabel(app.packageManager)?.toString() ?: packageName).also {
        cachedName = it
    }
    val state
        get() = AppStateCache.stateOrDefault(packageName)

    override fun equals(other: Any?): Boolean = other is AppInfo && other.packageName == packageName
    override fun hashCode(): Int = packageName.hashCode()
}

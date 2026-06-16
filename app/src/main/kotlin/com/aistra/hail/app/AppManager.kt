package com.aistra.hail.app

import android.content.Intent
import com.aistra.hail.BuildConfig
import com.aistra.hail.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppManager {
    data class SetListFrozenResult(
        val successPackages: Set<String>,
        val hasFailure: Boolean,
        val toastArg: String?
    )

    val lockScreen: Boolean
        get() = when {
            HailData.workingMode.startsWith(HailData.OWNER) -> HPolicy.lockScreen
            HailData.workingMode.startsWith(HailData.DHIZUKU) -> HDhizuku.lockScreen
            HailData.workingMode.startsWith(HailData.SU) -> HShell.lockScreen
            HailData.workingMode.startsWith(HailData.SHIZUKU) -> HShizuku.lockScreen
            else -> false
        }

    fun isAppFrozen(packageName: String): Boolean = when {
        HailData.workingMode.endsWith(HailData.STOP) -> HPackages.isAppStopped(packageName)
        HailData.workingMode.endsWith(HailData.DISABLE) -> HPackages.isAppDisabled(packageName)
        HailData.workingMode.endsWith(HailData.HIDE) -> HPackages.isAppHidden(packageName)
        HailData.workingMode.endsWith(HailData.SUSPEND) -> HPackages.isAppSuspended(packageName)
        else -> HPackages.isAppDisabled(packageName)
                || HPackages.isAppHidden(packageName)
                || HPackages.isAppSuspended(packageName)
    }

    fun setListFrozen(frozen: Boolean, vararg appInfo: AppInfo): String? =
        setListFrozenResult(frozen, appInfo.asList()).toastArg

    fun setListFrozen(
        frozen: Boolean,
        appInfo: List<AppInfo>,
        skipWhitelisted: Boolean = true
    ): String? = setListFrozenResult(frozen, appInfo, skipWhitelisted).toastArg

    fun setListFrozenResult(frozen: Boolean, vararg appInfo: AppInfo): SetListFrozenResult =
        setListFrozenResult(frozen, appInfo.asList())

    fun setListFrozenResult(
        frozen: Boolean,
        appInfo: List<AppInfo>,
        skipWhitelisted: Boolean = true
    ): SetListFrozenResult {
        val targets = appInfo.filterListFrozenTargets(skipWhitelisted)
        return if (HailData.workingMode.startsWith(HailData.SU)) {
            val (success, hasFailure) = setSuListFrozen(frozen, targets)
            buildSetListFrozenResult(frozen, targets, success, hasFailure)
        } else {
            val success = mutableSetOf<String>()
            var hasFailure = false
            targets.forEach {
                when {
                    setAppFrozenRaw(it.packageName, frozen) -> success += it.packageName
                    it.applicationInfo != null -> hasFailure = true
                }
            }
            buildSetListFrozenResult(frozen, targets, success, hasFailure)
        }
    }

    private fun List<AppInfo>.filterListFrozenTargets(skipWhitelisted: Boolean): List<AppInfo> {
        val whitelistedPackages = if (skipWhitelisted) {
            HailData.checkedList.filter { it.whitelisted }.mapTo(mutableSetOf()) { it.packageName }
        } else emptySet()
        return filter {
            it.packageName != BuildConfig.APPLICATION_ID &&
                    (!skipWhitelisted || (!it.whitelisted && it.packageName !in whitelistedPackages))
        }
    }

    private fun buildSetListFrozenResult(
        frozen: Boolean,
        appInfo: List<AppInfo>,
        success: Set<String>,
        hasFailure: Boolean
    ): SetListFrozenResult {
        if (success.isNotEmpty()) {
            AppStateCache.markFrozen(success, frozen)
            AppStateCache.refreshAsync(success)
        }
        val toastArg = if (hasFailure && success.isEmpty()) null
        else if (success.size == 1) appInfo.firstOrNull { it.packageName in success }?.name?.toString()
        else success.size.toString()
        return SetListFrozenResult(success, hasFailure, toastArg)
    }

    private fun setSuListFrozen(frozen: Boolean, appInfo: List<AppInfo>): Pair<Set<String>, Boolean> {
        val packageNames = appInfo.map { it.packageName }
        val success = when (HailData.workingMode) {
            HailData.MODE_SU_STOP -> if (frozen) HShell.forceStopApps(packageNames) else packageNames.toSet()
            HailData.MODE_SU_DISABLE -> HShell.setAppsDisabled(packageNames, frozen)
            HailData.MODE_SU_HIDE -> HShell.setAppsHidden(packageNames, frozen)
            HailData.MODE_SU_SUSPEND -> HShell.setAppsSuspended(packageNames, frozen)
            else -> emptySet()
        }
        var hasFailure = false
        appInfo.forEach {
            if (it.packageName !in success && it.applicationInfo != null) hasFailure = true
        }
        return success to hasFailure
    }

    private fun setAppFrozenRaw(packageName: String, frozen: Boolean): Boolean =
        packageName != BuildConfig.APPLICATION_ID && when (HailData.workingMode) {
            HailData.MODE_OWNER_HIDE -> HPolicy.setAppHidden(packageName, frozen)
            HailData.MODE_OWNER_SUSPEND -> HPolicy.setAppSuspended(packageName, frozen)
            HailData.MODE_DHIZUKU_HIDE -> HDhizuku.setAppHidden(packageName, frozen)
            HailData.MODE_DHIZUKU_SUSPEND -> HDhizuku.setAppSuspended(packageName, frozen)
            HailData.MODE_SU_STOP -> !frozen || HShell.forceStopApp(packageName)
            HailData.MODE_SU_DISABLE -> HShell.setAppDisabled(packageName, frozen)
            HailData.MODE_SU_HIDE -> HShell.setAppHidden(packageName, frozen)
            HailData.MODE_SU_SUSPEND -> HShell.setAppSuspended(packageName, frozen)
            HailData.MODE_SHIZUKU_STOP -> !frozen || HShizuku.forceStopApp(packageName)
            HailData.MODE_SHIZUKU_DISABLE -> HShizuku.setAppDisabled(packageName, frozen)
            HailData.MODE_SHIZUKU_HIDE -> HShizuku.setAppHidden(packageName, frozen)
            HailData.MODE_SHIZUKU_SUSPEND -> HShizuku.setAppSuspended(packageName, frozen)
            HailData.MODE_ISLAND_HIDE -> HIsland.setAppHidden(packageName, frozen)
            HailData.MODE_ISLAND_SUSPEND -> HIsland.setAppSuspended(packageName, frozen)
            HailData.MODE_PRIVAPP_STOP -> !frozen || HPackages.forceStopApp(packageName)
            HailData.MODE_PRIVAPP_DISABLE -> HPackages.setAppDisabled(packageName, frozen)
            else -> false
        }

    fun setAppFrozen(packageName: String, frozen: Boolean): Boolean =
        setAppFrozenRaw(packageName, frozen).also {
            if (it) {
                AppStateCache.markFrozen(listOf(packageName), frozen)
                AppStateCache.refreshAsync(listOf(packageName))
            }
        }

    fun uninstallApp(packageName: String): Boolean {
        when {
            HailData.workingMode.startsWith(HailData.OWNER) ->
                if (HPolicy.uninstallApp(packageName)) return true

            HailData.workingMode.startsWith(HailData.DHIZUKU) ->
                if (HDhizuku.uninstallApp(packageName)) return true

            HailData.workingMode.startsWith(HailData.SU) ->
                if (HShell.uninstallApp(packageName)) return true

            HailData.workingMode.startsWith(HailData.SHIZUKU) ->
                if (HShizuku.uninstallApp(packageName)) return true
        }
        HUI.startActivity(Intent.ACTION_DELETE, HPackages.packageUri(packageName))
        return false
    }

    fun reinstallApp(packageName: String): Boolean = when {
        HailData.workingMode.startsWith(HailData.SU) -> HShell.reinstallApp(packageName)
        HailData.workingMode.startsWith(HailData.SHIZUKU) -> HShizuku.reinstallApp(packageName)
        else -> false
    }

    suspend fun execute(command: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        when {
            HailData.workingMode.startsWith(HailData.SU) -> HShell.execute(command, true)
            HailData.workingMode.startsWith(HailData.SHIZUKU) -> HShizuku.execute(command)
            else -> 0 to null
        }
    }
}

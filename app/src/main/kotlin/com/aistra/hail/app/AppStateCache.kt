package com.aistra.hail.app

import android.content.pm.ApplicationInfo
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.aistra.hail.utils.HPackages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object AppStateCache : CoroutineScope {
    data class Update(val version: Int, val packageNames: Set<String>?)

    override val coroutineContext = SupervisorJob() + Dispatchers.Default

    private val states = ConcurrentHashMap<String, AppInfo.State>()
    private val versionValue = AtomicInteger(0)
    private val versionLiveData = MutableLiveData(0)
    private val updatesLiveData = MutableLiveData(Update(0, null))
    private var workingMode = HailData.workingMode

    val version: LiveData<Int> get() = versionLiveData
    val updates: LiveData<Update> get() = updatesLiveData

    fun stateOf(packageName: String): AppInfo.State? {
        ensureWorkingMode()
        return states[packageName]
    }

    fun stateOrDefault(packageName: String): AppInfo.State =
        stateOf(packageName) ?: AppInfo.State.UNFROZEN

    fun stateOrRefresh(packageName: String): AppInfo.State {
        ensureWorkingMode()
        return states[packageName] ?: refresh(packageName)
    }

    fun statesFor(packageNames: Collection<String>): Map<String, AppInfo.State> {
        ensureWorkingMode()
        return packageNames.associateWith { states[it] ?: AppInfo.State.UNFROZEN }
    }

    fun prime(packageNames: Collection<String>): Map<String, AppInfo.State> {
        ensureWorkingMode()
        val missing = packageNames.distinct().filterNot { states.containsKey(it) }
        if (missing.isNotEmpty()) refresh(missing)
        return statesFor(packageNames)
    }

    fun primeAsync(
        packageNames: Collection<String>,
        onComplete: ((Map<String, AppInfo.State>) -> Unit)? = null
    ) {
        val targets = packageNames.distinct()
        if (targets.isEmpty()) return
        launch(Dispatchers.IO) {
            val result = prime(targets)
            if (onComplete != null) withContext(Dispatchers.Main) { onComplete(result) }
        }
    }

    fun refresh(packageName: String): AppInfo.State {
        ensureWorkingMode()
        val state = resolveState(packageName)
        if (states.put(packageName, state) != state) publishVersion(setOf(packageName))
        return state
    }

    fun refresh(packageNames: Collection<String>): Map<String, AppInfo.State> {
        ensureWorkingMode()
        val changed = mutableSetOf<String>()
        val result = packageNames.distinct().associateWith {
            resolveState(it).also { state ->
                if (states.put(it, state) != state) changed += it
            }
        }
        if (changed.isNotEmpty()) publishVersion(changed)
        return result
    }

    fun refreshAsync(packageNames: Collection<String>, delayMillis: Long = 1500L) {
        val targets = packageNames.distinct()
        if (targets.isEmpty()) return
        launch(Dispatchers.IO) {
            if (delayMillis > 0) delay(delayMillis)
            refresh(targets)
        }
    }

    fun markFrozen(packageNames: Collection<String>, frozen: Boolean) {
        ensureWorkingMode()
        val state = if (frozen) AppInfo.State.FROZEN else AppInfo.State.UNFROZEN
        val changed = mutableSetOf<String>()
        packageNames.distinct().forEach {
            if (states.put(it, state) != state) changed += it
        }
        if (changed.isNotEmpty()) publishVersion(changed)
    }

    private fun resolveState(packageName: String): AppInfo.State {
        val info = HPackages.getApplicationInfoOrNull(packageName) ?: return AppInfo.State.NOT_FOUND
        if (HPackages.isAppUninstalled(info)) return AppInfo.State.NOT_FOUND
        return if (isFrozen(info)) AppInfo.State.FROZEN else AppInfo.State.UNFROZEN
    }

    private fun isFrozen(info: ApplicationInfo): Boolean = when {
        HailData.workingMode.endsWith(HailData.STOP) ->
            info.flags and ApplicationInfo.FLAG_STOPPED == ApplicationInfo.FLAG_STOPPED

        HailData.workingMode.endsWith(HailData.DISABLE) -> !info.enabled
        HailData.workingMode.endsWith(HailData.HIDE) -> privateFlags(info) and 1 == 1
        HailData.workingMode.endsWith(HailData.SUSPEND) ->
            info.flags and ApplicationInfo.FLAG_SUSPENDED == ApplicationInfo.FLAG_SUSPENDED

        else -> !info.enabled
                || privateFlags(info) and 1 == 1
                || info.flags and ApplicationInfo.FLAG_SUSPENDED == ApplicationInfo.FLAG_SUSPENDED
    }

    private fun privateFlags(info: ApplicationInfo): Int = runCatching {
        ApplicationInfo::class.java.getField("privateFlags").get(info) as Int
    }.getOrDefault(0)

    private fun ensureWorkingMode() {
        val currentMode = HailData.workingMode
        if (workingMode == currentMode) return
        synchronized(this) {
            if (workingMode == currentMode) return@synchronized
            workingMode = currentMode
            states.clear()
            publishVersion(null)
        }
    }

    private fun publishVersion(packageNames: Set<String>?) {
        val version = versionValue.incrementAndGet()
        versionLiveData.postValue(version)
        updatesLiveData.postValue(Update(version, packageNames))
    }
}

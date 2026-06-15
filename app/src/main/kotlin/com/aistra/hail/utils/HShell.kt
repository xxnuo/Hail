package com.aistra.hail.utils

import android.os.Build
import androidx.annotation.RequiresApi

object HShell {
    private const val BATCH_STATUS_PREFIX = "__HAIL_STATUS__"

    fun execute(command: String, root: Boolean): Pair<Int, String?> = runCatching {
        ProcessBuilder(if (root) "su" else "sh").redirectErrorStream(true).start().run {
            val output = StringBuffer()
            val reader = Thread {
                inputStream.bufferedReader().use {
                    output.append(it.readText())
                }
            }.also { it.start() }
            outputStream.use {
                it.write(command.toByteArray())
            }
            waitFor().also {
                reader.join()
                destroy()
            } to output.toString()
        }
    }.getOrElse { 1 to it.stackTraceToString() }

    private fun execSU(command: String) = execute(command, true)

    private fun execSUBatch(packageNames: Collection<String>, command: (String) -> String): Set<String> {
        val targets = packageNames.distinct()
        if (targets.isEmpty()) return emptySet()
        val script = buildString {
            targets.forEachIndexed { index, packageName ->
                append(command(shellQuote(packageName))).append('\n')
                append("echo $BATCH_STATUS_PREFIX$index:$?\n")
            }
        }
        val output = execSU(script).second.orEmpty()
        val successIndexes = output.lineSequence().mapNotNull { line ->
            if (!line.startsWith(BATCH_STATUS_PREFIX)) return@mapNotNull null
            val status = line.removePrefix(BATCH_STATUS_PREFIX).split(':', limit = 2)
            status.getOrNull(0)?.toIntOrNull()?.takeIf { status.getOrNull(1) == "0" }
        }.toSet()
        return targets.filterIndexed { index, _ -> index in successIndexes }.toSet()
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\"'\"'")}'"

    val checkSU get() = execSU("whoami").first == 0

    val lockScreen get() = execSU("input keyevent KEYCODE_POWER").first == 0

    fun forceStopApp(packageName: String): Boolean =
        execSU("am force-stop --user current ${shellQuote(packageName)}").first == 0

    fun forceStopApps(packageNames: Collection<String>): Set<String> =
        execSUBatch(packageNames) { "am force-stop --user current $it" }

    fun setAppDisabled(packageName: String, disabled: Boolean): Boolean =
        execSU("pm ${if (disabled) "disable" else "enable"} --user current ${shellQuote(packageName)}").first == 0

    fun setAppsDisabled(packageNames: Collection<String>, disabled: Boolean): Set<String> =
        execSUBatch(packageNames) { "pm ${if (disabled) "disable" else "enable"} --user current $it" }

    fun setAppHidden(packageName: String, hidden: Boolean): Boolean =
        execSU("pm ${if (hidden) "hide" else "unhide"} --user current ${shellQuote(packageName)}").first == 0

    fun setAppsHidden(packageNames: Collection<String>, hidden: Boolean): Set<String> =
        execSUBatch(packageNames) { "pm ${if (hidden) "hide" else "unhide"} --user current $it" }

    fun setAppSuspended(packageName: String, suspended: Boolean): Boolean =
        execSU("pm ${if (suspended) "suspend" else "unsuspend"} --user current ${shellQuote(packageName)}").first == 0

    fun setAppsSuspended(packageNames: Collection<String>, suspended: Boolean): Set<String> =
        execSUBatch(packageNames) { "pm ${if (suspended) "suspend" else "unsuspend"} --user current $it" }

    fun uninstallApp(packageName: String) = execSU(
        "pm ${if (HPackages.canUninstallNormally(packageName)) "uninstall" else "uninstall --user current"} ${shellQuote(packageName)}"
    ).first == 0

    fun reinstallApp(packageName: String) =
        execSU("pm install-existing --user current ${shellQuote(packageName)}").first == 0

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(packageName: String, restricted: Boolean) = execSU(
        "appops set --user current ${shellQuote(packageName)} RUN_ANY_IN_BACKGROUND ${if (restricted) "ignore" else "allow"}"
    ).first == 0
}

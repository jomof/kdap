package com.github.jomof.dap.sb

import com.github.jomof.dap.SBWatcher

/**
 * Wraps an [SBDebugger] so that every method call is reported to [watcher].
 *
 * Objects returned by decorated methods (e.g., [createTarget] → [SBTarget])
 * are themselves wrapped, so the entire SB object graph is observed.
 *
 * @see SBWatcher
 */
fun SBDebugger.watched(watcher: SBWatcher): SBDebugger = WatchedDebugger(this, watcher)

// ══════════════════════════════════════════════════════════════════════
// Internal helper
// ══════════════════════════════════════════════════════════════════════

/**
 * Invokes [block], reports the call to [watcher], and returns the result.
 * On exception, reports `"ERROR: <message>"` and re-throws.
 */
private inline fun <T> watch(
    watcher: SBWatcher,
    iface: String,
    method: String,
    args: String = "",
    resultToString: (T) -> String = { it.toString() },
    block: () -> T,
): T {
    return try {
        val result = block()
        watcher.onCall(iface, method, args, resultToString(result))
        result
    } catch (e: Throwable) {
        watcher.onCall(iface, method, args, "ERROR: ${e.message}")
        throw e
    }
}

/**
 * Suspending variant of [watch].
 */
private suspend inline fun <T> watchSuspend(
    watcher: SBWatcher,
    iface: String,
    method: String,
    args: String = "",
    resultToString: (T) -> String = { it.toString() },
    block: () -> T,
): T {
    return try {
        val result = block()
        watcher.onCall(iface, method, args, resultToString(result))
        result
    } catch (e: Throwable) {
        watcher.onCall(iface, method, args, "ERROR: ${e.message}")
        throw e
    }
}

// ══════════════════════════════════════════════════════════════════════
// SBDebugger decorator
// ══════════════════════════════════════════════════════════════════════

private class WatchedDebugger(
    private val real: SBDebugger,
    private val watcher: SBWatcher,
) : SBDebugger {

    override suspend fun setVariable(name: String, value: String) =
        watchSuspend(watcher, "SBDebugger", "setVariable", "name=$name, value=$value") {
            real.setVariable(name, value)
        }

    override suspend fun getVariable(name: String): String? =
        watchSuspend(watcher, "SBDebugger", "getVariable", "name=$name",
            resultToString = { it ?: "null" }) {
            real.getVariable(name)
        }

    override suspend fun createTarget(
        program: String?,
        triple: String?,
        platformName: String?,
        addDependent: Boolean,
    ): SBTarget {
        val args = listOfNotNull(
            program?.let { "program=$it" },
            triple?.let { "triple=$it" },
            platformName?.let { "platformName=$it" },
            if (addDependent) "addDependent=true" else null,
        ).joinToString(", ")
        return watchSuspend(watcher, "SBDebugger", "createTarget", args,
            resultToString = { "SBTarget" }) {
            real.createTarget(program, triple, platformName, addDependent)
                .let { WatchedTarget(it, watcher) }
        }
    }

    override suspend fun selectedTarget(): SBTarget =
        watchSuspend(watcher, "SBDebugger", "selectedTarget", resultToString = { "SBTarget" }) {
            WatchedTarget(real.selectedTarget(), watcher)
        }

    override suspend fun selectedPlatform(): SBPlatform =
        watchSuspend(watcher, "SBDebugger", "selectedPlatform", resultToString = { "SBPlatform" }) {
            WatchedPlatform(real.selectedPlatform(), watcher)
        }
}

// ══════════════════════════════════════════════════════════════════════
// SBTarget decorator
// ══════════════════════════════════════════════════════════════════════

private class WatchedTarget(
    private val real: SBTarget,
    private val watcher: SBWatcher,
) : SBTarget {

    override suspend fun isValid(): Boolean =
        watchSuspend(watcher, "SBTarget", "isValid") { real.isValid() }

    override suspend fun launch(launchInfo: SBLaunchInfo): SBProcess {
        val innerInfo = if (launchInfo is WatchedLaunchInfo) launchInfo.real else launchInfo
        return watchSuspend(watcher, "SBTarget", "launch", "launchInfo=SBLaunchInfo",
            resultToString = { "SBProcess" }) {
            WatchedProcess(real.launch(innerInfo), watcher)
        }
    }

    override suspend fun attach(attachInfo: SBAttachInfo): SBProcess {
        val innerInfo = if (attachInfo is WatchedAttachInfo) attachInfo.real else attachInfo
        return watchSuspend(watcher, "SBTarget", "attach", "attachInfo=SBAttachInfo",
            resultToString = { "SBProcess" }) {
            WatchedProcess(real.attach(innerInfo), watcher)
        }
    }

    override suspend fun launchInfo(): SBLaunchInfo =
        watchSuspend(watcher, "SBTarget", "launchInfo", resultToString = { "SBLaunchInfo" }) {
            WatchedLaunchInfo(real.launchInfo(), watcher)
        }

    override suspend fun setLaunchInfo(info: SBLaunchInfo) {
        val innerInfo = if (info is WatchedLaunchInfo) info.real else info
        watchSuspend(watcher, "SBTarget", "setLaunchInfo", "info=SBLaunchInfo",
            resultToString = { "Unit" }) {
            real.setLaunchInfo(innerInfo)
        }
    }

    override suspend fun process(): SBProcess =
        watchSuspend(watcher, "SBTarget", "process", resultToString = { "SBProcess" }) {
            WatchedProcess(real.process(), watcher)
        }

    override suspend fun executable(): SBFileSpec =
        watchSuspend(watcher, "SBTarget", "executable", resultToString = { "SBFileSpec" }) {
            WatchedFileSpec(real.executable(), watcher)
        }

    override suspend fun platform(): SBPlatform =
        watchSuspend(watcher, "SBTarget", "platform", resultToString = { "SBPlatform" }) {
            WatchedPlatform(real.platform(), watcher)
        }
}

// ══════════════════════════════════════════════════════════════════════
// SBProcess decorator
// ══════════════════════════════════════════════════════════════════════

private class WatchedProcess(
    private val real: SBProcess,
    private val watcher: SBWatcher,
) : SBProcess {

    override suspend fun isValid(): Boolean =
        watchSuspend(watcher, "SBProcess", "isValid") { real.isValid() }

    override suspend fun processId(): Long =
        watchSuspend(watcher, "SBProcess", "processId") { real.processId() }

    override suspend fun state(): ProcessState =
        watchSuspend(watcher, "SBProcess", "state") { real.state() }

    override suspend fun exitStatus(): Int =
        watchSuspend(watcher, "SBProcess", "exitStatus") { real.exitStatus() }

    override suspend fun resume() =
        watchSuspend(watcher, "SBProcess", "resume", resultToString = { "Unit" }) {
            real.resume()
        }

    override suspend fun kill() =
        watchSuspend(watcher, "SBProcess", "kill", resultToString = { "Unit" }) {
            real.kill()
        }

    override suspend fun detach(keepStopped: Boolean) =
        watchSuspend(watcher, "SBProcess", "detach", "keepStopped=$keepStopped",
            resultToString = { "Unit" }) {
            real.detach(keepStopped)
        }

    override suspend fun signal(signo: Int) =
        watchSuspend(watcher, "SBProcess", "signal", "signo=$signo",
            resultToString = { "Unit" }) {
            real.signal(signo)
        }

    override suspend fun unixSignals(): SBUnixSignals =
        watchSuspend(watcher, "SBProcess", "unixSignals",
            resultToString = { "SBUnixSignals" }) {
            WatchedUnixSignals(real.unixSignals(), watcher)
        }
}

// ══════════════════════════════════════════════════════════════════════
// SBLaunchInfo decorator
// ══════════════════════════════════════════════════════════════════════

private class WatchedLaunchInfo(
    internal val real: SBLaunchInfo,
    private val watcher: SBWatcher,
) : SBLaunchInfo {

    override suspend fun setArguments(args: List<String>, append: Boolean) =
        watchSuspend(watcher, "SBLaunchInfo", "setArguments",
            "args=$args, append=$append", resultToString = { "Unit" }) {
            real.setArguments(args, append)
        }

    override suspend fun setWorkingDirectory(path: String) =
        watchSuspend(watcher, "SBLaunchInfo", "setWorkingDirectory",
            "path=$path", resultToString = { "Unit" }) {
            real.setWorkingDirectory(path)
        }

    override suspend fun setLaunchFlags(flags: Set<LaunchFlag>) =
        watchSuspend(watcher, "SBLaunchInfo", "setLaunchFlags",
            "flags=$flags", resultToString = { "Unit" }) {
            real.setLaunchFlags(flags)
        }

    override suspend fun launchFlags(): Set<LaunchFlag> =
        watchSuspend(watcher, "SBLaunchInfo", "launchFlags") { real.launchFlags() }

    override suspend fun setEnvironment(env: SBEnvironment, append: Boolean) {
        val innerEnv = if (env is WatchedEnvironment) env.real else env
        watchSuspend(watcher, "SBLaunchInfo", "setEnvironment",
            "append=$append", resultToString = { "Unit" }) {
            real.setEnvironment(innerEnv, append)
        }
    }

    override suspend fun addOpenFileAction(fd: Int, path: String, read: Boolean, write: Boolean) =
        watchSuspend(watcher, "SBLaunchInfo", "addOpenFileAction",
            "fd=$fd, path=$path, read=$read, write=$write",
            resultToString = { "Unit" }) {
            real.addOpenFileAction(fd, path, read, write)
        }

    override suspend fun arguments(): List<String> =
        watchSuspend(watcher, "SBLaunchInfo", "arguments") { real.arguments() }

    override suspend fun workingDirectory(): String? =
        watchSuspend(watcher, "SBLaunchInfo", "workingDirectory",
            resultToString = { it ?: "null" }) {
            real.workingDirectory()
        }
}

// ══════════════════════════════════════════════════════════════════════
// SBAttachInfo decorator
// ══════════════════════════════════════════════════════════════════════

private class WatchedAttachInfo(
    internal val real: SBAttachInfo,
    private val watcher: SBWatcher,
) : SBAttachInfo {

    override suspend fun setProcessId(pid: Long) =
        watchSuspend(watcher, "SBAttachInfo", "setProcessId",
            "pid=$pid", resultToString = { "Unit" }) {
            real.setProcessId(pid)
        }

    override suspend fun setExecutable(path: String) =
        watchSuspend(watcher, "SBAttachInfo", "setExecutable",
            "path=$path", resultToString = { "Unit" }) {
            real.setExecutable(path)
        }

    override suspend fun setWaitForLaunch(wait: Boolean, async: Boolean) =
        watchSuspend(watcher, "SBAttachInfo", "setWaitForLaunch",
            "wait=$wait, async=$async", resultToString = { "Unit" }) {
            real.setWaitForLaunch(wait, async)
        }

    override suspend fun setIgnoreExisting(ignore: Boolean) =
        watchSuspend(watcher, "SBAttachInfo", "setIgnoreExisting",
            "ignore=$ignore", resultToString = { "Unit" }) {
            real.setIgnoreExisting(ignore)
        }
}

// ══════════════════════════════════════════════════════════════════════
// SBEnvironment decorator
// ══════════════════════════════════════════════════════════════════════

private class WatchedEnvironment(
    internal val real: SBEnvironment,
    private val watcher: SBWatcher,
) : SBEnvironment {

    override suspend fun set(key: String, value: String, overwrite: Boolean) =
        watchSuspend(watcher, "SBEnvironment", "set",
            "key=$key, value=$value, overwrite=$overwrite",
            resultToString = { "Unit" }) {
            real.set(key, value, overwrite)
        }

    override suspend fun entries(): Map<String, String> =
        watchSuspend(watcher, "SBEnvironment", "entries") { real.entries() }
}

// ══════════════════════════════════════════════════════════════════════
// SBPlatform decorator
// ══════════════════════════════════════════════════════════════════════

private class WatchedPlatform(
    private val real: SBPlatform,
    private val watcher: SBWatcher,
) : SBPlatform {

    override suspend fun triple(): String =
        watchSuspend(watcher, "SBPlatform", "triple") { real.triple() }

    override suspend fun environment(): SBEnvironment =
        watchSuspend(watcher, "SBPlatform", "environment",
            resultToString = { "SBEnvironment" }) {
            WatchedEnvironment(real.environment(), watcher)
        }

    override suspend fun name(): String =
        watchSuspend(watcher, "SBPlatform", "name") { real.name() }

    override suspend fun getFilePermissions(path: String): Int =
        watchSuspend(watcher, "SBPlatform", "getFilePermissions", "path=$path") {
            real.getFilePermissions(path)
        }
}

// ══════════════════════════════════════════════════════════════════════
// SBFileSpec decorator
// ══════════════════════════════════════════════════════════════════════

private class WatchedFileSpec(
    private val real: SBFileSpec,
    private val watcher: SBWatcher,
) : SBFileSpec {

    override suspend fun path(): String =
        watchSuspend(watcher, "SBFileSpec", "path") { real.path() }

    override suspend fun isValid(): Boolean =
        watchSuspend(watcher, "SBFileSpec", "isValid") { real.isValid() }
}

// ══════════════════════════════════════════════════════════════════════
// SBUnixSignals decorator
// ══════════════════════════════════════════════════════════════════════

private class WatchedUnixSignals(
    private val real: SBUnixSignals,
    private val watcher: SBWatcher,
) : SBUnixSignals {

    override suspend fun isValid(): Boolean =
        watchSuspend(watcher, "SBUnixSignals", "isValid") { real.isValid() }

    override suspend fun signalNumberFromName(name: String): Int? =
        watchSuspend(watcher, "SBUnixSignals", "signalNumberFromName", "name=$name",
            resultToString = { it?.toString() ?: "null" }) {
            real.signalNumberFromName(name)
        }

    override suspend fun setShouldSuppress(signo: Int, suppress: Boolean) =
        watchSuspend(watcher, "SBUnixSignals", "setShouldSuppress",
            "signo=$signo, suppress=$suppress", resultToString = { "Unit" }) {
            real.setShouldSuppress(signo, suppress)
        }

    override suspend fun setShouldStop(signo: Int, stop: Boolean) =
        watchSuspend(watcher, "SBUnixSignals", "setShouldStop",
            "signo=$signo, stop=$stop", resultToString = { "Unit" }) {
            real.setShouldStop(signo, stop)
        }

    override suspend fun setShouldNotify(signo: Int, notify: Boolean) =
        watchSuspend(watcher, "SBUnixSignals", "setShouldNotify",
            "signo=$signo, notify=$notify", resultToString = { "Unit" }) {
            real.setShouldNotify(signo, notify)
        }
}

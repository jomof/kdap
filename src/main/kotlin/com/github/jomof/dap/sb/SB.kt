package com.github.jomof.dap.sb

/**
 * Pure Kotlin interfaces mirroring LLDB's C++ SB API.
 *
 * These interfaces decouple launch/attach/disconnect logic from the
 * underlying LLDB transport. The current implementation routes calls
 * through lldb-dap's command interpreter via DAP evaluate requests
 * ([LldbDapDebugger]). A future implementation could use JNI/JNA to
 * call liblldb.so directly without changing any caller code.
 *
 * All methods are `suspend` because the lldb-dap implementation is
 * asynchronous (sends a request and awaits a response). A JNI/JNA
 * implementation would wrap blocking native calls with
 * `withContext(Dispatchers.IO)`.
 *
 * ## Naming conventions
 *
 * Interface and method names mirror the C++ SB API:
 * - `SBDebugger` → `SBDebugger`
 * - `SBTarget::LaunchSimple` → `SBTarget.launch`
 * - `SBLaunchInfo::SetArguments` → `SBLaunchInfo.setArguments`
 */

// ══════════════════════════════════════════════════════════════════════
// Enums
// ══════════════════════════════════════════════════════════════════════

/**
 * Flags controlling process launch behavior.
 *
 * Maps to LLDB's `lldb::LaunchFlags`.
 */
enum class LaunchFlag {
    /** Stop the process immediately after launch (before it runs). */
    StopAtEntry,
    /** Disable address space layout randomization. */
    DisableASLR,
}

/**
 * State of a debugged process.
 *
 * Maps to LLDB's `lldb::StateType`. Only the states referenced by
 * `launch.rs` are modeled; others can be added as needed.
 */
enum class ProcessState {
    Invalid,
    Unloaded,
    Connected,
    Attaching,
    Launching,
    Stopped,
    Running,
    Stepping,
    Crashed,
    Detached,
    Exited,
    Suspended;

    /** Returns `true` if the process is alive (not exited, detached, or invalid). */
    fun isAlive(): Boolean = when (this) {
        Stopped, Running, Stepping, Crashed, Suspended -> true
        else -> false
    }

    /** Returns `true` if the process is currently executing. */
    fun isRunning(): Boolean = when (this) {
        Running, Stepping -> true
        else -> false
    }
}

// ══════════════════════════════════════════════════════════════════════
// SBDebugger
// ══════════════════════════════════════════════════════════════════════

/**
 * The top-level LLDB debugger instance.
 *
 * Maps to LLDB's `SBDebugger`. Provides access to debugger-wide settings,
 * target creation, and platform information.
 */
interface SBDebugger {
    /**
     * Sets an LLDB internal setting.
     *
     * Maps to `SBDebugger::SetInternalVariable` / `settings set`.
     *
     * @param name  setting name (e.g., `"target.preload-symbols"`)
     * @param value setting value (e.g., `"false"`)
     */
    suspend fun setVariable(name: String, value: String)

    /**
     * Gets an LLDB internal setting value.
     *
     * Maps to `SBDebugger::GetInternalVariableValue` / `settings show`.
     *
     * @param name setting name
     * @return the setting value as a string, or `null` if not found
     */
    suspend fun getVariable(name: String): String?

    /**
     * Creates a debug target from a program path.
     *
     * Maps to `SBDebugger::CreateTarget`.
     *
     * @param program      path to the executable, or `null` for an empty target
     * @param triple       target triple (e.g., `"x86_64-unknown-linux-gnu"`), or `null`
     * @param platformName platform name (e.g., `"host"`), or `null`
     * @param addDependent whether to add dependent modules
     * @return the created target
     * @throws SBError if target creation fails
     */
    suspend fun createTarget(
        program: String? = null,
        triple: String? = null,
        platformName: String? = null,
        addDependent: Boolean = false,
    ): SBTarget

    /**
     * Returns the currently selected target.
     *
     * Maps to `SBDebugger::GetSelectedTarget`.
     */
    suspend fun selectedTarget(): SBTarget

    /**
     * Returns the currently selected platform.
     *
     * Maps to `SBDebugger::GetSelectedPlatform`.
     */
    suspend fun selectedPlatform(): SBPlatform
}

// ══════════════════════════════════════════════════════════════════════
// SBTarget
// ══════════════════════════════════════════════════════════════════════

/**
 * A debug target (executable + modules).
 *
 * Maps to LLDB's `SBTarget`.
 */
interface SBTarget {
    /** Whether this target object is valid. Maps to `SBTarget::IsValid`. */
    suspend fun isValid(): Boolean

    /**
     * Launches a new process using the given launch info.
     *
     * Maps to `SBTarget::Launch`.
     *
     * @return the launched process
     * @throws SBError if launch fails
     */
    suspend fun launch(launchInfo: SBLaunchInfo): SBProcess

    /**
     * Attaches to an existing process.
     *
     * Maps to `SBTarget::Attach`.
     *
     * @return the attached process
     * @throws SBError if attach fails
     */
    suspend fun attach(attachInfo: SBAttachInfo): SBProcess

    /**
     * Returns the current launch info for this target.
     *
     * Maps to `SBTarget::GetLaunchInfo`.
     */
    suspend fun launchInfo(): SBLaunchInfo

    /**
     * Sets the launch info for this target.
     *
     * Maps to `SBTarget::SetLaunchInfo`.
     */
    suspend fun setLaunchInfo(info: SBLaunchInfo)

    /**
     * Returns the process associated with this target.
     *
     * Maps to `SBTarget::GetProcess`.
     */
    suspend fun process(): SBProcess

    /**
     * Returns the main executable module's file spec.
     *
     * Maps to `SBTarget::GetExecutable`.
     */
    suspend fun executable(): SBFileSpec

    /**
     * Returns the platform associated with this target.
     *
     * Maps to `SBTarget::GetPlatform`.
     */
    suspend fun platform(): SBPlatform
}

// ══════════════════════════════════════════════════════════════════════
// SBProcess
// ══════════════════════════════════════════════════════════════════════

/**
 * A running (or stopped) debugged process.
 *
 * Maps to LLDB's `SBProcess`.
 */
interface SBProcess {
    /** Whether this process object is valid. Maps to `SBProcess::IsValid`. */
    suspend fun isValid(): Boolean

    /**
     * Returns the system process ID.
     *
     * Maps to `SBProcess::GetProcessID`.
     */
    suspend fun processId(): Long

    /**
     * Returns the current state of the process.
     *
     * Maps to `SBProcess::GetState`.
     */
    suspend fun state(): ProcessState

    /**
     * Returns the exit status of the process. Only valid when [state]
     * returns [ProcessState.Exited].
     *
     * Maps to `SBProcess::GetExitStatus`.
     */
    suspend fun exitStatus(): Int

    /**
     * Resumes execution of the process.
     *
     * Maps to `SBProcess::Continue`.
     *
     * @throws SBError if resume fails
     */
    suspend fun resume()

    /**
     * Kills (terminates) the process.
     *
     * Maps to `SBProcess::Kill` / `SBProcess::Destroy`.
     *
     * @throws SBError if kill fails
     */
    suspend fun kill()

    /**
     * Detaches from the process.
     *
     * Maps to `SBProcess::Detach`.
     *
     * @param keepStopped if `true`, leave the process stopped after detaching
     * @throws SBError if detach fails
     */
    suspend fun detach(keepStopped: Boolean = false)

    /**
     * Sends a Unix signal to the process.
     *
     * Maps to `SBProcess::Signal`.
     *
     * @param signo the signal number
     * @throws SBError if sending the signal fails
     */
    suspend fun signal(signo: Int)

    /**
     * Returns the Unix signals interface for this process.
     *
     * Maps to `SBProcess::GetUnixSignals`.
     */
    suspend fun unixSignals(): SBUnixSignals
}

// ══════════════════════════════════════════════════════════════════════
// SBLaunchInfo
// ══════════════════════════════════════════════════════════════════════

/**
 * Configuration for launching a process.
 *
 * Maps to LLDB's `SBLaunchInfo`. In the lldb-dap implementation, setter
 * methods translate to `settings set target.*` commands; the accumulated
 * state is consumed by [SBTarget.launch].
 */
interface SBLaunchInfo {
    /**
     * Sets the command-line arguments for the process.
     *
     * Maps to `SBLaunchInfo::SetArguments`.
     *
     * @param args    argument list (not including argv[0])
     * @param append  if `true`, append to existing arguments
     */
    suspend fun setArguments(args: List<String>, append: Boolean = false)

    /**
     * Sets the working directory for the process.
     *
     * Maps to `SBLaunchInfo::SetWorkingDirectory`.
     */
    suspend fun setWorkingDirectory(path: String)

    /**
     * Sets the launch flags.
     *
     * Maps to `SBLaunchInfo::SetLaunchFlags`.
     */
    suspend fun setLaunchFlags(flags: Set<LaunchFlag>)

    /**
     * Returns the current launch flags.
     *
     * Maps to `SBLaunchInfo::GetLaunchFlags`.
     */
    suspend fun launchFlags(): Set<LaunchFlag>

    /**
     * Sets the environment for the launched process.
     *
     * Maps to `SBLaunchInfo::SetEnvironment`.
     *
     * @param env    the environment to set
     * @param append if `true`, merge with the existing environment
     */
    suspend fun setEnvironment(env: SBEnvironment, append: Boolean = false)

    /**
     * Redirects a file descriptor for the launched process.
     *
     * Maps to `SBLaunchInfo::AddOpenFileAction`.
     *
     * @param fd   file descriptor number (0=stdin, 1=stdout, 2=stderr)
     * @param path file path to open
     * @param read open for reading
     * @param write open for writing
     */
    suspend fun addOpenFileAction(fd: Int, path: String, read: Boolean, write: Boolean)

    /**
     * Returns the current argument list.
     *
     * Maps to `SBLaunchInfo::GetArguments`.
     */
    suspend fun arguments(): List<String>

    /**
     * Returns the current working directory, or `null` if not set.
     *
     * Maps to `SBLaunchInfo::GetWorkingDirectory`.
     */
    suspend fun workingDirectory(): String?
}

// ══════════════════════════════════════════════════════════════════════
// SBAttachInfo
// ══════════════════════════════════════════════════════════════════════

/**
 * Configuration for attaching to a process.
 *
 * Maps to LLDB's `SBAttachInfo`.
 */
interface SBAttachInfo {
    /**
     * Sets the process ID to attach to.
     *
     * Maps to `SBAttachInfo::SetProcessID`.
     */
    suspend fun setProcessId(pid: Long)

    /**
     * Sets the executable path for attach-by-name.
     *
     * Maps to `SBAttachInfo::SetExecutable`.
     */
    suspend fun setExecutable(path: String)

    /**
     * Configures whether to wait for the process to launch before attaching.
     *
     * Maps to `SBAttachInfo::SetWaitForLaunch`.
     *
     * @param wait  if `true`, wait for the process to launch
     * @param async if `true`, return immediately and attach when the process appears
     */
    suspend fun setWaitForLaunch(wait: Boolean, async: Boolean = false)

    /**
     * Configures whether to ignore already-running instances when waiting.
     *
     * Maps to `SBAttachInfo::SetIgnoreExisting`.
     */
    suspend fun setIgnoreExisting(ignore: Boolean)
}

// ══════════════════════════════════════════════════════════════════════
// SBEnvironment
// ══════════════════════════════════════════════════════════════════════

/**
 * A set of environment variables for a process.
 *
 * Maps to LLDB's `SBEnvironment`.
 */
interface SBEnvironment {
    /**
     * Sets an environment variable.
     *
     * Maps to `SBEnvironment::Set`.
     *
     * @param key       variable name
     * @param value     variable value
     * @param overwrite if `true`, overwrite an existing variable with the same name
     */
    suspend fun set(key: String, value: String, overwrite: Boolean = true)

    /**
     * Returns all entries as key-value pairs.
     */
    suspend fun entries(): Map<String, String>
}

// ══════════════════════════════════════════════════════════════════════
// SBPlatform
// ══════════════════════════════════════════════════════════════════════

/**
 * A debugging platform (host, remote, simulator, etc.).
 *
 * Maps to LLDB's `SBPlatform`.
 */
interface SBPlatform {
    /**
     * Returns the target triple (e.g., `"x86_64-unknown-linux-gnu"`).
     *
     * Maps to `SBPlatform::GetTriple`.
     */
    suspend fun triple(): String

    /**
     * Returns the platform's environment variables.
     *
     * Maps to `SBPlatform::GetEnvironment`.
     */
    suspend fun environment(): SBEnvironment

    /**
     * Returns the platform name (e.g., `"host"`).
     *
     * Maps to `SBPlatform::GetName`.
     */
    suspend fun name(): String

    /**
     * Returns the file permissions for a path on this platform.
     *
     * Maps to `SBPlatform::GetFilePermissions`. Returns `0` if the
     * path does not exist or is inaccessible.
     */
    suspend fun getFilePermissions(path: String): Int
}

// ══════════════════════════════════════════════════════════════════════
// SBFileSpec
// ══════════════════════════════════════════════════════════════════════

/**
 * A file path specification.
 *
 * Maps to LLDB's `SBFileSpec`.
 */
interface SBFileSpec {
    /**
     * Returns the full file path as a string.
     *
     * Maps to `SBFileSpec::GetPath` / `SBFileSpec::GetDirectory` + `GetFilename`.
     */
    suspend fun path(): String

    /** Whether this file spec is valid. Maps to `SBFileSpec::IsValid`. */
    suspend fun isValid(): Boolean
}

// ══════════════════════════════════════════════════════════════════════
// SBUnixSignals
// ══════════════════════════════════════════════════════════════════════

/**
 * Interface for querying and configuring Unix signal handling.
 *
 * Maps to LLDB's `SBUnixSignals`.
 */
interface SBUnixSignals {
    /** Whether this signals object is valid. Maps to `SBUnixSignals::IsValid`. */
    suspend fun isValid(): Boolean

    /**
     * Returns the signal number for a signal name, or `null` if not found.
     *
     * Maps to `SBUnixSignals::GetSignalNumberFromName`.
     */
    suspend fun signalNumberFromName(name: String): Int?

    /**
     * Configures whether the debugger should suppress a signal
     * (not deliver it to the process).
     *
     * Maps to `SBUnixSignals::SetShouldSuppress`.
     */
    suspend fun setShouldSuppress(signo: Int, suppress: Boolean)

    /**
     * Configures whether the debugger should stop when a signal is received.
     *
     * Maps to `SBUnixSignals::SetShouldStop`.
     */
    suspend fun setShouldStop(signo: Int, stop: Boolean)

    /**
     * Configures whether the debugger should notify the user when a signal
     * is received.
     *
     * Maps to `SBUnixSignals::SetShouldNotify`.
     */
    suspend fun setShouldNotify(signo: Int, notify: Boolean)
}

// ══════════════════════════════════════════════════════════════════════
// Errors
// ══════════════════════════════════════════════════════════════════════

/**
 * Exception thrown when an SB API call fails.
 *
 * Maps to LLDB's `SBError` when it indicates a failure.
 */
class SBError(message: String, cause: Throwable? = null) : Exception(message, cause)

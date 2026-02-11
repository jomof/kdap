package com.github.jomof.dap.sb

import com.github.jomof.dap.DapSession.AsyncRequestContext
import com.github.jomof.dap.messages.DapResponse
import org.json.JSONObject
import java.util.logging.Logger

/**
 * Factory that creates an [SBDebugger] backed by lldb-dap evaluate requests.
 *
 * All SB API calls are translated to **Python SB API calls** sent via DAP
 * `evaluate` requests with `context: "repl"` and the `script` command prefix.
 * This requires an lldb-dap built with Python support (SWIG bindings).
 *
 * ## Value transport
 *
 * Return values are obtained via Python's interactive auto-display mechanism
 * (`Py_single_input` / `PRINT_EXPR`), which places the `repr()` of the last
 * expression in a semicolon chain into the evaluate response's `result` field
 * **without** generating separate DAP `output` events. This is critical because
 * `print()` writes to `sys.stdout`, which LLDB routes through its I/O handler,
 * causing spurious `output` events that pollute the DAP event stream.
 *
 * | Pattern                                        | Result field   | Output event? |
 * |------------------------------------------------|----------------|---------------|
 * | `script print('hello')`                        | `hello`        | **YES** — bad |
 * | `script 'hello'`                               | `'hello'`      | **NO** — good |
 * | `script x = 42; x`                             | `42`           | **NO** — good |
 * | `script _e = lldb.SBError(); op(_e); _check(e)`| (empty/None)   | **NO** — good |
 *
 * ## Stateful Python objects
 *
 * The Python interpreter inside lldb-dap is **persistent** across evaluate
 * calls. Stateful objects like `SBLaunchInfo` and `SBAttachInfo` are stored
 * as Python-side variables (e.g. `_kdap_li`, `_kdap_ai`) and mutated across
 * multiple Kotlin method calls before being passed to `target.Launch()` or
 * `target.Attach()`.
 *
 * ## Future replacement
 *
 * This entire file can be replaced with a JNI/JNA implementation that calls
 * liblldb.so directly. The interfaces in `SB.kt` remain unchanged.
 */
suspend fun createDebugger(ctx: AsyncRequestContext): SBDebugger {
    // Define a reusable error-checking helper in the persistent interpreter.
    // _kdap_check(e) returns None on success, raises RuntimeError on failure.
    evalPyVoid(ctx,
        "exec(\"def _kdap_check(e):\\n" +
            " if e.Fail(): raise RuntimeError(e.GetCString() or 'SB error')\")")
    return LldbDapDebugger(ctx)
}

// ══════════════════════════════════════════════════════════════════════
// Evaluate helpers
// ══════════════════════════════════════════════════════════════════════

/**
 * Result of evaluating a command via DAP evaluate request.
 */
data class EvaluateResult(
    /** Whether the evaluate request succeeded. */
    val success: Boolean,
    /** The result text (command/script output). */
    val result: String,
    /** Error message if [success] is `false`. */
    val message: String?,
)

/**
 * Sends Python code to the backend via `script <code>`.
 *
 * The code runs in lldb-dap's persistent Python interpreter. The last
 * expression in a semicolon-separated chain is auto-displayed via
 * `PRINT_EXPR` and its `repr()` appears in the response `result` field.
 *
 * Uses [AsyncRequestContext.sendSilentRequestToBackendAndAwait] to
 * suppress console `output` events that Python's auto-display generates
 * via `sys.stdout`. The return value is read from the response `result`
 * field instead.
 */
internal suspend fun evaluatePython(ctx: AsyncRequestContext, code: String): EvaluateResult {
    val json = JSONObject().apply {
        put("type", "request")
        put("command", "evaluate")
        put("arguments", JSONObject().apply {
            put("expression", "script $code")
            put("context", "repl")
        })
    }
    val response: DapResponse = ctx.sendSilentRequestToBackendAndAwait(json.toString())
    val resultText = response.body["result"] as? String ?: ""
    return EvaluateResult(
        success = response.success,
        result = resultText,
        message = response.message,
    )
}

/**
 * Sends an LLDB CLI command to the backend via a DAP evaluate request
 * with `context: "repl"`. Used for raw commands (initCommands,
 * exec_commands, etc.) where CLI is more appropriate than Python.
 */
internal suspend fun evaluateCommand(ctx: AsyncRequestContext, command: String): EvaluateResult {
    val json = JSONObject().apply {
        put("type", "request")
        put("command", "evaluate")
        put("arguments", JSONObject().apply {
            put("expression", command)
            put("context", "repl")
        })
    }
    val response: DapResponse = ctx.sendRequestToBackendAndAwait(json.toString())
    val resultText = response.body["result"] as? String ?: ""
    return EvaluateResult(
        success = response.success,
        result = resultText,
        message = response.message,
    )
}

/**
 * Strips the `(lldb) <command>` echo prefix from evaluate output.
 */
private fun extractOutput(raw: String): String {
    val lines = raw.lines()
    val echoIdx = lines.indexOfFirst { it.trimStart().startsWith("(lldb)") }
    return if (echoIdx >= 0 && echoIdx + 1 < lines.size) {
        lines.drop(echoIdx + 1).joinToString("\n").trim()
    } else {
        raw.trim()
    }
}

/**
 * Executes Python code and returns the raw auto-displayed output
 * (after stripping the echo prefix). For boolean or integer results,
 * the caller parses this directly. For string results, use [evalPyStr]
 * which additionally strips Python repr quotes.
 *
 * Throws [SBError] if the evaluate request fails.
 */
private suspend fun evalPy(ctx: AsyncRequestContext, code: String): String {
    val result = evaluatePython(ctx, code)
    if (!result.success) {
        throw SBError(result.message ?: "Python SB call failed: $code")
    }
    return extractOutput(result.result)
}

/**
 * Executes Python code whose auto-displayed result is a Python string.
 * Strips both the echo prefix and Python repr quotes (e.g. `'hello'` → `hello`).
 *
 * Throws [SBError] if the evaluate request fails.
 */
private suspend fun evalPyStr(ctx: AsyncRequestContext, code: String): String {
    return stripPyRepr(evalPy(ctx, code))
}

/**
 * Executes Python code that produces no needed return value.
 * Throws [SBError] if the evaluate request fails.
 */
private suspend fun evalPyVoid(ctx: AsyncRequestContext, code: String) {
    val result = evaluatePython(ctx, code)
    if (!result.success) {
        throw SBError(result.message ?: "Python SB call failed: $code")
    }
}

// ══════════════════════════════════════════════════════════════════════
// Python string/value helpers
// ══════════════════════════════════════════════════════════════════════

/**
 * Strips Python `repr()` quotes from a string value.
 *
 * Python's auto-display wraps strings in quotes: `'hello'` or `"hello"`.
 * This removes the outer quotes and unescapes common escape sequences.
 */
private fun stripPyRepr(s: String): String {
    val t = s.trim()
    if (t.length >= 2) {
        val q = t[0]
        if ((q == '\'' || q == '"') && t.endsWith(q.toString())) {
            return t.substring(1, t.length - 1)
                .replace("\\$q", q.toString())
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }
    }
    return t
}

/**
 * Produces a Python string literal with proper escaping.
 * Uses single quotes: `'value'`.
 */
private fun pyStr(s: String): String {
    val escaped = s
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    return "'$escaped'"
}

/**
 * Produces a Python list literal from a list of strings.
 * E.g. `['arg1', 'arg2']`.
 */
private fun pyList(items: List<String>): String =
    "[${items.joinToString(", ") { pyStr(it) }}]"

/**
 * Produces a Python boolean literal.
 */
private fun pyBool(b: Boolean): String = if (b) "True" else "False"

// ══════════════════════════════════════════════════════════════════════
// SBDebugger implementation
// ══════════════════════════════════════════════════════════════════════

private class LldbDapDebugger(private val ctx: AsyncRequestContext) : SBDebugger {

    override suspend fun setVariable(name: String, value: String) {
        // SetInternalVariable returns SBError → check it.
        evalPyVoid(ctx,
            "_e = lldb.SBDebugger.SetInternalVariable(${pyStr(name)}, ${pyStr(value)}, " +
                "lldb.debugger.GetInstanceName()); _kdap_check(_e)")
    }

    override suspend fun getVariable(name: String): String? {
        val output = evalPyStr(ctx,
            "_r = lldb.SBDebugger.GetInternalVariableValue(${pyStr(name)}, " +
                "lldb.debugger.GetInstanceName()); " +
                "_r.GetStringAtIndex(0) if _r.GetSize() > 0 else ''")
        return output.ifEmpty { null }
    }

    override suspend fun createTarget(
        program: String?,
        triple: String?,
        platformName: String?,
        addDependent: Boolean,
    ): SBTarget {
        // CreateTarget returns (SBTarget, SBError) → check error.
        evalPyVoid(ctx, buildString {
            append("_e = lldb.SBError(); ")
            append("_t = lldb.debugger.CreateTarget(")
            append(if (program != null) pyStr(program) else "''")
            append(", ")
            append(if (triple != null) pyStr(triple) else "''")
            append(", ")
            append(if (platformName != null) pyStr(platformName) else "''")
            append(", ")
            append(pyBool(addDependent))
            append(", _e); _kdap_check(_e)")
        })
        return LldbDapTarget(ctx)
    }

    override suspend fun selectedTarget(): SBTarget = LldbDapTarget(ctx)

    override suspend fun selectedPlatform(): SBPlatform = LldbDapPlatform(ctx)
}

// ══════════════════════════════════════════════════════════════════════
// SBTarget implementation
// ══════════════════════════════════════════════════════════════════════

private class LldbDapTarget(private val ctx: AsyncRequestContext) : SBTarget {

    override suspend fun isValid(): Boolean {
        return evalPy(ctx, "lldb.target.IsValid()").trim() == "True"
    }

    override suspend fun launch(launchInfo: SBLaunchInfo): SBProcess {
        val v = (launchInfo as LldbDapLaunchInfo).pythonVarName
        // On macOS, ensure LLDB knows where to find debugserver for
        // remote platform launches. Set the internal setting directly
        // since env var LLDB_DEBUGSERVER_PATH may not reach LLDB's core.
        val debugServerPath = System.getenv("LLDB_DEBUGSERVER_PATH")
        if (debugServerPath != null) {
            evalPyVoid(ctx,
                "lldb.debugger.HandleCommand(" +
                    "'settings set platform.plugin.darwin.debugserver-path $debugServerPath')"
            )
        }
        // Launch returns (SBProcess, SBError). Check the SBError, then
        // validate the process is valid (remote platforms can return
        // success in the SBError but an invalid process when debugserver
        // is unavailable).
        val output = evalPy(ctx,
            "_e = lldb.SBError(); _p = lldb.target.Launch($v, _e); " +
                "_kdap_check(_e); " +
                "str(_p.GetProcessID()) if _p.IsValid() else " +
                "exec('raise Exception(\"Process is not valid after launch. \" + " +
                    "\"state=\" + str(_p.GetState()))')")
        val pid = output.trim().toLongOrNull()
        return LldbDapProcess(ctx, cachedPid = pid)
    }

    override suspend fun attach(attachInfo: SBAttachInfo): SBProcess {
        val v = (attachInfo as LldbDapAttachInfo).pythonVarName
        val output = evalPy(ctx,
            "_e = lldb.SBError(); _p = lldb.target.Attach($v, _e); " +
                "_kdap_check(_e); _p.GetProcessID()")
        val pid = output.trim().toLongOrNull()
        return LldbDapProcess(ctx, cachedPid = pid)
    }

    override suspend fun launchInfo(): SBLaunchInfo {
        // Create a persistent Python-side SBLaunchInfo variable.
        evalPyVoid(ctx, "_kdap_li = lldb.target.GetLaunchInfo()")
        return LldbDapLaunchInfo(ctx, "_kdap_li")
    }

    override suspend fun setLaunchInfo(info: SBLaunchInfo) {
        val v = (info as LldbDapLaunchInfo).pythonVarName
        evalPyVoid(ctx, "lldb.target.SetLaunchInfo($v)")
    }

    override suspend fun process(): SBProcess = LldbDapProcess(ctx)

    override suspend fun executable(): SBFileSpec {
        val path = evalPyStr(ctx,
            "_f = lldb.target.GetExecutable(); " +
                "_f.fullpath if _f.IsValid() else ''")
        return StaticFileSpec(path, path.isNotEmpty())
    }

    override suspend fun platform(): SBPlatform = LldbDapPlatform(ctx)
}

// ══════════════════════════════════════════════════════════════════════
// SBProcess implementation
// ══════════════════════════════════════════════════════════════════════

private class LldbDapProcess(
    private val ctx: AsyncRequestContext,
    private val cachedPid: Long? = null,
) : SBProcess {

    override suspend fun isValid(): Boolean {
        return evalPy(ctx, "lldb.target.GetProcess().IsValid()").trim() == "True"
    }

    override suspend fun processId(): Long {
        if (cachedPid != null) return cachedPid
        val output = evalPy(ctx, "lldb.target.GetProcess().GetProcessID()")
        return output.trim().toLongOrNull()
            ?: throw SBError("Could not parse process ID from: $output")
    }

    override suspend fun state(): ProcessState {
        val output = evalPy(ctx, "lldb.target.GetProcess().GetState()")
        return stateFromLldbInt(output.trim().toInt())
    }

    override suspend fun exitStatus(): Int {
        val output = evalPy(ctx, "lldb.target.GetProcess().GetExitStatus()")
        return output.trim().toInt()
    }

    override suspend fun resume() {
        // Continue() returns SBError.
        evalPyVoid(ctx,
            "_e = lldb.target.GetProcess().Continue(); _kdap_check(_e)")
    }

    override suspend fun kill() {
        evalPyVoid(ctx,
            "_e = lldb.target.GetProcess().Kill(); _kdap_check(_e)")
    }

    override suspend fun detach(keepStopped: Boolean) {
        evalPyVoid(ctx,
            "_e = lldb.target.GetProcess().Detach(${pyBool(keepStopped)}); _kdap_check(_e)")
    }

    override suspend fun signal(signo: Int) {
        evalPyVoid(ctx,
            "_e = lldb.target.GetProcess().Signal($signo); _kdap_check(_e)")
    }

    override suspend fun unixSignals(): SBUnixSignals = LldbDapUnixSignals(ctx)
}

/**
 * Maps LLDB's `lldb::StateType` integer values to our [ProcessState] enum.
 *
 * Values correspond to `lldb.eStateInvalid` (0) through
 * `lldb.eStateSuspended` (11).
 */
private fun stateFromLldbInt(state: Int): ProcessState = when (state) {
    0 -> ProcessState.Invalid
    1 -> ProcessState.Unloaded
    2 -> ProcessState.Connected
    3 -> ProcessState.Attaching
    4 -> ProcessState.Launching
    5 -> ProcessState.Stopped
    6 -> ProcessState.Running
    7 -> ProcessState.Stepping
    8 -> ProcessState.Crashed
    9 -> ProcessState.Detached
    10 -> ProcessState.Exited
    11 -> ProcessState.Suspended
    else -> error("Unknown LLDB StateType: $state")
}

// ══════════════════════════════════════════════════════════════════════
// SBLaunchInfo implementation
// ══════════════════════════════════════════════════════════════════════

/**
 * Launch info backed by a persistent Python-side `SBLaunchInfo` object.
 *
 * Each setter translates to a Python SB API call on the variable named
 * by [pythonVarName]. The accumulated state is consumed by
 * [LldbDapTarget.launch], which calls `target.Launch(varName, error)`.
 */
private class LldbDapLaunchInfo(
    private val ctx: AsyncRequestContext,
    /** Name of the Python variable holding the SBLaunchInfo object. */
    val pythonVarName: String,
) : SBLaunchInfo {
    private var flags: MutableSet<LaunchFlag> = mutableSetOf()

    override suspend fun setArguments(args: List<String>, append: Boolean) {
        if (args.isEmpty()) return
        evalPyVoid(ctx, "$pythonVarName.SetArguments(${pyList(args)}, ${pyBool(append)})")
    }

    override suspend fun setWorkingDirectory(path: String) {
        evalPyVoid(ctx, "$pythonVarName.SetWorkingDirectory(${pyStr(path)})")
    }

    override suspend fun setLaunchFlags(flags: Set<LaunchFlag>) {
        this.flags = flags.toMutableSet()
        val flagValue = flags.sumOf { lldbLaunchFlagValue(it) }
        evalPyVoid(ctx, "$pythonVarName.SetLaunchFlags($flagValue)")
    }

    override suspend fun launchFlags(): Set<LaunchFlag> = flags.toSet()

    override suspend fun setEnvironment(env: SBEnvironment, append: Boolean) {
        val entries = env.entries()
        if (entries.isEmpty()) return
        val code = buildString {
            append("_kdap_env = lldb.SBEnvironment(); ")
            for ((key, value) in entries) {
                append("_kdap_env.Set(${pyStr(key)}, ${pyStr(value)}, True); ")
            }
            append("$pythonVarName.SetEnvironment(_kdap_env, ${pyBool(append)})")
        }
        evalPyVoid(ctx, code)
    }

    override suspend fun addOpenFileAction(fd: Int, path: String, read: Boolean, write: Boolean) {
        evalPyVoid(ctx,
            "$pythonVarName.AddOpenFileAction($fd, ${pyStr(path)}, ${pyBool(read)}, ${pyBool(write)})")
    }

    override suspend fun arguments(): List<String> {
        val output = evalPyStr(ctx,
            "import json; _a = $pythonVarName.GetArguments(); " +
                "json.dumps([_a.GetStringAtIndex(i) for i in range(_a.GetSize())])")
        val trimmed = output.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
        val arr = org.json.JSONArray(trimmed)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    override suspend fun workingDirectory(): String? {
        val output = evalPyStr(ctx,
            "$pythonVarName.GetWorkingDirectory() or ''")
        return output.ifEmpty { null }
    }
}

/**
 * Returns the LLDB integer flag value for a [LaunchFlag].
 *
 * These correspond to `lldb::eLaunchFlagStopAtEntry` (4) and
 * `lldb::eLaunchFlagDisableASLR` (8).
 */
private fun lldbLaunchFlagValue(flag: LaunchFlag): Int = when (flag) {
    LaunchFlag.StopAtEntry -> 4   // lldb.eLaunchFlagStopAtEntry
    LaunchFlag.DisableASLR -> 8   // lldb.eLaunchFlagDisableASLR
}

// ══════════════════════════════════════════════════════════════════════
// SBAttachInfo implementation
// ══════════════════════════════════════════════════════════════════════

/**
 * Attach info backed by a persistent Python-side `SBAttachInfo` object.
 *
 * Each setter translates to a Python SB API call on the variable named
 * by [pythonVarName]. The accumulated state is consumed by
 * [LldbDapTarget.attach], which calls `target.Attach(varName, error)`.
 */
class LldbDapAttachInfo internal constructor(
    private val ctx: AsyncRequestContext,
    /** Name of the Python variable holding the SBAttachInfo object. */
    val pythonVarName: String,
) : SBAttachInfo {

    override suspend fun setProcessId(pid: Long) {
        evalPyVoid(ctx, "$pythonVarName.SetProcessID($pid)")
    }

    override suspend fun setExecutable(path: String) {
        evalPyVoid(ctx, "$pythonVarName.SetExecutable(${pyStr(path)})")
    }

    override suspend fun setWaitForLaunch(wait: Boolean, async: Boolean) {
        evalPyVoid(ctx, "$pythonVarName.SetWaitForLaunch(${pyBool(wait)}, ${pyBool(async)})")
    }

    override suspend fun setIgnoreExisting(ignore: Boolean) {
        evalPyVoid(ctx, "$pythonVarName.SetIgnoreExisting(${pyBool(ignore)})")
    }
}

/**
 * Creates a new [SBAttachInfo] backed by a persistent Python variable.
 */
suspend fun createAttachInfo(ctx: AsyncRequestContext): SBAttachInfo {
    evalPyVoid(ctx, "_kdap_ai = lldb.SBAttachInfo()")
    return LldbDapAttachInfo(ctx, "_kdap_ai")
}

// ══════════════════════════════════════════════════════════════════════
// SBEnvironment implementation
// ══════════════════════════════════════════════════════════════════════

/**
 * In-memory environment variable collection.
 *
 * Variables are accumulated locally in Kotlin and applied as a batch
 * to the Python-side SBLaunchInfo via [SBLaunchInfo.setEnvironment].
 * This avoids round-trips for each individual env var.
 */
class LldbDapEnvironment : SBEnvironment {
    private val vars = mutableMapOf<String, String>()

    override suspend fun set(key: String, value: String, overwrite: Boolean) {
        if (overwrite || key !in vars) {
            vars[key] = value
        }
    }

    override suspend fun entries(): Map<String, String> = vars.toMap()
}

/**
 * Creates a new empty [SBEnvironment].
 */
fun createEnvironment(): SBEnvironment = LldbDapEnvironment()

// ══════════════════════════════════════════════════════════════════════
// SBPlatform implementation
// ══════════════════════════════════════════════════════════════════════

private class LldbDapPlatform(private val ctx: AsyncRequestContext) : SBPlatform {

    override suspend fun triple(): String {
        return evalPyStr(ctx,
            "lldb.debugger.GetSelectedPlatform().GetTriple()")
    }

    override suspend fun environment(): SBEnvironment = LldbDapEnvironment()

    override suspend fun name(): String {
        return evalPyStr(ctx,
            "lldb.debugger.GetSelectedPlatform().GetName()")
    }

    override suspend fun getFilePermissions(path: String): Int {
        val output = evalPy(ctx,
            "lldb.debugger.GetSelectedPlatform()" +
                ".GetFilePermissions(lldb.SBFileSpec(${pyStr(path)}))")
        return output.trim().toInt()
    }
}

// ══════════════════════════════════════════════════════════════════════
// SBFileSpec implementation
// ══════════════════════════════════════════════════════════════════════

private data class StaticFileSpec(
    private val filePath: String,
    private val valid: Boolean,
) : SBFileSpec {
    override suspend fun path(): String = filePath
    override suspend fun isValid(): Boolean = valid
}

// ══════════════════════════════════════════════════════════════════════
// SBUnixSignals implementation
// ══════════════════════════════════════════════════════════════════════

private class LldbDapUnixSignals(private val ctx: AsyncRequestContext) : SBUnixSignals {

    override suspend fun isValid(): Boolean {
        return evalPy(ctx,
            "lldb.target.GetProcess().GetUnixSignals().IsValid()").trim() == "True"
    }

    override suspend fun signalNumberFromName(name: String): Int? {
        val output = evalPy(ctx,
            "lldb.target.GetProcess().GetUnixSignals()" +
                ".GetSignalNumberFromName(${pyStr(name)})")
        val signo = output.trim().toIntOrNull()
        // LLDB returns UINT32_MAX (4294967295) for "not found"
        return if (signo != null && signo in 0..65535) signo else null
    }

    override suspend fun setShouldSuppress(signo: Int, suppress: Boolean) {
        evalPyVoid(ctx,
            "lldb.target.GetProcess().GetUnixSignals()" +
                ".SetShouldSuppress($signo, ${pyBool(suppress)})")
    }

    override suspend fun setShouldStop(signo: Int, stop: Boolean) {
        evalPyVoid(ctx,
            "lldb.target.GetProcess().GetUnixSignals()" +
                ".SetShouldStop($signo, ${pyBool(stop)})")
    }

    override suspend fun setShouldNotify(signo: Int, notify: Boolean) {
        evalPyVoid(ctx,
            "lldb.target.GetProcess().GetUnixSignals()" +
                ".SetShouldNotify($signo, ${pyBool(notify)})")
    }
}

private val log = Logger.getLogger("com.github.jomof.dap.sb.LldbDapSB")

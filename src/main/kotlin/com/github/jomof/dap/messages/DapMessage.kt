package com.github.jomof.dap.messages

import org.json.JSONArray
import org.json.JSONObject

// ══════════════════════════════════════════════════════════════════════
// CodeLLDB types — field-for-field Kotlin equivalents of the Rust
// structs in codelldb-types/src/lib.rs.
// ══════════════════════════════════════════════════════════════════════

/**
 * An untagged union of two types, matching Rust's `Either<T1, T2>` with
 * `#[serde(untagged)]`.
 */
sealed class Either<out T1, out T2> {
    data class First<T1>(val value: T1) : Either<T1, Nothing>()
    data class Second<T2>(val value: T2) : Either<Nothing, T2>()
}

/** Specifies how source breakpoints should be set. */
enum class BreakpointMode {
    /** Match breakpoints by full path. */
    Path,
    /** Match breakpoints by file name only. */
    File;

    companion object {
        fun fromJson(value: String?): BreakpointMode? = when (value) {
            "path" -> Path
            "file" -> File
            else -> null
        }
    }
}

/**
 * Debug console mode.
 *
 * - [Commands]: treat input as debugger commands; prefix expressions with `?`
 * - [Evaluate]: treat input as expressions; prefix commands with backtick
 * - [Split]: use console for evaluation, open separate terminal for commands
 */
enum class ConsoleMode {
    Commands,
    Evaluate,
    Split;

    companion object {
        fun fromJson(value: String?): ConsoleMode? = when (value) {
            "commands" -> Commands
            "evaluate" -> Evaluate
            "split" -> Split
            else -> null
        }
    }
}

/** Display format for variables and expressions. */
enum class DisplayFormat {
    Auto, Hex, Decimal, Binary;

    companion object {
        fun fromJson(value: String?): DisplayFormat? = when (value) {
            "auto" -> Auto
            "hex" -> Hex
            "decimal" -> Decimal
            "binary" -> Binary
            else -> null
        }
    }
}

/** When to show disassembly. */
enum class ShowDisassembly {
    /** Always show disassembly, even if source is available. */
    Always,
    /** Never show disassembly. */
    Never,
    /** Only show disassembly when source is not available. */
    Auto;

    companion object {
        fun fromJson(value: String?): ShowDisassembly? = when (value) {
            "always" -> Always
            "never" -> Never
            "auto" -> Auto
            else -> null
        }
    }
}

/**
 * Terminal device identifier.
 *
 * On Unix, this is a TTY device path (string). On Windows, it is a
 * process ID (number). Matches Rust's untagged `TerminalId` enum.
 */
sealed class TerminalId {
    /** TTY device name (Unix). */
    data class TTY(val name: String) : TerminalId()
    /** Process ID (Windows). */
    data class PID(val pid: Long) : TerminalId()

    companion object {
        fun fromJsonValue(value: Any?): TerminalId? = when (value) {
            is String -> TTY(value)
            is Number -> PID(value.toLong())
            else -> null
        }
    }
}

/**
 * Terminal type to use for the debuggee.
 *
 * Matches CodeLLDB's `TerminalKind` enum with `#[serde(rename_all = "camelCase")]`
 * and an untagged `TerminalId` variant.
 */
sealed class TerminalKind {
    /** Use integrated terminal in VS Code. */
    data object Integrated : TerminalKind()
    /** Use external terminal window. */
    data object External : TerminalKind()
    /** Use VS Code Debug Console for stdout and stderr. Stdin will be unavailable. */
    data object Console : TerminalKind()
    /** Use a specific TTY device or process ID. */
    data class WithTerminalId(val id: TerminalId) : TerminalKind()

    companion object {
        fun fromJsonValue(value: Any?): TerminalKind? = when (value) {
            is String -> when (value) {
                "integrated" -> Integrated
                "external" -> External
                "console" -> Console
                else -> {
                    // A string that isn't a known enum value is treated as a TTY path
                    WithTerminalId(TerminalId.TTY(value))
                }
            }
            is Number -> WithTerminalId(TerminalId.PID(value.toLong()))
            else -> null
        }
    }

    /** Serializes back to the JSON representation. */
    fun toJsonValue(): Any = when (this) {
        is Integrated -> "integrated"
        is External -> "external"
        is Console -> "console"
        is WithTerminalId -> when (val id = this.id) {
            is TerminalId.TTY -> id.name
            is TerminalId.PID -> id.pid
        }
    }
}

/**
 * Terminal type to use (compatibility alias of [TerminalKind]).
 *
 * Matches CodeLLDB's `ConsoleKind` enum.
 */
enum class ConsoleKind {
    /** Use integrated terminal in VS Code. */
    IntegratedTerminal,
    /** Use external terminal window. */
    ExternalTerminal,
    /** Use VS Code Debug Console for stdout and stderr. Stdin will be unavailable. */
    InternalConsole;

    companion object {
        fun fromJson(value: String?): ConsoleKind? = when (value) {
            "integratedTerminal" -> IntegratedTerminal
            "externalTerminal" -> ExternalTerminal
            "internalConsole" -> InternalConsole
            else -> null
        }
    }

    /** Serializes back to the camelCase JSON representation. */
    fun toJsonValue(): String = when (this) {
        IntegratedTerminal -> "integratedTerminal"
        ExternalTerminal -> "externalTerminal"
        InternalConsole -> "internalConsole"
    }
}

/** The default evaluator type used for expressions. */
enum class Expressions {
    Simple, Python, Native;

    companion object {
        fun fromJson(value: String?): Expressions? = when (value) {
            "simple" -> Simple
            "python" -> Python
            "native" -> Native
            else -> null
        }
    }
}

/**
 * Internal adapter settings passed via `_adapterSettings` in the launch
 * configuration.
 *
 * Matches CodeLLDB's `AdapterSettings` struct.
 */
data class AdapterSettings(
    val displayFormat: DisplayFormat? = null,
    val showDisassembly: ShowDisassembly? = null,
    val dereferencePointers: Boolean? = null,
    val containerSummary: Boolean? = null,
    val evaluationTimeout: Float? = null,
    val summaryTimeout: Float? = null,
    val suppressMissingSourceFiles: Boolean? = null,
    val consoleMode: ConsoleMode? = null,
    val sourceLanguages: List<String>? = null,
    val scriptConfig: Map<String, Any?>? = null,
    val evaluateForHovers: Boolean? = null,
    val commandCompletions: Boolean? = null,
) {
    companion object {
        fun fromJson(obj: JSONObject?): AdapterSettings? {
            if (obj == null) return null
            return AdapterSettings(
                displayFormat = DisplayFormat.fromJson(obj.optString("displayFormat", null)),
                showDisassembly = ShowDisassembly.fromJson(obj.optString("showDisassembly", null)),
                dereferencePointers = obj.optNullableBoolean("dereferencePointers"),
                containerSummary = obj.optNullableBoolean("containerSummary"),
                evaluationTimeout = obj.optNullableFloat("evaluationTimeout"),
                summaryTimeout = obj.optNullableFloat("summaryTimeout"),
                suppressMissingSourceFiles = obj.optNullableBoolean("suppressMissingSourceFiles"),
                consoleMode = ConsoleMode.fromJson(obj.optString("consoleMode", null)),
                sourceLanguages = obj.optStringList("sourceLanguages"),
                scriptConfig = obj.optJSONObject("scriptConfig")?.toStringAnyMap(),
                evaluateForHovers = obj.optNullableBoolean("evaluateForHovers"),
                commandCompletions = obj.optNullableBoolean("commandCompletions"),
            )
        }
    }
}

/**
 * Fields shared by both [LaunchRequestArguments] and [AttachRequestArguments].
 *
 * Matches CodeLLDB's `CommonLaunchFields` struct (flattened via `#[serde(flatten)]`).
 */
data class CommonLaunchFields(
    val name: String? = null,
    /** Source path remapping. Each entry maps a remote prefix to a local prefix. */
    val sourceMap: Map<String, String?>? = null,
    /** The default evaluator type used for expressions. */
    val expressions: Expressions? = null,
    /** Initialization commands executed upon debugger startup. */
    val initCommands: List<String>? = null,
    /** Commands executed just before the debuggee is launched or attached to. */
    val preRunCommands: List<String>? = null,
    /** Commands executed just after the debuggee has been launched or attached to. */
    val postRunCommands: List<String>? = null,
    /**
     * Gracefully shut down the debuggee:
     * - If a string, it is a signal name to send.
     * - If a list of strings, they are LLDB commands to execute.
     */
    val gracefulShutdown: Either<String, List<String>>? = null,
    /** Commands executed just before the debuggee is terminated or disconnected from. */
    val preTerminateCommands: List<String>? = null,
    /** Commands executed at the end of debugging session, after the debuggee has been terminated. */
    val exitCommands: List<String>? = null,
    /** Source languages to enable language-specific features for. */
    val sourceLanguages: List<String>? = null,
    /** Enable reverse debugging. */
    val reverseDebugging: Boolean? = null,
    /** Base directory used for resolution of relative source paths. */
    val relativePathBase: String? = null,
    /** Specifies how source breakpoints should be set. */
    val breakpointMode: BreakpointMode? = null,
    /** Internal adapter settings. */
    val adapterSettings: AdapterSettings? = null,
) {
    companion object {
        fun fromJson(obj: JSONObject): CommonLaunchFields {
            return CommonLaunchFields(
                name = obj.optString("name", null),
                sourceMap = obj.optJSONObject("sourceMap")?.toStringNullableStringMap(),
                expressions = Expressions.fromJson(obj.optString("expressions", null)),
                initCommands = obj.optStringList("initCommands"),
                preRunCommands = obj.optStringList("preRunCommands"),
                postRunCommands = obj.optStringList("postRunCommands"),
                gracefulShutdown = parseGracefulShutdown(obj),
                preTerminateCommands = obj.optStringList("preTerminateCommands"),
                exitCommands = obj.optStringList("exitCommands"),
                sourceLanguages = obj.optStringList("sourceLanguages"),
                reverseDebugging = obj.optNullableBoolean("reverseDebugging"),
                relativePathBase = obj.optString("relativePathBase", null),
                breakpointMode = BreakpointMode.fromJson(obj.optString("breakpointMode", null)),
                adapterSettings = AdapterSettings.fromJson(obj.optJSONObject("_adapterSettings")),
            )
        }

        private fun parseGracefulShutdown(obj: JSONObject): Either<String, List<String>>? {
            if (!obj.has("gracefulShutdown")) return null
            val value = obj.get("gracefulShutdown")
            return when (value) {
                is String -> Either.First(value)
                is JSONArray -> {
                    val list = (0 until value.length()).map { value.getString(it) }
                    Either.Second(list)
                }
                else -> null
            }
        }
    }
}

/**
 * Arguments for the DAP `launch` request.
 *
 * Matches CodeLLDB's `LaunchRequestArguments` struct. The [common] fields
 * are flattened (mixed in at the same level in the JSON).
 */
data class LaunchRequestArguments(
    val common: CommonLaunchFields = CommonLaunchFields(),
    val noDebug: Boolean? = null,
    /** Path to the program to debug. */
    val program: String? = null,
    /** Program arguments. */
    val args: List<String>? = null,
    /** Program working directory. */
    val cwd: String? = null,
    /** Additional environment variables. */
    val env: Map<String, String>? = null,
    /** File to read the environment variables from. */
    val envFile: String? = null,
    /**
     * Destination for stdio streams:
     * - `null` = send to the debugger console or the terminal
     * - A single string = path for all three streams
     * - A list = individual paths for stdin, stdout, stderr
     */
    val stdio: Either<String, List<String?>>? = null,
    /** Automatically stop debuggee after launch. */
    val stopOnEntry: Boolean? = null,
    /** Terminal type to use. */
    val terminal: TerminalKind? = null,
    /** Terminal type to use (compatibility alias of [terminal]). */
    val console: ConsoleKind? = null,
    /** Commands that create the debug target. */
    val targetCreateCommands: List<String>? = null,
    /** Commands that create the debuggee process. */
    val processCreateCommands: List<String>? = null,
) {
    companion object {
        /**
         * Parses a [LaunchRequestArguments] from the `arguments` object of
         * a DAP launch request.
         */
        fun fromJson(obj: JSONObject): LaunchRequestArguments {
            return LaunchRequestArguments(
                common = CommonLaunchFields.fromJson(obj),
                noDebug = obj.optNullableBoolean("noDebug"),
                program = obj.optString("program", null),
                args = obj.optStringList("args"),
                cwd = obj.optString("cwd", null),
                env = obj.optJSONObject("env")?.toStringStringMap(),
                envFile = obj.optString("envFile", null),
                stdio = parseStdio(obj),
                stopOnEntry = obj.optNullableBoolean("stopOnEntry"),
                terminal = parseTerminal(obj),
                console = ConsoleKind.fromJson(obj.optString("console", null)),
                targetCreateCommands = obj.optStringList("targetCreateCommands"),
                processCreateCommands = obj.optStringList("processCreateCommands"),
            )
        }

        private fun parseStdio(obj: JSONObject): Either<String, List<String?>>? {
            if (!obj.has("stdio")) return null
            val value = obj.get("stdio")
            return when (value) {
                is String -> Either.First(value)
                is JSONArray -> {
                    val list = (0 until value.length()).map {
                        if (value.isNull(it)) null else value.getString(it)
                    }
                    Either.Second(list)
                }
                else -> null
            }
        }

        private fun parseTerminal(obj: JSONObject): TerminalKind? {
            if (!obj.has("terminal")) return null
            return TerminalKind.fromJsonValue(obj.get("terminal"))
        }
    }

    /**
     * Serializes this [LaunchRequestArguments] to a [JSONObject], flattening
     * [common] fields at the top level (matching CodeLLDB's `#[serde(flatten)]`).
     */
    fun toJsonObject(): JSONObject = JSONObject().apply {
        // CommonLaunchFields (flattened)
        common.name?.let { put("name", it) }
        common.sourceMap?.let { map ->
            put("sourceMap", JSONObject().apply {
                for ((k, v) in map) put(k, v ?: JSONObject.NULL)
            })
        }
        common.expressions?.let { put("expressions", it.name.lowercase()) }
        common.initCommands?.let { put("initCommands", JSONArray(it)) }
        common.preRunCommands?.let { put("preRunCommands", JSONArray(it)) }
        common.postRunCommands?.let { put("postRunCommands", JSONArray(it)) }
        common.gracefulShutdown?.let { gs ->
            when (gs) {
                is Either.First<*> -> put("gracefulShutdown", gs.value)
                is Either.Second<*> -> put("gracefulShutdown", JSONArray(gs.value))
            }
        }
        common.preTerminateCommands?.let { put("preTerminateCommands", JSONArray(it)) }
        common.exitCommands?.let { put("exitCommands", JSONArray(it)) }
        common.sourceLanguages?.let { put("sourceLanguages", JSONArray(it)) }
        common.reverseDebugging?.let { put("reverseDebugging", it) }
        common.relativePathBase?.let { put("relativePathBase", it) }
        common.breakpointMode?.let { put("breakpointMode", it.name.lowercase()) }
        // _adapterSettings intentionally omitted from serialization (internal)

        // LaunchRequestArguments fields
        noDebug?.let { put("noDebug", it) }
        program?.let { put("program", it) }
        args?.let { put("args", JSONArray(it)) }
        cwd?.let { put("cwd", it) }
        env?.let { map ->
            put("env", JSONObject().apply {
                for ((k, v) in map) put(k, v)
            })
        }
        envFile?.let { put("envFile", it) }
        stdio?.let { s ->
            when (s) {
                is Either.First<*> -> put("stdio", s.value)
                is Either.Second<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val paths = s.value as List<String?>
                    put("stdio", JSONArray(paths.map { it ?: JSONObject.NULL }))
                }
            }
        }
        stopOnEntry?.let { put("stopOnEntry", it) }
        terminal?.let { put("terminal", it.toJsonValue()) }
        console?.let { put("console", it.toJsonValue()) }
        targetCreateCommands?.let { put("targetCreateCommands", JSONArray(it)) }
        processCreateCommands?.let { put("processCreateCommands", JSONArray(it)) }
    }
}

/**
 * Arguments for the DAP `attach` request.
 *
 * Matches CodeLLDB's `AttachRequestArguments` struct.
 */
data class AttachRequestArguments(
    val common: CommonLaunchFields = CommonLaunchFields(),
    /** Path to the program to attach to. */
    val program: String? = null,
    /** Process id to attach to (number or string). */
    val pid: Either<Long, String>? = null,
    /** Wait for the process to launch (macOS only). */
    val waitFor: Boolean? = null,
    /** Automatically stop debuggee after attach. */
    val stopOnEntry: Boolean? = null,
    /** Commands that create the debug target. */
    val targetCreateCommands: List<String>? = null,
    /** Commands that create the debuggee process. */
    val processCreateCommands: List<String>? = null,
) {
    companion object {
        /**
         * Parses an [AttachRequestArguments] from the `arguments` object of
         * a DAP attach request.
         */
        fun fromJson(obj: JSONObject): AttachRequestArguments {
            return AttachRequestArguments(
                common = CommonLaunchFields.fromJson(obj),
                program = obj.optString("program", null),
                pid = parsePid(obj),
                waitFor = obj.optNullableBoolean("waitFor"),
                stopOnEntry = obj.optNullableBoolean("stopOnEntry"),
                targetCreateCommands = obj.optStringList("targetCreateCommands"),
                processCreateCommands = obj.optStringList("processCreateCommands"),
            )
        }

        private fun parsePid(obj: JSONObject): Either<Long, String>? {
            if (!obj.has("pid")) return null
            val value = obj.get("pid")
            return when (value) {
                is Number -> Either.First(value.toLong())
                is String -> Either.Second(value)
                else -> null
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// JSON parsing helpers
// ══════════════════════════════════════════════════════════════════════

/** Returns `null` if the key is absent, otherwise the boolean value. */
internal fun JSONObject.optNullableBoolean(key: String): Boolean? =
    if (has(key)) optBoolean(key) else null

/** Returns `null` if the key is absent, otherwise the float value. */
internal fun JSONObject.optNullableFloat(key: String): Float? =
    if (has(key)) optDouble(key).toFloat() else null

/** Parses a JSON array of strings, returning `null` if the key is absent. */
internal fun JSONObject.optStringList(key: String): List<String>? {
    val arr = optJSONArray(key) ?: return null
    return (0 until arr.length()).map { arr.getString(it) }
}

/** Converts a JSONObject to a `Map<String, String>`. */
internal fun JSONObject.toStringStringMap(): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for (key in keySet()) {
        map[key] = optString(key, "")
    }
    return map
}

/** Converts a JSONObject to a `Map<String, String?>` (values may be null). */
internal fun JSONObject.toStringNullableStringMap(): Map<String, String?> {
    val map = mutableMapOf<String, String?>()
    for (key in keySet()) {
        map[key] = if (isNull(key)) null else optString(key, "")
    }
    return map
}

/** Converts a JSONObject to a `Map<String, Any?>` for generic nested objects. */
internal fun JSONObject.toStringAnyMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    for (key in keySet()) {
        map[key] = when {
            isNull(key) -> null
            else -> get(key)
        }
    }
    return map
}

/**
 * Strongly-typed DAP message hierarchy.
 *
 * Every standard DAP command and event is represented by a concrete data class,
 * following the pattern established by CodeLLDB's `adapter-protocol`. Commands
 * and events that KDAP actively inspects or modifies have rich fields; commands
 * KDAP just forwards have minimal fields (just [seq]).
 *
 * A narrow [UnknownRequest] / [UnknownEvent] catch-all exists only for
 * genuinely unrecognized messages (like CodeLLDB's `#[serde(other)] unknown`).
 *
 * ## Parsing and serialization
 *
 * [parse] converts a raw JSON string into the appropriate concrete type using
 * `org.json` internally. [toJson] serializes back to a JSON string. In practice,
 * `toJson()` is only called for interceptor-created or modified messages;
 * forwarded messages use raw JSON passthrough in [com.github.jomof.dap.DapSession].
 */
sealed class DapMessage {
    abstract val seq: Int

    /** Serializes this message to a DAP JSON string. */
    abstract fun toJson(): String

    companion object {
        /**
         * Parses a raw DAP JSON string into the appropriate typed message.
         *
         * @throws org.json.JSONException if the JSON is malformed
         * @throws IllegalArgumentException if the `type` field is missing or unrecognized
         */
        fun parse(json: String): DapMessage {
            val obj = JSONObject(json)
            val seq = obj.optInt("seq", 0)
            return when (val type = obj.optString("type", "")) {
                "request" -> parseRequest(obj, seq)
                "response" -> parseResponse(obj, seq)
                "event" -> parseEvent(obj, seq)
                else -> throw IllegalArgumentException("Unknown DAP message type: '$type'")
            }
        }

        private fun parseRequest(obj: JSONObject, seq: Int): DapRequest {
            val command = obj.optString("command", "")
            val args = obj.optJSONObject("arguments")
            return when (command) {
                // Rich fields — KDAP inspects/modifies these
                "launch" -> LaunchRequest(
                    seq = seq,
                    arguments = if (args != null) LaunchRequestArguments.fromJson(args) else LaunchRequestArguments(),
                )
                "evaluate" -> EvaluateRequest(
                    seq = seq,
                    expression = args?.optString("expression", "") ?: "",
                    context = args?.optString("context", null),
                    frameId = if (args?.has("frameId") == true) args.optInt("frameId") else null,
                )
                // Standard DAP commands (forwarded, minimal fields)
                "initialize" -> InitializeRequest(
                    seq = seq,
                    supportsRunInTerminalRequest = args?.optBoolean("supportsRunInTerminalRequest", false) ?: false,
                )
                "attach" -> AttachRequest(seq)
                "restart" -> RestartRequest(seq)
                "disconnect" -> DisconnectRequest(seq)
                "terminate" -> TerminateRequest(seq)
                "setBreakpoints" -> SetBreakpointsRequest(seq)
                "setFunctionBreakpoints" -> SetFunctionBreakpointsRequest(seq)
                "setExceptionBreakpoints" -> SetExceptionBreakpointsRequest(seq)
                "setDataBreakpoints" -> SetDataBreakpointsRequest(seq)
                "setInstructionBreakpoints" -> SetInstructionBreakpointsRequest(seq)
                "configurationDone" -> ConfigurationDoneRequest(seq)
                "continue" -> ContinueRequest(seq)
                "next" -> NextRequest(seq)
                "stepIn" -> StepInRequest(seq)
                "stepOut" -> StepOutRequest(seq)
                "stepBack" -> StepBackRequest(seq)
                "reverseContinue" -> ReverseContinueRequest(seq)
                "pause" -> PauseRequest(seq)
                "restartFrame" -> RestartFrameRequest(seq)
                "threads" -> ThreadsRequest(seq)
                "stackTrace" -> StackTraceRequest(seq)
                "scopes" -> ScopesRequest(seq)
                "variables" -> VariablesRequest(seq)
                "setVariable" -> SetVariableRequest(seq)
                "source" -> SourceRequest(seq)
                "modules" -> ModulesRequest(seq)
                "completions" -> CompletionsRequest(seq)
                "exceptionInfo" -> ExceptionInfoRequest(seq)
                "readMemory" -> ReadMemoryRequest(seq)
                "writeMemory" -> WriteMemoryRequest(seq)
                "disassemble" -> DisassembleRequest(seq)
                "dataBreakpointInfo" -> DataBreakpointInfoRequest(seq)
                "gotoTargets" -> GotoTargetsRequest(seq)
                "goto" -> GotoRequest(seq)
                "stepInTargets" -> StepInTargetsRequest(seq)
                "cancel" -> CancelRequest(seq)
                "breakpointLocations" -> BreakpointLocationsRequest(seq)
                "loadedSources" -> LoadedSourcesRequest(seq)
                "terminateThreads" -> TerminateThreadsRequest(seq)
                "setExpression" -> SetExpressionRequest(seq)
                // Reverse requests (adapter -> client)
                "runInTerminal" -> {
                    val arguments = obj.optJSONObject("arguments")
                    val args = arguments?.optJSONArray("args")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList()
                    RunInTerminalRequest(
                        seq = seq,
                        args = args,
                        kind = arguments?.optString("kind")?.ifEmpty { null },
                        title = arguments?.optString("title")?.ifEmpty { null },
                    )
                }
                "startDebugging" -> StartDebuggingRequest(seq)
                // Narrow catch-all
                else -> UnknownRequest(seq, command)
            }
        }

        private fun parseEvent(obj: JSONObject, seq: Int): DapEvent {
            val event = obj.optString("event", "")
            val body = obj.optJSONObject("body")
            return when (event) {
                // Rich fields — KDAP inspects/creates these
                "initialized" -> InitializedEvent(seq)
                "process" -> ProcessEvent(
                    seq = seq,
                    name = body?.optString("name", "") ?: "",
                    systemProcessId = if (body?.has("systemProcessId") == true) body.optInt("systemProcessId") else null,
                    isLocalProcess = if (body?.has("isLocalProcess") == true) body.optBoolean("isLocalProcess") else null,
                    startMethod = body?.optString("startMethod", null),
                )
                "output" -> OutputEvent(
                    seq = seq,
                    category = body?.optString("category", null),
                    output = body?.optString("output", "") ?: "",
                )
                // Standard DAP events (forwarded, minimal fields)
                "stopped" -> StoppedEvent(seq)
                "continued" -> ContinuedEvent(
                    seq = seq,
                    threadId = body?.optInt("threadId", 0) ?: 0,
                    allThreadsContinued = if (body?.has("allThreadsContinued") == true) body.optBoolean("allThreadsContinued") else null,
                )
                "exited" -> ExitedEvent(
                    seq = seq,
                    exitCode = body?.optInt("exitCode", 0) ?: 0,
                )
                "terminated" -> TerminatedEvent(
                    seq = seq,
                    restart = if (body?.has("restart") == true) jsonValueToKotlin(body.get("restart")) else null,
                )
                "thread" -> ThreadEvent(seq)
                "breakpoint" -> BreakpointEvent(seq)
                "module" -> ModuleEvent(seq)
                "loadedSource" -> LoadedSourceEvent(seq)
                "capabilities" -> CapabilitiesEvent(
                    seq = seq,
                    capabilities = if (body?.has("capabilities") == true)
                        jsonObjectToMap(body.getJSONObject("capabilities"))
                    else emptyMap(),
                )
                "invalidated" -> InvalidatedEvent(seq)
                "memory" -> MemoryEvent(seq)
                "progressStart" -> ProgressStartEvent(seq)
                "progressUpdate" -> ProgressUpdateEvent(seq)
                "progressEnd" -> ProgressEndEvent(seq)
                // Narrow catch-all
                else -> UnknownEvent(seq, event)
            }
        }

        private fun parseResponse(obj: JSONObject, seq: Int): DapResponse {
            val body = obj.optJSONObject("body")
            return DapResponse(
                seq = seq,
                requestSeq = obj.optInt("request_seq", 0),
                command = obj.optString("command", ""),
                success = obj.optBoolean("success", false),
                message = obj.optString("message", null),
                body = if (body != null) jsonObjectToMap(body) else emptyMap(),
            )
        }

        // ── JSON <-> Map conversion helpers ─────────────────────────────

        internal fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
            val map = mutableMapOf<String, Any?>()
            for (key in obj.keySet()) {
                map[key] = jsonValueToKotlin(obj.get(key))
            }
            return map
        }

        internal fun jsonValueToKotlin(value: Any?): Any? = when (value) {
            JSONObject.NULL, null -> null
            is JSONObject -> jsonObjectToMap(value)
            is org.json.JSONArray -> (0 until value.length()).map { jsonValueToKotlin(value.get(it)) }
            else -> value // String, Int, Long, Double, Boolean
        }

        internal fun mapToJsonObject(map: Map<String, Any?>): JSONObject {
            val obj = JSONObject()
            for ((key, value) in map) {
                obj.put(key, kotlinValueToJson(value))
            }
            return obj
        }

        @Suppress("UNCHECKED_CAST")
        internal fun kotlinValueToJson(value: Any?): Any = when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> mapToJsonObject(value as Map<String, Any?>)
            is List<*> -> org.json.JSONArray(value.map { kotlinValueToJson(it) })
            else -> value // String, Int, Long, Double, Boolean
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Requests
// ══════════════════════════════════════════════════════════════════════

/**
 * Base class for all DAP request messages. Each concrete subclass corresponds
 * to a specific DAP command.
 */
sealed class DapRequest : DapMessage() {
    abstract val command: String

    /** Builds the basic request JSON envelope. Subclasses add arguments. */
    protected fun buildRequestJson(arguments: JSONObject? = null): String {
        val obj = JSONObject()
        obj.put("type", "request")
        obj.put("seq", seq)
        obj.put("command", command)
        if (arguments != null) {
            obj.put("arguments", arguments)
        }
        return obj.toString()
    }
}

// ── Commands KDAP inspects/modifies (rich fields) ────────────────────

/**
 * DAP `launch` request. KDAP reads [program] for reverse event injection
 * and [terminal] for `runInTerminal` support.
 */
data class LaunchRequest(
    override val seq: Int,
    val arguments: LaunchRequestArguments = LaunchRequestArguments(),
) : DapRequest() {
    override val command get() = "launch"

    /** Convenience accessor for the most commonly used field. */
    val program: String? get() = arguments.program

    override fun toJson(): String = buildRequestJson(arguments.toJsonObject())
}

/**
 * DAP `evaluate` request. KDAP rewrites [context] from `"_command"` to
 * `"repl"` for CodeLLDB compatibility.
 */
data class EvaluateRequest(
    override val seq: Int,
    val expression: String,
    val context: String?,
    val frameId: Int? = null,
) : DapRequest() {
    override val command get() = "evaluate"
    override fun toJson(): String = buildRequestJson(JSONObject().apply {
        put("expression", expression)
        if (context != null) put("context", context)
        if (frameId != null) put("frameId", frameId)
    })
}

// ── Standard DAP commands (forwarded, minimal fields) ────────────────

data class InitializeRequest(
    override val seq: Int,
    /** Whether the client supports the `runInTerminal` reverse request. */
    val supportsRunInTerminalRequest: Boolean = false,
) : DapRequest() {
    override val command get() = "initialize"
    override fun toJson(): String = buildRequestJson()
}

data class AttachRequest(override val seq: Int) : DapRequest() {
    override val command get() = "attach"
    override fun toJson(): String = buildRequestJson()
}

data class RestartRequest(override val seq: Int) : DapRequest() {
    override val command get() = "restart"
    override fun toJson(): String = buildRequestJson()
}

data class DisconnectRequest(override val seq: Int) : DapRequest() {
    override val command get() = "disconnect"
    override fun toJson(): String = buildRequestJson()
}

data class TerminateRequest(override val seq: Int) : DapRequest() {
    override val command get() = "terminate"
    override fun toJson(): String = buildRequestJson()
}

data class SetBreakpointsRequest(override val seq: Int) : DapRequest() {
    override val command get() = "setBreakpoints"
    override fun toJson(): String = buildRequestJson()
}

data class SetFunctionBreakpointsRequest(override val seq: Int) : DapRequest() {
    override val command get() = "setFunctionBreakpoints"
    override fun toJson(): String = buildRequestJson()
}

data class SetExceptionBreakpointsRequest(override val seq: Int) : DapRequest() {
    override val command get() = "setExceptionBreakpoints"
    override fun toJson(): String = buildRequestJson()
}

data class SetDataBreakpointsRequest(override val seq: Int) : DapRequest() {
    override val command get() = "setDataBreakpoints"
    override fun toJson(): String = buildRequestJson()
}

data class SetInstructionBreakpointsRequest(override val seq: Int) : DapRequest() {
    override val command get() = "setInstructionBreakpoints"
    override fun toJson(): String = buildRequestJson()
}

data class ConfigurationDoneRequest(override val seq: Int) : DapRequest() {
    override val command get() = "configurationDone"
    override fun toJson(): String = buildRequestJson()
}

data class ContinueRequest(override val seq: Int) : DapRequest() {
    override val command get() = "continue"
    override fun toJson(): String = buildRequestJson()
}

data class NextRequest(override val seq: Int) : DapRequest() {
    override val command get() = "next"
    override fun toJson(): String = buildRequestJson()
}

data class StepInRequest(override val seq: Int) : DapRequest() {
    override val command get() = "stepIn"
    override fun toJson(): String = buildRequestJson()
}

data class StepOutRequest(override val seq: Int) : DapRequest() {
    override val command get() = "stepOut"
    override fun toJson(): String = buildRequestJson()
}

data class StepBackRequest(override val seq: Int) : DapRequest() {
    override val command get() = "stepBack"
    override fun toJson(): String = buildRequestJson()
}

data class ReverseContinueRequest(override val seq: Int) : DapRequest() {
    override val command get() = "reverseContinue"
    override fun toJson(): String = buildRequestJson()
}

data class PauseRequest(override val seq: Int) : DapRequest() {
    override val command get() = "pause"
    override fun toJson(): String = buildRequestJson()
}

data class RestartFrameRequest(override val seq: Int) : DapRequest() {
    override val command get() = "restartFrame"
    override fun toJson(): String = buildRequestJson()
}

data class ThreadsRequest(override val seq: Int) : DapRequest() {
    override val command get() = "threads"
    override fun toJson(): String = buildRequestJson()
}

data class StackTraceRequest(override val seq: Int) : DapRequest() {
    override val command get() = "stackTrace"
    override fun toJson(): String = buildRequestJson()
}

data class ScopesRequest(override val seq: Int) : DapRequest() {
    override val command get() = "scopes"
    override fun toJson(): String = buildRequestJson()
}

data class VariablesRequest(override val seq: Int) : DapRequest() {
    override val command get() = "variables"
    override fun toJson(): String = buildRequestJson()
}

data class SetVariableRequest(override val seq: Int) : DapRequest() {
    override val command get() = "setVariable"
    override fun toJson(): String = buildRequestJson()
}

data class SourceRequest(override val seq: Int) : DapRequest() {
    override val command get() = "source"
    override fun toJson(): String = buildRequestJson()
}

data class ModulesRequest(override val seq: Int) : DapRequest() {
    override val command get() = "modules"
    override fun toJson(): String = buildRequestJson()
}

data class CompletionsRequest(override val seq: Int) : DapRequest() {
    override val command get() = "completions"
    override fun toJson(): String = buildRequestJson()
}

data class ExceptionInfoRequest(override val seq: Int) : DapRequest() {
    override val command get() = "exceptionInfo"
    override fun toJson(): String = buildRequestJson()
}

data class ReadMemoryRequest(override val seq: Int) : DapRequest() {
    override val command get() = "readMemory"
    override fun toJson(): String = buildRequestJson()
}

data class WriteMemoryRequest(override val seq: Int) : DapRequest() {
    override val command get() = "writeMemory"
    override fun toJson(): String = buildRequestJson()
}

data class DisassembleRequest(override val seq: Int) : DapRequest() {
    override val command get() = "disassemble"
    override fun toJson(): String = buildRequestJson()
}

data class DataBreakpointInfoRequest(override val seq: Int) : DapRequest() {
    override val command get() = "dataBreakpointInfo"
    override fun toJson(): String = buildRequestJson()
}

data class GotoTargetsRequest(override val seq: Int) : DapRequest() {
    override val command get() = "gotoTargets"
    override fun toJson(): String = buildRequestJson()
}

data class GotoRequest(override val seq: Int) : DapRequest() {
    override val command get() = "goto"
    override fun toJson(): String = buildRequestJson()
}

data class StepInTargetsRequest(override val seq: Int) : DapRequest() {
    override val command get() = "stepInTargets"
    override fun toJson(): String = buildRequestJson()
}

data class CancelRequest(override val seq: Int) : DapRequest() {
    override val command get() = "cancel"
    override fun toJson(): String = buildRequestJson()
}

data class BreakpointLocationsRequest(override val seq: Int) : DapRequest() {
    override val command get() = "breakpointLocations"
    override fun toJson(): String = buildRequestJson()
}

data class LoadedSourcesRequest(override val seq: Int) : DapRequest() {
    override val command get() = "loadedSources"
    override fun toJson(): String = buildRequestJson()
}

data class TerminateThreadsRequest(override val seq: Int) : DapRequest() {
    override val command get() = "terminateThreads"
    override fun toJson(): String = buildRequestJson()
}

data class SetExpressionRequest(override val seq: Int) : DapRequest() {
    override val command get() = "setExpression"
    override fun toJson(): String = buildRequestJson()
}

// Reverse requests (adapter -> client)

data class RunInTerminalRequest(
    override val seq: Int,
    /** The command line to execute (first element is the program). */
    val args: List<String> = emptyList(),
    /** Terminal kind requested: `"integrated"` or `"external"`. */
    val kind: String? = null,
    /** Optional title for the terminal. */
    val title: String? = null,
) : DapRequest() {
    override val command get() = "runInTerminal"
    override fun toJson(): String {
        val arguments = JSONObject().apply {
            if (args.isNotEmpty()) put("args", JSONArray(args))
            if (kind != null) put("kind", kind)
            if (title != null) put("title", title)
        }
        return buildRequestJson(arguments)
    }
}

data class StartDebuggingRequest(override val seq: Int) : DapRequest() {
    override val command get() = "startDebugging"
    override fun toJson(): String = buildRequestJson()
}

// ── Narrow catch-all (like CodeLLDB's #[serde(other)] unknown) ───────

/**
 * Catch-all for DAP commands not explicitly modeled. This should only match
 * genuinely unrecognized commands; all standard DAP commands have their own type.
 */
data class UnknownRequest(
    override val seq: Int,
    override val command: String,
) : DapRequest() {
    override fun toJson(): String = buildRequestJson()
}

// ══════════════════════════════════════════════════════════════════════
// Events
// ══════════════════════════════════════════════════════════════════════

/**
 * Base class for all DAP event messages. Each concrete subclass corresponds
 * to a specific DAP event.
 */
sealed class DapEvent : DapMessage() {
    abstract val event: String

    /** Builds the basic event JSON envelope. Subclasses add body fields. */
    protected fun buildEventJson(body: JSONObject? = null): String {
        val obj = JSONObject()
        obj.put("type", "event")
        obj.put("seq", seq)
        obj.put("event", event)
        if (body != null) {
            obj.put("body", body)
        }
        return obj.toString()
    }
}

// ── Events KDAP inspects/creates (rich fields) ──────────────────────

/** DAP `initialized` event. KDAP injects a console mode message before this. */
data class InitializedEvent(override val seq: Int) : DapEvent() {
    override val event get() = "initialized"
    override fun toJson(): String = buildEventJson()
}

/**
 * DAP `process` event. KDAP reads [systemProcessId] to inject
 * "Launched process {pid}" output events.
 */
data class ProcessEvent(
    override val seq: Int,
    val name: String,
    val systemProcessId: Int? = null,
    val isLocalProcess: Boolean? = null,
    val startMethod: String? = null,
) : DapEvent() {
    override val event get() = "process"
    override fun toJson(): String = buildEventJson(JSONObject().apply {
        put("name", name)
        if (systemProcessId != null) put("systemProcessId", systemProcessId)
        if (isLocalProcess != null) put("isLocalProcess", isLocalProcess)
        if (startMethod != null) put("startMethod", startMethod)
    })
}

/**
 * DAP `output` event. KDAP creates these for CodeLLDB-compatible console
 * messages during the launch sequence.
 */
data class OutputEvent(
    override val seq: Int,
    val category: String? = null,
    val output: String,
) : DapEvent() {
    override val event get() = "output"
    override fun toJson(): String = buildEventJson(JSONObject().apply {
        if (category != null) put("category", category)
        put("output", output)
    })

    companion object {
        /** Factory for console output events (injected reverse events). */
        fun console(text: String) = OutputEvent(seq = 0, category = "console", output = text)
    }
}

// ── Standard DAP events (forwarded, minimal fields) ──────────────────

data class StoppedEvent(override val seq: Int) : DapEvent() {
    override val event get() = "stopped"
    override fun toJson(): String = buildEventJson()
}

data class ContinuedEvent(
    override val seq: Int,
    val threadId: Int = 0,
    val allThreadsContinued: Boolean? = null,
) : DapEvent() {
    override val event get() = "continued"
    override fun toJson(): String = buildEventJson(JSONObject().apply {
        put("threadId", threadId)
        if (allThreadsContinued != null) put("allThreadsContinued", allThreadsContinued)
    })
}

data class ExitedEvent(
    override val seq: Int,
    val exitCode: Int = 0,
) : DapEvent() {
    override val event get() = "exited"
    override fun toJson(): String = buildEventJson(JSONObject().apply {
        put("exitCode", exitCode)
    })
}

data class TerminatedEvent(
    override val seq: Int,
    val restart: Any? = null,
) : DapEvent() {
    override val event get() = "terminated"
    override fun toJson(): String = if (restart != null) {
        buildEventJson(JSONObject().apply {
            put("restart", DapMessage.kotlinValueToJson(restart))
        })
    } else {
        buildEventJson()
    }
}

data class ThreadEvent(override val seq: Int) : DapEvent() {
    override val event get() = "thread"
    override fun toJson(): String = buildEventJson()
}

data class BreakpointEvent(override val seq: Int) : DapEvent() {
    override val event get() = "breakpoint"
    override fun toJson(): String = buildEventJson()
}

data class ModuleEvent(override val seq: Int) : DapEvent() {
    override val event get() = "module"
    override fun toJson(): String = buildEventJson()
}

data class LoadedSourceEvent(override val seq: Int) : DapEvent() {
    override val event get() = "loadedSource"
    override fun toJson(): String = buildEventJson()
}

data class CapabilitiesEvent(
    override val seq: Int,
    val capabilities: Map<String, Any?> = emptyMap(),
) : DapEvent() {
    override val event get() = "capabilities"
    override fun toJson(): String = buildEventJson(JSONObject().apply {
        put("capabilities", DapMessage.mapToJsonObject(capabilities))
    })
}

data class InvalidatedEvent(override val seq: Int) : DapEvent() {
    override val event get() = "invalidated"
    override fun toJson(): String = buildEventJson()
}

data class MemoryEvent(override val seq: Int) : DapEvent() {
    override val event get() = "memory"
    override fun toJson(): String = buildEventJson()
}

data class ProgressStartEvent(override val seq: Int) : DapEvent() {
    override val event get() = "progressStart"
    override fun toJson(): String = buildEventJson()
}

data class ProgressUpdateEvent(override val seq: Int) : DapEvent() {
    override val event get() = "progressUpdate"
    override fun toJson(): String = buildEventJson()
}

data class ProgressEndEvent(override val seq: Int) : DapEvent() {
    override val event get() = "progressEnd"
    override fun toJson(): String = buildEventJson()
}

// ── Narrow catch-all ─────────────────────────────────────────────────

/**
 * Catch-all for DAP events not explicitly modeled. This should only match
 * genuinely unrecognized events; all standard DAP events have their own type.
 */
data class UnknownEvent(
    override val seq: Int,
    override val event: String,
) : DapEvent() {
    override fun toJson(): String = buildEventJson()
}

// ══════════════════════════════════════════════════════════════════════
// Responses
// ══════════════════════════════════════════════════════════════════════

/**
 * DAP response message. KDAP doesn't need to distinguish response types by
 * command (it never deeply inspects response bodies), so a single class with
 * concrete envelope fields suffices. Typed response variants can be added
 * later if needed.
 */
data class DapResponse(
    override val seq: Int,
    val requestSeq: Int,
    val command: String,
    val success: Boolean,
    val message: String? = null,
    val body: Map<String, Any?> = emptyMap(),
) : DapMessage() {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", "response")
        obj.put("seq", seq)
        obj.put("request_seq", requestSeq)
        obj.put("command", command)
        obj.put("success", success)
        if (message != null) obj.put("message", message)
        obj.put("body", Companion.mapToJsonObject(body))
        return obj.toString()
    }

    companion object {
        /** Factory for error responses (interceptor-generated). */
        fun error(requestSeq: Int, command: String, message: String) =
            DapResponse(
                seq = 0,
                requestSeq = requestSeq,
                command = command,
                success = false,
                message = message,
            )

        // Delegate to DapMessage companion for Map/JSONObject conversion
        private fun mapToJsonObject(map: Map<String, Any?>): JSONObject =
            DapMessage.mapToJsonObject(map)
    }
}

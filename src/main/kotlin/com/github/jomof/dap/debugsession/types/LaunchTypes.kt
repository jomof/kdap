package com.github.jomof.dap.types

import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin transliterations of CodeLLDB's DAP launch/attach types from
 * `codelldb-types/src/lib.rs`. All fields are nullable to match Rust's
 * `Option<T>` semantics. JSON field names use camelCase to match the
 * serde `rename_all = "camelCase"` convention used by CodeLLDB.
 *
 * Each top-level type includes a companion `fromJson(JSONObject)` parser
 * for constructing instances from DAP request arguments.
 */

// ══════════════════════════════════════════════════════════════════════
// Either<T1, T2>
// ══════════════════════════════════════════════════════════════════════

/**
 * An untagged union of two types, matching Rust's `Either<T1, T2>` with
 * `#[serde(untagged)]`.
 */
sealed class Either<out T1, out T2> {
    data class First<T1>(val value: T1) : Either<T1, Nothing>()
    data class Second<T2>(val value: T2) : Either<Nothing, T2>()
}

// ══════════════════════════════════════════════════════════════════════
// Enums
// ══════════════════════════════════════════════════════════════════════

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

// ══════════════════════════════════════════════════════════════════════
// AdapterSettings
// ══════════════════════════════════════════════════════════════════════

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

// ══════════════════════════════════════════════════════════════════════
// CommonLaunchFields
// ══════════════════════════════════════════════════════════════════════

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

// ══════════════════════════════════════════════════════════════════════
// LaunchRequestArguments
// ══════════════════════════════════════════════════════════════════════

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
}

// ══════════════════════════════════════════════════════════════════════
// AttachRequestArguments
// ══════════════════════════════════════════════════════════════════════

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
private fun JSONObject.optNullableBoolean(key: String): Boolean? =
    if (has(key)) optBoolean(key) else null

/** Returns `null` if the key is absent, otherwise the float value. */
private fun JSONObject.optNullableFloat(key: String): Float? =
    if (has(key)) optDouble(key).toFloat() else null

/** Parses a JSON array of strings, returning `null` if the key is absent. */
private fun JSONObject.optStringList(key: String): List<String>? {
    val arr = optJSONArray(key) ?: return null
    return (0 until arr.length()).map { arr.getString(it) }
}

/** Converts a JSONObject to a `Map<String, String>`. */
private fun JSONObject.toStringStringMap(): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for (key in keySet()) {
        map[key] = optString(key, "")
    }
    return map
}

/** Converts a JSONObject to a `Map<String, String?>` (values may be null). */
private fun JSONObject.toStringNullableStringMap(): Map<String, String?> {
    val map = mutableMapOf<String, String?>()
    for (key in keySet()) {
        map[key] = if (isNull(key)) null else optString(key, "")
    }
    return map
}

/** Converts a JSONObject to a `Map<String, Any?>` for generic nested objects. */
private fun JSONObject.toStringAnyMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    for (key in keySet()) {
        map[key] = when {
            isNull(key) -> null
            else -> get(key)
        }
    }
    return map
}

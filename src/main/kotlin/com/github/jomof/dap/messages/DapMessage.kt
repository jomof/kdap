package com.github.jomof.dap.messages

import org.json.JSONArray
import org.json.JSONObject

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
                    program = args?.optString("program", null),
                    terminal = args?.optString("terminal")?.ifEmpty { null },
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
    val program: String?,
    /** Terminal mode: `"integrated"`, `"external"`, or `null` for console. */
    val terminal: String? = null,
) : DapRequest() {
    override val command get() = "launch"
    override fun toJson(): String = buildRequestJson(JSONObject().apply {
        if (program != null) put("program", program)
        if (terminal != null) put("terminal", terminal)
    })
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

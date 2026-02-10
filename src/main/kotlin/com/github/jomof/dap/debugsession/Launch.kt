package com.github.jomof.dap.debugsession

import com.github.jomof.dap.DapSession.AsyncRequestContext
import com.github.jomof.dap.messages.*
import com.github.jomof.dap.sb.*
import com.github.jomof.dap.types.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.logging.Logger

/**
 * Launch/attach/disconnect/terminate logic as extension functions on
 * [DebugSession], mirroring CodeLLDB's `debug_session/launch.rs`.
 *
 * Each function reads and writes [DebugSession] state (e.g.,
 * [DebugSession.terminateOnDisconnect]) rather than holding its own
 * fields, so multiple [com.github.jomof.dap.interception] handlers
 * sharing the same [DebugSession] instance see consistent state.
 *
 * ## CodeLLDB method mapping
 *
 * | CodeLLDB (launch.rs)   | KDAP (this file)                        |
 * |------------------------|-----------------------------------------|
 * | `handle_launch`        | [DebugSession.handleLaunch]             |
 * | `complete_launch`      | [DebugSession.completeLaunch]           |
 * | `common_init_session`  | [DebugSession.commonInitSession]        |
 * | `create_terminal`      | [DebugSession.createTerminal]           |
 * | `configure_stdio`      | [DebugSession.configureStdio]           |
 * | `handle_attach`        | [DebugSession.handleAttach]             |
 * | `complete_attach`      | [DebugSession.completeAttach]           |
 * | `handle_disconnect`    | [DebugSession.handleDisconnect]         |
 * | `handle_terminate`     | [DebugSession.handleTerminate]          |
 * | `exec_commands`        | [DebugSession.execCommands]             |
 * | `print_console_mode`   | [DebugSession.printConsoleMode]         |
 */

private val log = Logger.getLogger(DebugSession::class.java.name)
private const val TCP_ACCEPT_TIMEOUT_MS = 30_000

/** Console mode announcement matching CodeLLDB's `print_console_mode`. */
const val CONSOLE_MODE_MESSAGE =
    "Console is in 'commands' mode, prefix expressions with '?'."

// ── onInitialize ─────────────────────────────────────────────────

/**
 * Captures client capabilities from the initialize request.
 * Called by [com.github.jomof.dap.interception.InitializeHandler].
 */
fun DebugSession.onInitialize(request: InitializeRequest) {
    clientSupportsRunInTerminal = request.supportsRunInTerminalRequest
}

// ── handle_launch (launch.rs:20) ─────────────────────────────────

/**
 * Mirrors CodeLLDB's `handle_launch`.
 *
 * The full launch sequence runs here: init session, create target,
 * send initialized, wait for configurationDone, create terminal,
 * then complete the launch.
 */
suspend fun DebugSession.handleLaunch(rawJson: String, ctx: AsyncRequestContext) {
    val obj = JSONObject(rawJson)
    val requestSeq = obj.optInt("seq", 0)
    val argsObj = obj.optJSONObject("arguments") ?: JSONObject()
    val args = LaunchRequestArguments.fromJson(argsObj)

    try {
        val debugger = createDebugger(ctx)

        // common_init_session (launch.rs:21)
        commonInitSession(args.common, debugger, ctx)

        // no_debug handling (launch.rs:23-29)
        val noDebug = args.noDebug ?: false
        if (noDebug) {
            logErrors { debugger.setVariable("target.preload-symbols", "false") }
            logErrors { debugger.setVariable("plugin.jit-loader.gdb.enable", "off") }
        }

        // Create target (launch.rs:31-47)
        val target: SBTarget
        if (args.targetCreateCommands != null) {
            execCommands("targetCreateCommands", args.targetCreateCommands, debugger, ctx)
            val selected = debugger.selectedTarget()
            target = if (selected.isValid()) selected else debugger.createTarget()
        } else {
            val program = args.program
                ?: throw SBError("The \"program\" attribute is required for launch.")
            target = createTargetFromProgram(program, debugger)
        }

        // send_event(EventBody::initialized) (launch.rs:50)
        ctx.sendEventToClient(InitializedEvent(seq = 0).toJson())

        // Wait for configurationDone (launch.rs:52-55)
        val configDoneRawJson = ctx.interceptClientRequest("configurationDone")
        val configDoneSeq = JSONObject(configDoneRawJson).optInt("seq", 0)

        // create_terminal (launch.rs:56)
        val ttyPath = createTerminal(args, ctx)

        // Activate the event gate so that backend events (e.g., process
        // exit on fast-exiting programs) are buffered until we've sent
        // all launch-sequence events to the client.
        ctx.activateEventGate()
        try {
            // complete_launch (launch.rs:57)
            completeLaunch(args, debugger, target, ttyPath, ctx)

            // Send responses and continued event in CodeLLDB order:
            // 1. launch response
            // 2. configurationDone response
            // 3. continued event
            sendSuccessResponse(ctx, requestSeq, "launch")
            sendSuccessResponse(ctx, configDoneSeq, "configurationDone")
            ctx.sendEventToClient(ContinuedEvent(seq = 0, allThreadsContinued = true).toJson())
        } finally {
            // Release gate — buffered backend events now flow to the client.
            ctx.releaseEventGate()
        }

    } catch (e: Exception) {
        log.warning { "Launch: launch failed: ${e.message}" }
        sendErrorResponse(ctx, requestSeq, "launch", e.message ?: "Launch failed")
    }
}

// ── complete_launch (launch.rs:62) ───────────────────────────────

/**
 * Mirrors CodeLLDB's `complete_launch`.
 *
 * Sets up environment, arguments, stdio, runs preRunCommands,
 * announces the launch, launches the process, and runs postRunCommands.
 */
private suspend fun DebugSession.completeLaunch(
    args: LaunchRequestArguments,
    debugger: SBDebugger,
    target: SBTarget,
    ttyPath: String?,
    ctx: AsyncRequestContext,
) {
    val launchInfo = target.launchInfo()

    // Environment setup (launch.rs:65-95)
    val env = createEnvironment()
    if (args.env != null) {
        for ((k, v) in args.env) {
            env.set(k, v, overwrite = true)
        }
    }
    launchInfo.setEnvironment(env, append = false)

    // Arguments (launch.rs:97-99)
    if (args.args != null) {
        launchInfo.setArguments(args.args, append = false)
    }

    // Working directory (launch.rs:100-102)
    if (args.cwd != null) {
        launchInfo.setWorkingDirectory(args.cwd)
    }

    // Stop on entry (launch.rs:103-105)
    if (args.stopOnEntry == true) {
        val flags = launchInfo.launchFlags().toMutableSet()
        flags.add(LaunchFlag.StopAtEntry)
        launchInfo.setLaunchFlags(flags)
    }

    // configure_stdio (launch.rs:106)
    configureStdio(args, launchInfo, ttyPath)

    target.setLaunchInfo(launchInfo)

    // preRunCommands (launch.rs:110-112)
    if (args.common.preRunCommands != null) {
        execCommands("preRunCommands", args.common.preRunCommands, debugger, ctx)
    }

    // Announce launch command line (launch.rs:117-123)
    val executable = target.executable()
    val programPath = if (executable.isValid()) executable.path() else args.program ?: "?"
    val commandLine = if (args.args != null) {
        "$programPath ${args.args.joinToString(" ")}"
    } else {
        programPath
    }
    consoleMessage("Launching: $commandLine", ctx)

    // Mark the process as running before launching. Events that arrive
    // from the backend after this point (which are gated) represent
    // debuggee activity and should be treated as process output.
    processRunning = true

    // Launch the process (launch.rs:154-165)
    val process: SBProcess
    if (args.processCreateCommands != null) {
        execCommands("processCreateCommands", args.processCreateCommands, debugger, ctx)
        process = target.process()
    } else {
        process = target.launch(launchInfo)
    }

    // Announce launched process (launch.rs:186-190)
    val pid = process.processId()
    consoleMessage("Launched process $pid from '$programPath'", ctx)

    // Note: the continued event is sent by handleLaunch AFTER the
    // launch and configurationDone responses, matching CodeLLDB's ordering.

    // terminate_on_disconnect = true (launch.rs:192)
    terminateOnDisconnect = true

    // common_post_run (launch.rs:193)
    commonPostRun(args.common)
}

// ── handle_attach (launch.rs:198) ────────────────────────────────

/**
 * Mirrors CodeLLDB's `handle_attach`.
 */
suspend fun DebugSession.handleAttach(rawJson: String, ctx: AsyncRequestContext) {
    val obj = JSONObject(rawJson)
    val requestSeq = obj.optInt("seq", 0)
    val argsObj = obj.optJSONObject("arguments") ?: JSONObject()
    val args = AttachRequestArguments.fromJson(argsObj)

    try {
        val debugger = createDebugger(ctx)

        // common_init_session (launch.rs:199)
        commonInitSession(args.common, debugger, ctx)

        // Validate (launch.rs:201-205)
        if (args.program == null && args.pid == null && args.targetCreateCommands == null) {
            throw SBError("Either \"program\" or \"pid\" is required to attach.")
        }

        // Create target (launch.rs:207-231)
        val target: SBTarget = when {
            args.targetCreateCommands != null -> {
                execCommands("targetCreateCommands", args.targetCreateCommands, debugger, ctx)
                val selected = debugger.selectedTarget()
                if (selected.isValid()) selected else debugger.createTarget()
            }
            args.program != null -> {
                try {
                    createTargetFromProgram(args.program, debugger)
                } catch (_: Exception) {
                    // Assume attach-by-name
                    debugger.createTarget()
                }
            }
            else -> debugger.createTarget()
        }

        // send_event(EventBody::initialized) (launch.rs:234)
        ctx.sendEventToClient(InitializedEvent(seq = 0).toJson())

        // Wait for configurationDone (launch.rs:236-239)
        val configDoneRawJson = ctx.interceptClientRequest("configurationDone")
        val configDoneSeq = JSONObject(configDoneRawJson).optInt("seq", 0)

        // Activate the event gate so that backend events are buffered
        // until we've sent all attach-sequence events.
        ctx.activateEventGate()
        try {
            // complete_attach (launch.rs:240)
            completeAttach(args, debugger, target, ctx)

            // Send attach and configurationDone responses
            sendSuccessResponse(ctx, requestSeq, "attach")
            sendSuccessResponse(ctx, configDoneSeq, "configurationDone")
        } finally {
            ctx.releaseEventGate()
        }

    } catch (e: Exception) {
        log.warning { "Launch: attach failed: ${e.message}" }
        sendErrorResponse(ctx, requestSeq, "attach", e.message ?: "Attach failed")
    }
}

// ── complete_attach (launch.rs:245) ──────────────────────────────

/**
 * Mirrors CodeLLDB's `complete_attach`.
 */
private suspend fun DebugSession.completeAttach(
    args: AttachRequestArguments,
    debugger: SBDebugger,
    target: SBTarget,
    ctx: AsyncRequestContext,
) {
    // preRunCommands (launch.rs:246-248)
    if (args.common.preRunCommands != null) {
        execCommands("preRunCommands", args.common.preRunCommands, debugger, ctx)
    }

    // Mark the process as running before attaching.
    processRunning = true

    // Attach (launch.rs:250-298)
    val process: SBProcess
    if (args.processCreateCommands != null) {
        execCommands("processCreateCommands", args.processCreateCommands, debugger, ctx)
        process = target.process()
    } else {
        val attachInfo = createAttachInfo(ctx)
        when (val pid = args.pid) {
            is Either.First -> attachInfo.setProcessId(pid.value)
            is Either.Second -> {
                val pidNum = pid.value.toLongOrNull()
                    ?: throw SBError("Process id must be a positive integer.")
                attachInfo.setProcessId(pidNum)
            }
            null -> {
                if (args.program != null) {
                    attachInfo.setExecutable(args.program)
                }
            }
        }

        attachInfo.setWaitForLaunch(args.waitFor ?: false, async = false)
        attachInfo.setIgnoreExisting(false)

        process = target.attach(attachInfo)

        if (args.stopOnEntry == true) {
            // LLDB won't generate event for the initial stop;
            // send a stopped event ourselves
            ctx.sendEventToClient(StoppedEvent(seq = 0).toJson())
        } else {
            logErrors { process.resume() }
        }
    }

    // Announce (launch.rs:300)
    val pid = process.processId()
    consoleMessage("Attached to process $pid", ctx)

    // terminate_on_disconnect = false (launch.rs:302)
    terminateOnDisconnect = false

    // common_post_run (launch.rs:303)
    commonPostRun(args.common)
}

// ── handle_disconnect (launch.rs:382) ────────────────────────────

/**
 * Mirrors CodeLLDB's `handle_disconnect`.
 */
suspend fun DebugSession.handleDisconnect(rawJson: String, ctx: AsyncRequestContext) {
    val obj = JSONObject(rawJson)
    val requestSeq = obj.optInt("seq", 0)
    val argsObj = obj.optJSONObject("arguments")
    val terminateDebuggee = argsObj?.optNullableBoolean("terminateDebuggee")

    try {
        val debugger = createDebugger(ctx)

        // preTerminateCommands (launch.rs:383-385)
        preTerminateCommands?.let { commands ->
            logErrors { execCommands("preTerminateCommands", commands, debugger, ctx) }
        }

        // terminate_debuggee (launch.rs:389)
        terminateDebuggee(debugger, terminateDebuggee, ctx)

        // exitCommands (launch.rs:391-393)
        exitCommands?.let { commands ->
            logErrors { execCommands("exitCommands", commands, debugger, ctx) }
        }

        sendSuccessResponse(ctx, requestSeq, "disconnect")

        // Send terminated event — KDAP owns the session lifecycle since it
        // didn't forward launch/attach to lldb-dap, so lldb-dap won't
        // generate this event on disconnect.
        ctx.sendEventToClient(TerminatedEvent(seq = 0).toJson())

    } catch (e: Exception) {
        log.warning { "Launch: disconnect failed: ${e.message}" }
        // Still send success — disconnect should not fail visibly
        sendSuccessResponse(ctx, requestSeq, "disconnect")
        ctx.sendEventToClient(TerminatedEvent(seq = 0).toJson())
    }
}

// ── handle_terminate (launch.rs:345) ─────────────────────────────

/**
 * Mirrors CodeLLDB's `handle_terminate`.
 *
 * Sends a graceful shutdown signal or commands if configured.
 */
suspend fun DebugSession.handleTerminate(rawJson: String, ctx: AsyncRequestContext) {
    val obj = JSONObject(rawJson)
    val requestSeq = obj.optInt("seq", 0)

    try {
        val debugger = createDebugger(ctx)

        when (val shutdown = gracefulShutdown) {
            is Either.First -> {
                // Signal name (launch.rs:347-372)
                val process = debugger.selectedTarget().process()
                val signals = process.unixSignals()
                if (!signals.isValid()) {
                    throw SBError("The current platform does not support sending signals.")
                }
                val signo = signals.signalNumberFromName(shutdown.value)
                    ?: throw SBError("Invalid signal name: ${shutdown.value}")
                signals.setShouldSuppress(signo, false)
                signals.setShouldStop(signo, false)
                signals.setShouldNotify(signo, false)

                if (!process.state().isRunning()) {
                    logErrors { process.resume() }
                    // Brief delay for the process to start running
                    kotlinx.coroutines.delay(100)
                }
                process.signal(signo)
            }
            is Either.Second -> {
                // Commands (launch.rs:374-376)
                execCommands("gracefulShutdown", shutdown.value, debugger, ctx)
            }
            null -> { /* No graceful shutdown configured */ }
        }

        sendSuccessResponse(ctx, requestSeq, "terminate")

    } catch (e: Exception) {
        log.warning { "Launch: terminate failed: ${e.message}" }
        sendErrorResponse(ctx, requestSeq, "terminate", e.message ?: "Terminate failed")
    }
}

// ── terminate_debuggee (launch.rs:398) ───────────────────────────

/**
 * Mirrors CodeLLDB's `terminate_debuggee`.
 */
private suspend fun DebugSession.terminateDebuggee(
    debugger: SBDebugger,
    forceTerminate: Boolean?,
    ctx: AsyncRequestContext,
) {
    val process = debugger.selectedTarget().process()
    if (process.isValid()) {
        val state = process.state()
        if (state.isAlive()) {
            val terminate = forceTerminate ?: terminateOnDisconnect
            if (terminate) {
                process.kill()
            } else {
                process.detach(keepStopped = false)
            }
        }
    }
}

// ── common_init_session (launch.rs:531) ──────────────────────────

/**
 * Mirrors CodeLLDB's `common_init_session`.
 *
 * Handles initialization tasks common to both launching and attaching:
 * source map, settings, console mode announcement, initCommands, etc.
 */
private suspend fun DebugSession.commonInitSession(
    common: CommonLaunchFields,
    debugger: SBDebugger,
    ctx: AsyncRequestContext,
) {
    // Source map (launch.rs:545-547)
    if (common.sourceMap != null) {
        initSourceMap(common.sourceMap, debugger, ctx)
    }

    // Console mode announcement (launch.rs:590)
    printConsoleMode(ctx)

    // initCommands (launch.rs:592-594)
    if (common.initCommands != null) {
        execCommands("initCommands", common.initCommands, debugger, ctx)
    }

    // adapter_settings.console_mode (launch.rs:600-608)
    val settings = common.adapterSettings
    if (settings != null) {
        if (settings.consoleMode != ConsoleMode.Split) {
            // Without a terminal, confirmations will just hang the session
            logErrors { debugger.setVariable("auto-confirm", "true") }
        }
    }

    // Store graceful_shutdown for later use
    gracefulShutdown = common.gracefulShutdown
}

// ── common_post_run (launch.rs:613) ──────────────────────────────

/**
 * Mirrors CodeLLDB's `common_post_run`.
 */
private fun DebugSession.commonPostRun(common: CommonLaunchFields) {
    preTerminateCommands = common.preTerminateCommands
    exitCommands = common.exitCommands
}

// ── create_terminal (launch.rs:442) ──────────────────────────────

/**
 * Mirrors CodeLLDB's `create_terminal`.
 *
 * Determines the terminal kind from launch arguments, and if an
 * integrated/external terminal is requested (and the client supports
 * `runInTerminal`), orchestrates the TCP handshake with `kdap-launch`.
 *
 * @return the TTY path if a terminal was created, `null` otherwise
 */
private suspend fun DebugSession.createTerminal(
    args: LaunchRequestArguments,
    ctx: AsyncRequestContext,
): String? {
    if (!clientSupportsRunInTerminal) return null

    // Resolve terminal kind (launch.rs:450-467)
    val terminalKind: TerminalKind = args.terminal
        ?: when (args.console) {
            ConsoleKind.InternalConsole -> TerminalKind.Console
            ConsoleKind.ExternalTerminal -> TerminalKind.External
            ConsoleKind.IntegratedTerminal -> TerminalKind.Integrated
            null -> TerminalKind.Integrated
        }

    val dapKind: String = when (terminalKind) {
        is TerminalKind.Console -> return null
        is TerminalKind.WithTerminalId -> return null // TTY already specified
        is TerminalKind.Integrated -> "integrated"
        is TerminalKind.External -> "external"
    }

    val title = args.common.name ?: "Debug"

    return runTerminalHandshake(dapKind, title, ctx)
}

/**
 * Orchestrates the `runInTerminal` reverse request flow, including
 * TCP handshake with `kdap-launch` to obtain a TTY path.
 */
private suspend fun runTerminalHandshake(
    terminalKind: String,
    title: String,
    ctx: AsyncRequestContext,
): String? {
    val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    serverSocket.soTimeout = TCP_ACCEPT_TIMEOUT_MS
    val port = serverSocket.localPort
    val host = "127.0.0.1"

    try {
        val javaHome = System.getProperty("java.home")
        val javaBin = "$javaHome/bin/java"
        val classpath = System.getProperty("java.class.path")

        val runInTerminalArgs = JSONArray().apply {
            put(javaBin)
            put("-cp")
            put(classpath)
            put("com.github.jomof.KdapLaunchKt")
            put("--connect=$host:$port")
        }

        val reverseRequest = JSONObject().apply {
            put("type", "request")
            put("command", "runInTerminal")
            put("arguments", JSONObject().apply {
                put("args", runInTerminalArgs)
                put("kind", terminalKind)
                put("title", title)
            })
        }

        val seq = ctx.sendReverseRequest(reverseRequest.toString())
        val response = ctx.awaitResponse(seq)

        if (!response.success) {
            log.warning { "Launch: client rejected runInTerminal: ${response.message}" }
            return null
        }

        return serverSocket.use { ss ->
            val socket = ss.accept()
            socket.use { s ->
                val input = s.getInputStream()
                    .bufferedReader(StandardCharsets.UTF_8)
                    .readText()
                val ttyJson = JSONObject(input)
                val tty = if (ttyJson.isNull("tty")) null else ttyJson.optString("tty", null)

                val successResponse = JSONObject().apply { put("success", true) }
                s.getOutputStream().write(
                    successResponse.toString().toByteArray(StandardCharsets.UTF_8)
                )
                s.getOutputStream().flush()

                tty
            }
        }
    } catch (e: Exception) {
        log.warning { "Launch: terminal handshake error: ${e.message}" }
        return null
    } finally {
        if (!serverSocket.isClosed) serverSocket.close()
    }
}

// ── configure_stdio (launch.rs:495) ──────────────────────────────

/**
 * Mirrors CodeLLDB's `configure_stdio`.
 *
 * Sets up stdin/stdout/stderr redirection via [SBLaunchInfo.addOpenFileAction].
 */
private suspend fun configureStdio(
    args: LaunchRequestArguments,
    launchInfo: SBLaunchInfo,
    ttyPath: String?,
) {
    // Build stdio array: either from args or empty (launch.rs:496-504)
    val stdioPaths: MutableList<String?> = when (val stdio = args.stdio) {
        is Either.First -> mutableListOf(stdio.value)
        is Either.Second -> stdio.value.toMutableList()
        null -> mutableListOf()
    }
    // Pad to at least 3 entries
    while (stdioPaths.size < 3) stdioPaths.add(null)

    // Apply TTY path for unspecified streams (launch.rs:506-525)
    if (ttyPath != null) {
        for ((fd, name) in stdioPaths.withIndex()) {
            val path = name ?: ttyPath
            val read = fd == 0
            val write = fd != 0
            launchInfo.addOpenFileAction(fd, path, read, write)
        }
    } else {
        // Only set explicitly specified stdio paths
        for ((fd, name) in stdioPaths.withIndex()) {
            if (name != null) {
                val read = fd == 0
                val write = fd != 0
                launchInfo.addOpenFileAction(fd, name, read, write)
            }
        }
    }
}

// ── create_target_from_program (launch.rs:414) ───────────────────

/**
 * Mirrors CodeLLDB's `create_target_from_program`.
 */
private suspend fun createTargetFromProgram(program: String, debugger: SBDebugger): SBTarget {
    return try {
        debugger.createTarget(program)
    } catch (e: SBError) {
        // On Windows, try adding .exe extension (launch.rs:418-425)
        if (System.getProperty("os.name", "").lowercase().contains("windows")
            && !program.endsWith(".exe")
        ) {
            debugger.createTarget("$program.exe")
        } else {
            throw e
        }
    }
}

// ── init_source_map (launch.rs:633) ──────────────────────────────

/**
 * Mirrors CodeLLDB's `init_source_map`.
 */
private suspend fun initSourceMap(
    sourceMap: Map<String, String?>,
    debugger: SBDebugger,
    ctx: AsyncRequestContext,
) {
    if (sourceMap.isEmpty()) return
    val args = sourceMap.entries.joinToString(" ") { (remote, local) ->
        "\"${escapeForLldb(remote)}\" \"${escapeForLldb(local ?: "")}\""
    }
    logErrors { debugger.setVariable("target.source-map", args) }
}

// ── print_console_mode (launch.rs:623) ───────────────────────────

/**
 * Mirrors CodeLLDB's `print_console_mode`.
 */
private suspend fun printConsoleMode(ctx: AsyncRequestContext) {
    consoleMessage(CONSOLE_MODE_MESSAGE, ctx)
}

// ── exec_commands (used throughout launch.rs) ────────────────────

/**
 * Executes a list of LLDB commands, mirroring CodeLLDB's `exec_commands`.
 */
private suspend fun execCommands(
    label: String,
    commands: List<String>,
    debugger: SBDebugger,
    ctx: AsyncRequestContext,
) {
    for (command in commands) {
        log.fine { "Launch: $label: $command" }
        // Use the evaluate helper directly for raw LLDB commands
        val json = JSONObject().apply {
            put("type", "request")
            put("command", "evaluate")
            put("arguments", JSONObject().apply {
                put("expression", command)
                put("context", "repl")
            })
        }
        val response = ctx.sendRequestToBackendAndAwait(json.toString())
        if (!response.success) {
            log.warning { "Launch: $label command failed: $command — ${response.message}" }
            throw SBError("$label command failed: ${response.message ?: command}")
        }
    }
}

// ── Event / response helpers ─────────────────────────────────────

/**
 * Sends a console output event to the client.
 */
private suspend fun consoleMessage(message: String, ctx: AsyncRequestContext) {
    ctx.sendEventToClient(OutputEvent.console("$message\n").toJson())
}

/**
 * Sends a success response for a given command.
 */
private suspend fun sendSuccessResponse(
    ctx: AsyncRequestContext,
    requestSeq: Int,
    command: String,
) {
    val response = DapResponse(
        seq = 0,
        requestSeq = requestSeq,
        command = command,
        success = true,
    )
    ctx.sendEventToClient(response.toJson())
}

/**
 * Sends an error response for a given command.
 */
private suspend fun sendErrorResponse(
    ctx: AsyncRequestContext,
    requestSeq: Int,
    command: String,
    message: String,
) {
    val response = DapResponse.error(requestSeq, command, message)
    ctx.sendEventToClient(response.toJson())
}

// ── Utility helpers ──────────────────────────────────────────────────

/** Runs [block] and logs any exception without rethrowing. */
private suspend fun logErrors(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        log.warning { "logErrors: ${e.message}" }
    }
}

private fun escapeForLldb(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

/** Returns `null` if the key is absent; otherwise the boolean value. */
private fun JSONObject.optNullableBoolean(key: String): Boolean? =
    if (has(key)) optBoolean(key) else null

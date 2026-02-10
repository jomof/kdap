package com.github.jomof

import com.github.jomof.dap.messages.*
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Verifies that KDAP produces the same adapter-originated messages during a
 * console-mode launch lifecycle as CodeLLDB.
 *
 * The [expectedLaunchSequence] documents the **full** expected message stream
 * (responses and events) for both servers. Each entry is tagged with which
 * server(s) produce it:
 * - [both]: produced by both KDAP and CodeLLDB (the convergence target)
 * - [kdapOnly]: produced only by KDAP (differences to eliminate)
 * - [codelldbOnly]: produced only by CodeLLDB (differences to eliminate)
 *
 * The goal is to shrink the server-specific entries to zero over time,
 * indicating full alignment.
 *
 * Only [ModuleEvent]s are excluded from matching — their count is
 * non-deterministic (varies by platform and library set). Every other
 * event and response must appear in the expected sequence or the test fails.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DapLaunchSequenceTest {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    // ── Shared expected launch sequence ──────────────────────────────────

    /**
     * The **complete** expected message sequence during a `terminal: "console"`
     * launch lifecycle, with every difference between KDAP and CodeLLDB
     * explicitly annotated.
     *
     * Only [ModuleEvent]s are excluded from matching — their count is
     * non-deterministic (varies by platform and library set). Every other
     * response and event must appear here or the test fails.
     *
     * Entries tagged [kdapOnly] or [codelldbOnly] represent known behavioral
     * differences. The goal is to eliminate these over time.
     */
    private val expectedLaunchSequence = listOf(
        // ── Initialize phase ─────────────────────────────────────────
        ExpectedResponse(
            "initialize response", both,
            command = "initialize",
            // Body contains full capabilities — tested in DapInitializeTest
            skip = setOf("body"),
        ),

        // ── Launch phase ─────────────────────────────────────────────
        ExpectedEvent(
            "console mode announcement", both,
            event = OutputEvent(
                seq = 0, category = "console",
                output = "Console is in 'commands' mode, prefix expressions with '?'.\n",
            ),
        ),
        ExpectedEvent(
            "initialized event", both,
            event = InitializedEvent(seq = 0),
        ),

        // ── After configurationDone ──────────────────────────────────
        ExpectedEvent(
            "launch command line", both,
            event = OutputEvent(seq = 0, category = "console", output = "Launching: <program>"),
            regex = mapOf("output" to Regex("""^Launching: .+\n$""")),
        ),
        ExpectedEvent(
            "launch confirmation with PID", both,
            event = OutputEvent(seq = 0, category = "console", output = "Launched process <pid> from '<program>'"),
            regex = mapOf("output" to Regex("""^Launched process \d+.*\n$""")),
        ),

        // Both servers send launch and configurationDone responses together,
        // after launch output events but before continued.
        ExpectedResponse("launch response", both, command = "launch"),
        ExpectedResponse("configurationDone response", both, command = "configurationDone"),

        // Both servers send 'continued' when the process starts running.
        // (KDAP converts lldb-dap's 'process' event into 'continued'.)
        ExpectedEvent(
            "continued event", both,
            event = ContinuedEvent(seq = 0, allThreadsContinued = true),
        ),

        // ── Program output ───────────────────────────────────────────
        ExpectedEvent(
            "debuggee stdout", both,
            event = OutputEvent(seq = 0, category = "stdout", output = "No testcase was specified."),
            regex = mapOf("output" to Regex("""^No testcase was specified\.\s*$""")),
        ),

        // ── Process exit ─────────────────────────────────────────────
        ExpectedEvent(
            "process exit", both,
            event = OutputEvent(seq = 0, category = "console", output = "Process exited with code <N>."),
            regex = mapOf("output" to Regex("""^Process exited with code -?\d+\.\n$""")),
        ),

        // ── Shutdown ─────────────────────────────────────────────────
        // Exit code is 255 on macOS/Linux (8-bit truncation of -1)
        // and -1 on Windows (32-bit signed).
        ExpectedEvent(
            "exited event", both,
            event = ExitedEvent(seq = 0, exitCode = 255),
            regex = mapOf("exitCode" to Regex("""-1|255""")),
        ),
        ExpectedEvent(
            "terminated event", both,
            event = TerminatedEvent(seq = 0),
        ),
    )

    // ── Expected sequence: stdio testcase (exit code 0, stdout+stderr) ──

    /**
     * Expected message sequence when the debuggee is launched with
     * `args: ["stdio"]`. The debuggee prints "stdout\n" to stdout and
     * "stderr\n" to stderr, then exits with code 0.
     *
     * Structural differences from the default (no-args) sequence:
     * - Debuggee output is "stdout\n" instead of "No testcase was specified.\n"
     * - An additional stderr output event appears
     * - Exit code is 0 instead of 255
     */
    private val expectedStdioLaunchSequence = listOf(
        // ── Initialize phase ─────────────────────────────────────────
        ExpectedResponse(
            "initialize response", both,
            command = "initialize",
            skip = setOf("body"),
        ),

        // ── Launch phase ─────────────────────────────────────────────
        ExpectedEvent(
            "console mode announcement", both,
            event = OutputEvent(
                seq = 0, category = "console",
                output = "Console is in 'commands' mode, prefix expressions with '?'.\n",
            ),
        ),
        ExpectedEvent(
            "initialized event", both,
            event = InitializedEvent(seq = 0),
        ),

        // ── After configurationDone ──────────────────────────────────
        ExpectedEvent(
            "launch command line", both,
            event = OutputEvent(seq = 0, category = "console", output = "Launching: <program>"),
            regex = mapOf("output" to Regex("""^Launching: .+\n$""")),
        ),
        ExpectedEvent(
            "launch confirmation with PID", both,
            event = OutputEvent(seq = 0, category = "console", output = "Launched process <pid> from '<program>'"),
            regex = mapOf("output" to Regex("""^Launched process \d+.*\n$""")),
        ),

        ExpectedResponse("launch response", both, command = "launch"),
        ExpectedResponse("configurationDone response", both, command = "configurationDone"),

        ExpectedEvent(
            "continued event", both,
            event = ContinuedEvent(seq = 0, allThreadsContinued = true),
        ),

        // ── Program output (stdio testcase) ──────────────────────────
        // Both stdout and stderr arrive as category "stdout" (lldb-dap
        // does not distinguish). Both servers combine consecutive chunks
        // into a single output event.
        ExpectedEvent(
            "debuggee stdout+stderr", both,
            event = OutputEvent(seq = 0, category = "stdout", output = "stdout\\r\\nstderr\\r\\n"),
            regex = mapOf("output" to Regex("""^stdout\r?\nstderr\r?\n$""")),
        ),

        // ── Process exit ─────────────────────────────────────────────
        ExpectedEvent(
            "process exit", both,
            event = OutputEvent(seq = 0, category = "console", output = "Process exited with code <N>."),
            regex = mapOf("output" to Regex("""^Process exited with code -?\d+\.\n$""")),
        ),

        // ── Shutdown ─────────────────────────────────────────────────
        ExpectedEvent(
            "exited event", both,
            event = ExitedEvent(seq = 0, exitCode = 0),
        ),
        ExpectedEvent(
            "terminated event", both,
            event = TerminatedEvent(seq = 0),
        ),
    )

    // ── Expected sequence: terminal mode (integrated / external) ──────────

    /**
     * Expected message sequence for a terminal-mode launch (`terminal:
     * "integrated"` or `"external"`).
     *
     * Differences from [expectedLaunchSequence] (console mode):
     * - Both servers send a `runInTerminal` reverse request after
     *   `initialized` so the client can open a terminal. KDAP runs
     *   `kdap-launch` (Java helper in the same JAR) while CodeLLDB
     *   runs `codelldb-launch` (native binary).
     * - In a headless test environment neither helper has a TTY, so
     *   both servers skip stdio redirection. All remaining events are
     *   identical to console mode.
     */
    private fun expectedTerminalLaunchSequence(kind: String) = listOf(
        // ── Initialize phase ─────────────────────────────────────────
        ExpectedResponse(
            "initialize response", both,
            command = "initialize",
            skip = setOf("body"),
        ),

        // ── runInTerminal (KDAP) ─────────────────────────────────────
        // KDAP sends runInTerminal immediately when the launch request
        // is received, before the backend's initialized event arrives.
        // The async handler runs concurrently with the backend reader.
        //
        // KDAP args: [java, -cp, classpath, KdapLaunchKt, --connect=HOST:PORT]
        ExpectedRequest(
            "runInTerminal ($kind)", kdapOnly,
            request = RunInTerminalRequest(
                seq = 0,
                kind = kind,
                title = "KDAP Debug",
                args = listOf("<java>", "-cp", "<classpath>", "KdapLaunchKt", "--connect=127.0.0.1:<port>"),
            ),
            regex = mapOf(
                "args" to Regex("""\["[^"]+","-cp","[^"]+","com\.github\.jomof\.KdapLaunchKt","--connect=127\.0\.0\.1:\d+"]"""),
            ),
        ),

        // ── Launch phase ─────────────────────────────────────────────
        ExpectedEvent(
            "console mode announcement", both,
            event = OutputEvent(
                seq = 0, category = "console",
                output = "Console is in 'commands' mode, prefix expressions with '?'.\n",
            ),
        ),
        ExpectedEvent(
            "initialized event", both,
            event = InitializedEvent(seq = 0),
        ),

        // ── runInTerminal (CodeLLDB) ─────────────────────────────────
        // CodeLLDB sends runInTerminal after initialized, once the
        // launch has been processed sequentially.
        //
        // CodeLLDB args: [codelldb-launch, --connect=HOST:PORT, --clear-screen]
        ExpectedRequest(
            "runInTerminal ($kind)", codelldbOnly,
            request = RunInTerminalRequest(
                seq = 0,
                kind = kind,
                title = "test",
                args = listOf(
                    "<codelldb-launch>",
                    "--connect=127.0.0.1:<port>",
                    "--clear-screen",
                ),
            ),
            regex = mapOf(
                "args" to Regex("""\[".+[/\\]codelldb-launch[^"]*","--connect=127\.0\.0\.1:\d+","--clear-screen"]"""),
            ),
        ),

        // ── After configurationDone ──────────────────────────────────
        ExpectedEvent(
            "launch command line", both,
            event = OutputEvent(seq = 0, category = "console", output = "Launching: <program>"),
            regex = mapOf("output" to Regex("""^Launching: .+\n$""")),
        ),
        ExpectedEvent(
            "launch confirmation with PID", both,
            event = OutputEvent(seq = 0, category = "console", output = "Launched process <pid> from '<program>'"),
            regex = mapOf("output" to Regex("""^Launched process \d+.*\n$""")),
        ),

        ExpectedResponse("launch response", both, command = "launch"),
        ExpectedResponse("configurationDone response", both, command = "configurationDone"),

        ExpectedEvent(
            "continued event", both,
            event = ContinuedEvent(seq = 0, allThreadsContinued = true),
        ),

        // ── Program output ───────────────────────────────────────────
        ExpectedEvent(
            "debuggee stdout", both,
            event = OutputEvent(seq = 0, category = "stdout", output = "No testcase was specified."),
            regex = mapOf("output" to Regex("""^No testcase was specified\.\s*$""")),
        ),

        // ── Process exit ─────────────────────────────────────────────
        ExpectedEvent(
            "process exit", both,
            event = OutputEvent(seq = 0, category = "console", output = "Process exited with code <N>."),
            regex = mapOf("output" to Regex("""^Process exited with code -?\d+\.\n$""")),
        ),

        // ── Shutdown ─────────────────────────────────────────────────
        // Exit code is 255 on macOS/Linux (8-bit truncation of -1)
        // and -1 on Windows (32-bit signed).
        ExpectedEvent(
            "exited event", both,
            event = ExitedEvent(seq = 0, exitCode = 255),
            regex = mapOf("exitCode" to Regex("""-1|255""")),
        ),
        ExpectedEvent(
            "terminated event", both,
            event = TerminatedEvent(seq = 0),
        ),
    )

    // ── Tests ────────────────────────────────────────────────────────────

    // --- Default launch (no debuggee args, terminal: "console", name: "test") ---

    @Test
    fun `kdap produces expected launch events`() {
        runLaunchAndAssert(ConnectionMode.STDIO, Server.KDAP, "kdap")
    }

    @Test
    fun `codelldb produces expected launch events`() {
        // CodeLLDB on Windows doesn't isolate debuggee stdout from the DAP
        // transport, causing missing output events and framing corruption.
        assumeTrue(!isWindows, "CodeLLDB stdio transport is unreliable on Windows")
        runLaunchAndAssert(ConnectionMode.STDIO_CODELDB, Server.CODELLDB, "codelldb")
    }

    // --- Custom name (same sequence — name is a display label, not in events) ---

    @Test
    fun `kdap produces expected events with custom name`() {
        runLaunchAndAssert(
            ConnectionMode.STDIO, Server.KDAP, "kdap-custom-name",
            launchArgs = mapOf("name" to "my-debug-session", "terminal" to "console"),
        )
    }

    @Test
    fun `codelldb produces expected events with custom name`() {
        assumeTrue(!isWindows, "CodeLLDB stdio transport is unreliable on Windows")
        runLaunchAndAssert(
            ConnectionMode.STDIO_CODELDB, Server.CODELLDB, "codelldb-custom-name",
            launchArgs = mapOf("name" to "my-debug-session", "terminal" to "console"),
        )
    }

    // --- stdio testcase (debuggee prints stdout+stderr, exits 0) ---

    @Test
    fun `kdap produces expected events for stdio testcase`() {
        runLaunchAndAssert(
            ConnectionMode.STDIO, Server.KDAP, "kdap-stdio",
            launchArgs = mapOf("name" to "test", "terminal" to "console", "args" to listOf("stdio")),
            expected = expectedStdioLaunchSequence,
        )
    }

    @Test
    fun `codelldb produces expected events for stdio testcase`() {
        assumeTrue(!isWindows, "CodeLLDB stdio transport is unreliable on Windows")
        runLaunchAndAssert(
            ConnectionMode.STDIO_CODELDB, Server.CODELLDB, "codelldb-stdio",
            launchArgs = mapOf("name" to "test", "terminal" to "console", "args" to listOf("stdio")),
            expected = expectedStdioLaunchSequence,
        )
    }

    // --- terminal: "integrated" (CodeLLDB uses runInTerminal; KDAP/lldb-dap ignores) ---

    @Test
    fun `kdap produces expected events for terminal integrated`() {
        runLaunchAndAssert(
            ConnectionMode.STDIO, Server.KDAP, "kdap-integrated",
            launchArgs = mapOf("name" to "test", "terminal" to "integrated"),
            expected = expectedTerminalLaunchSequence("integrated"),
            supportsRunInTerminal = true,
        )
    }

    @Test
    fun `codelldb produces expected events for terminal integrated`() {
        assumeTrue(!isWindows, "CodeLLDB stdio transport is unreliable on Windows")
        runLaunchAndAssert(
            ConnectionMode.STDIO_CODELDB, Server.CODELLDB, "codelldb-integrated",
            launchArgs = mapOf("name" to "test", "terminal" to "integrated"),
            expected = expectedTerminalLaunchSequence("integrated"),
            supportsRunInTerminal = true,
        )
    }

    // --- terminal: "external" (CodeLLDB uses runInTerminal; KDAP/lldb-dap ignores) ---

    @Test
    fun `kdap produces expected events for terminal external`() {
        runLaunchAndAssert(
            ConnectionMode.STDIO, Server.KDAP, "kdap-external",
            launchArgs = mapOf("name" to "test", "terminal" to "external"),
            expected = expectedTerminalLaunchSequence("external"),
            supportsRunInTerminal = true,
        )
    }

    @Test
    fun `codelldb produces expected events for terminal external`() {
        assumeTrue(!isWindows, "CodeLLDB stdio transport is unreliable on Windows")
        runLaunchAndAssert(
            ConnectionMode.STDIO_CODELDB, Server.CODELLDB, "codelldb-external",
            launchArgs = mapOf("name" to "test", "terminal" to "external"),
            expected = expectedTerminalLaunchSequence("external"),
            supportsRunInTerminal = true,
        )
    }

    // ── Shared test body ─────────────────────────────────────────────────

    /**
     * Runs a full launch lifecycle and asserts the message stream matches
     * [expected].
     *
     * @param launchArgs extra arguments merged into the DAP `launch` request
     *   (e.g. `"name"`, `"terminal"`, `"args"`). These are passed directly to
     *   [DapTestUtils.sendLaunchRequest] as `extraArgs`.
     * @param expected the expected message sequence; defaults to the console-mode
     *   sequence with no debuggee arguments ([expectedLaunchSequence]).
     * @param supportsRunInTerminal when true, advertises the
     *   `supportsRunInTerminalRequest` client capability and handles
     *   `runInTerminal` reverse requests during the launch lifecycle.
     */
    private fun runLaunchAndAssert(
        mode: ConnectionMode,
        server: Server,
        label: String,
        launchArgs: Map<String, Any> = mapOf("name" to "test", "terminal" to "console"),
        expected: List<Expected> = expectedLaunchSequence,
        supportsRunInTerminal: Boolean = false,
    ) {
        val debuggee = DapTestUtils.resolveDebuggeeBinary()
        mode.connect().use { ctx ->
            try {
                val allMessages = captureLaunchMessages(
                    ctx, debuggee.absolutePath, launchArgs,
                    supportsRunInTerminal = supportsRunInTerminal,
                )
                val interesting = filterToInterestingMessages(allMessages)
                val applicableExpected = expected.filter { it.appliesTo(server) }
                assertFullMatch(interesting, applicableExpected, label)
            } catch (e: Exception) {
                throw AssertionError("[$label] Failed during launch sequence test\n${ctx.diagnostics()}", e)
            }
        }
    }

    // ── Message capture ──────────────────────────────────────────────────

    /**
     * Executes a full DAP launch lifecycle (initialize -> launch ->
     * configurationDone -> terminated) and returns every adapter-to-client
     * message as a parsed [DapMessage].
     *
     * @param launchArgs extra arguments for the `launch` request (merged into
     *   the `arguments` object alongside `program`).
     * @param supportsRunInTerminal when true, advertises the
     *   `supportsRunInTerminalRequest` capability and handles `runInTerminal`
     *   reverse requests by running the requested command.
     */
    private fun captureLaunchMessages(
        ctx: ConnectionContext,
        program: String,
        launchArgs: Map<String, Any> = emptyMap(),
        supportsRunInTerminal: Boolean = false,
    ): List<DapMessage> {
        val input = ctx.inputStream
        val output = ctx.outputStream
        val messages = mutableListOf<DapMessage>()

        // 1. Initialize
        if (ctx.initializeResponse != null) {
            messages.add(DapMessage.parse(ctx.initializeResponse!!))
        } else {
            DapTestUtils.sendInitializeRequest(output, supportsRunInTerminal = supportsRunInTerminal)
            messages.add(DapMessage.parse(DapTestUtils.readDapMessage(input)))
        }

        // 2. Launch (caller provides all extra args — terminal, name, args, etc.)
        DapTestUtils.sendLaunchRequest(output, seq = 2, program = program, extraArgs = launchArgs)

        // 3. Read until 'initialized' event (runInTerminal may arrive here)
        readUntil(input, output, messages) { it is InitializedEvent }

        // 4. ConfigurationDone
        DapTestUtils.sendConfigurationDoneRequest(output, seq = 3)

        // 5. Read until 'terminated' event
        readUntil(input, output, messages, maxMessages = 500) { it is TerminatedEvent }

        return messages
    }

    /**
     * Reads DAP messages into [dest] until [predicate] matches.
     *
     * When a [RunInTerminalRequest] reverse request is encountered, it is
     * handled automatically: the command from its [RunInTerminalRequest.args]
     * is executed via [ProcessBuilder] and a success response is sent back
     * to the adapter. The request is still recorded in [dest] for sequence
     * matching.
     */
    private fun readUntil(
        input: InputStream,
        output: OutputStream,
        dest: MutableList<DapMessage>,
        maxMessages: Int = 50,
        predicate: (DapMessage) -> Boolean,
    ) {
        repeat(maxMessages) {
            val message = DapMessage.parse(DapTestUtils.readDapMessage(input))
            dest.add(message)
            if (message is RunInTerminalRequest) {
                handleRunInTerminal(message, output)
                return@repeat // don't check predicate — continue reading
            }
            if (predicate(message)) return
        }
        val tail = dest.takeLast(10).joinToString("\n") { "  ${summarize(it)}" }
        error("Predicate not satisfied within $maxMessages messages. Last 10:\n$tail")
    }

    /**
     * Handles a `runInTerminal` reverse request.
     *
     * For **KDAP** requests (args contain `KdapLaunchKt`): simulates `kdap-launch`
     * by connecting directly to KDAP's TCP listener and sending fake TTY info
     * (`tty: null` = no real TTY in test environment). This tests the full
     * runInTerminal flow including the TCP handshake, without needing a real
     * terminal.
     *
     * For **CodeLLDB** requests (args contain `codelldb-launch`): runs the
     * actual command via [ProcessBuilder]. `codelldb-launch` connects back to
     * CodeLLDB's TCP and reports terminal device info.
     */
    private fun handleRunInTerminal(request: RunInTerminalRequest, output: OutputStream) {
        require(request.args.isNotEmpty()) {
            "runInTerminal request has empty args: $request"
        }

        val isKdapRequest = request.args.any { it.contains("KdapLaunchKt") }

        if (isKdapRequest) {
            // KDAP: simulate kdap-launch by connecting directly to the TCP port
            val connectArg = request.args.first { it.startsWith("--connect=") }
            val address = connectArg.removePrefix("--connect=")
            val lastColon = address.lastIndexOf(':')
            val host = address.substring(0, lastColon)
            val port = address.substring(lastColon + 1).toInt()

            // 1. Connect to KDAP's TCP listener and send fake TTY info.
            //    In CI/headless there is no TTY, so we send null.
            //    KDAP will skip stdio injection and forward the launch as-is.
            val tcpSocket = Socket(host, port)
            val ttyJson = JSONObject().apply { put("tty", JSONObject.NULL) }
            tcpSocket.getOutputStream().write(
                ttyJson.toString().toByteArray(StandardCharsets.UTF_8)
            )
            tcpSocket.getOutputStream().flush()
            tcpSocket.shutdownOutput() // signal we're done writing

            // 2. Send runInTerminal success response to KDAP.
            //    This unblocks the handler's awaitResponse().
            DapTestUtils.sendRunInTerminalResponse(output, request.seq)

            // 3. Read TCP response (handler writes after accept + read).
            tcpSocket.getInputStream()
                .bufferedReader(StandardCharsets.UTF_8)
                .readText()
            tcpSocket.close()
        } else {
            // CodeLLDB: run the command — codelldb-launch connects back via TCP
            val process = ProcessBuilder(request.args)
                .redirectErrorStream(true)
                .start()
            // Send success response so the adapter can proceed
            DapTestUtils.sendRunInTerminalResponse(output, request.seq)
            // Wait briefly for codelldb-launch to finish its TCP handshake
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }
}

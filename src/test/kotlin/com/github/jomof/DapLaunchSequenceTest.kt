package com.github.jomof

import com.github.jomof.dap.messages.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.InputStream
import java.io.OutputStream
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
            regex = mapOf("output" to Regex("""^Process exited with code \d+\.\n$""")),
        ),

        // ── Shutdown ─────────────────────────────────────────────────
        ExpectedEvent(
            "exited event", both,
            event = ExitedEvent(seq = 0, exitCode = 255),
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
            regex = mapOf("output" to Regex("""^Process exited with code \d+\.\n$""")),
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
     * - CodeLLDB sends a `runInTerminal` reverse request before events.
     *   KDAP/lldb-dap ignores the `terminal` parameter, so no reverse
     *   request is sent — the entry is tagged [codelldbOnly].
     * - In a headless test environment `codelldb-launch` has no TTY, so
     *   CodeLLDB receives `terminalId: null` and skips stdio redirection.
     *   All remaining events are identical to console mode.
     */
    private fun expectedTerminalLaunchSequence(kind: String) = listOf(
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

        // ── runInTerminal (CodeLLDB only) ────────────────────────────
        // CodeLLDB sends this reverse request after initialized so the
        // client can open a terminal and run codelldb-launch. lldb-dap
        // ignores the terminal parameter, so KDAP never sees this.
        // TODO: implement runInTerminal support in KDAP to align with CodeLLDB
        //
        // args[0] = path to codelldb-launch (dynamic path)
        // args[1] = --connect=127.0.0.1:<port> (dynamic port)
        // args[2] = --clear-screen (static)
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
            // args is a JSON array — regex matches its stringified form.
            // kind and title are exact-matched by structural comparison.
            regex = mapOf(
                "args" to Regex("""\[".+/codelldb-launch","--connect=127\.0\.0\.1:\d+","--clear-screen"]"""),
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
            regex = mapOf("output" to Regex("""^Process exited with code \d+\.\n$""")),
        ),

        // ── Shutdown ─────────────────────────────────────────────────
        ExpectedEvent(
            "exited event", both,
            event = ExitedEvent(seq = 0, exitCode = 255),
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
     * Handles a `runInTerminal` reverse request by executing the command and
     * sending a success response back to the adapter.
     *
     * The adapter (CodeLLDB) provides the full command in [request.args],
     * typically `["/path/to/codelldb-launch", "--connect=HOST:PORT", ...]`.
     * `codelldb-launch` connects back to the adapter via TCP and reports
     * terminal device info. In a headless environment (no TTY), it sends
     * `terminalId: null` and the adapter skips stdio redirection.
     */
    private fun handleRunInTerminal(request: RunInTerminalRequest, output: OutputStream) {
        require(request.args.isNotEmpty()) {
            "runInTerminal request has empty args: $request"
        }
        // Start the command — codelldb-launch connects back to the adapter via TCP
        val process = ProcessBuilder(request.args)
            .redirectErrorStream(true)
            .start()
        // Send success response so the adapter can proceed
        DapTestUtils.sendRunInTerminalResponse(output, request.seq)
        // Wait briefly for codelldb-launch to finish its TCP handshake.
        // It should complete quickly (connects, sends JSON, closes).
        process.waitFor(5, TimeUnit.SECONDS)
    }
}

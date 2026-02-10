package com.github.jomof

import com.github.jomof.dap.messages.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Verifies that KDAP produces the same adapter-originated messages during an
 * attach lifecycle as CodeLLDB.
 *
 * The [expectedAttachSequence] documents the **full** expected message stream
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
 *
 * ## Attach vs Launch
 *
 * Unlike [DapLaunchSequenceTest], the debuggee is spawned externally before
 * the DAP session starts (using the `inf_loop` testcase). The adapter
 * attaches to the running process by PID with `stopOnEntry: true`, then the
 * test sends `disconnect` to detach and shut down cleanly. The debuggee
 * process is killed in a `finally` block.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DapAttachSequenceTest {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    // ── Shared expected attach sequence ──────────────────────────────────

    /**
     * The **complete** expected message sequence during an attach lifecycle
     * with `stopOnEntry: true`, with every difference between KDAP and
     * CodeLLDB explicitly annotated.
     *
     * Only [ModuleEvent]s are excluded from matching — their count is
     * non-deterministic (varies by platform and library set). Every other
     * response and event must appear here or the test fails.
     *
     * Entries tagged [kdapOnly] or [codelldbOnly] represent known behavioral
     * differences. The goal is to eliminate these over time.
     */
    private val expectedAttachSequence = listOf(
        // ── Initialize phase ─────────────────────────────────────────
        ExpectedResponse(
            "initialize response", both,
            command = "initialize",
            // Body contains full capabilities — tested in DapInitializeTest
            skip = setOf("body"),
        ),

        // ── Attach phase ─────────────────────────────────────────────
        // KDAP: attach response arrives immediately (lldb-dap responds
        // before the backend has sent initialized).
        ExpectedResponse("attach response", kdapOnly, command = "attach"),

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
        // CodeLLDB: reports the stop reason before the stopped event.
        ExpectedEvent(
            "stop reason output", codelldbOnly,
            event = OutputEvent(seq = 0, category = "stderr", output = "Stop reason: signal SIGSTOP\n"),
        ),

        // ── Stop on entry ────────────────────────────────────────────
        ExpectedEvent(
            "stopped event (entry)", both,
            event = StoppedEvent(seq = 0),
        ),

        // CodeLLDB: reports the attached process after the stopped event.
        ExpectedEvent(
            "attached to process", codelldbOnly,
            event = OutputEvent(seq = 0, category = "console", output = "Attached to process <pid>"),
            regex = mapOf("output" to Regex("""^Attached to process \d+\n$""")),
        ),

        // CodeLLDB: attach response arrives after stopped event, once
        // the attach has been fully processed.
        ExpectedResponse("attach response", codelldbOnly, command = "attach"),

        ExpectedResponse("configurationDone response", both, command = "configurationDone"),

        // ── Disconnect / detach ──────────────────────────────────────
        // KDAP: ProcessEventHandler converts lldb-dap's ProcessEvent
        // (emitted during attach) into a ContinuedEvent. This arrives
        // during the disconnect phase because lldb-dap defers it.
        ExpectedEvent(
            "continued event (detach)", kdapOnly,
            event = ContinuedEvent(seq = 0, allThreadsContinued = true),
        ),

        // CodeLLDB: sends a disconnect response and detach notification.
        ExpectedResponse("disconnect response", codelldbOnly, command = "disconnect"),
        ExpectedEvent(
            "detached notification", codelldbOnly,
            event = OutputEvent(seq = 0, category = "console", output = "Detached from debuggee.\n"),
        ),

        ExpectedEvent(
            "terminated event", both,
            event = TerminatedEvent(seq = 0),
        ),
    )

    // ── Tests (C++ debuggee) ────────────────────────────────────────────

    @Test
    fun `kdap produces expected attach events`() {
        runAttachAndAssert(ConnectionMode.STDIO, Server.KDAP, "kdap-attach")
    }

    @Test
    fun `codelldb produces expected attach events`() {
        assumeTrue(!isWindows, "CodeLLDB stdio transport is unreliable on Windows")
        runAttachAndAssert(ConnectionMode.STDIO_CODELDB, Server.CODELLDB, "codelldb-attach")
    }

    // ── Tests (Rust debuggee) ───────────────────────────────────────────

    @Test
    fun `kdap produces expected attach events (rust)`() {
        runAttachAndAssert(ConnectionMode.STDIO, Server.KDAP, "kdap-attach-rust", debuggee = Debuggee.RUST)
    }

    @Test
    fun `codelldb produces expected attach events (rust)`() {
        assumeTrue(!isWindows, "CodeLLDB stdio transport is unreliable on Windows")
        runAttachAndAssert(ConnectionMode.STDIO_CODELDB, Server.CODELLDB, "codelldb-attach-rust", debuggee = Debuggee.RUST)
    }

    // ── Shared test body ─────────────────────────────────────────────────

    /**
     * Spawns a debuggee with the `inf_loop` testcase, runs a full attach
     * lifecycle, and asserts the message stream matches [expectedAttachSequence].
     *
     * @param debuggee which debuggee binary to use ([Debuggee.CPP] or [Debuggee.RUST]).
     */
    private fun runAttachAndAssert(
        mode: ConnectionMode,
        server: Server,
        label: String,
        debuggee: Debuggee = Debuggee.CPP,
    ) {
        val debuggeeBinary = debuggee.resolve()

        // Spawn the debuggee with inf_loop so it stays alive for attach.
        val debuggeeProcess = ProcessBuilder(debuggeeBinary.absolutePath, "inf_loop")
            .redirectErrorStream(true)
            .start()

        // Drain the debuggee's stdout/stderr to prevent pipe buffer blocking.
        // The inf_loop testcase prints a counter every second; without draining,
        // the pipe buffer could eventually fill and block the debuggee.
        val drainThread = thread(isDaemon = true, name = "debuggee-drain") {
            debuggeeProcess.inputStream.use { it.readAllBytes() }
        }

        try {
            // Give the debuggee a moment to start up and enter its loop.
            Thread.sleep(500)
            require(debuggeeProcess.isAlive) {
                "Debuggee exited prematurely with code ${debuggeeProcess.exitValue()}"
            }

            mode.connect().use { ctx ->
                try {
                    val allMessages = captureAttachMessages(
                        ctx,
                        program = debuggeeBinary.absolutePath,
                        pid = debuggeeProcess.pid(),
                    )
                    val interesting = filterToInterestingMessages(allMessages)
                    val applicableExpected = expectedAttachSequence.filter { it.appliesTo(server) }
                    assertFullMatch(interesting, applicableExpected, label)
                } catch (e: Exception) {
                    throw AssertionError("[$label] Failed during attach sequence test\n${ctx.diagnostics()}", e)
                }
            }
        } finally {
            // Always kill the debuggee — it's in an infinite loop.
            debuggeeProcess.destroyForcibly()
            debuggeeProcess.waitFor(5, TimeUnit.SECONDS)
        }
    }

    // ── Message capture ──────────────────────────────────────────────────

    /**
     * Executes a full DAP attach lifecycle (initialize -> attach ->
     * configurationDone -> stopped -> disconnect -> terminated) and returns
     * every adapter-to-client message as a parsed [DapMessage].
     */
    private fun captureAttachMessages(
        ctx: ConnectionContext,
        program: String,
        pid: Long,
    ): List<DapMessage> {
        val input = ctx.inputStream
        val output = ctx.outputStream
        val messages = mutableListOf<DapMessage>()

        // 1. Initialize
        if (ctx.initializeResponse != null) {
            messages.add(DapMessage.parse(ctx.initializeResponse!!))
        } else {
            DapTestUtils.sendInitializeRequest(output)
            messages.add(DapMessage.parse(DapTestUtils.readDapMessage(input)))
        }

        // 2. Attach (with stopOnEntry: true)
        DapTestUtils.sendAttachRequest(output, seq = 2, pid = pid, program = program)

        // 3. Read until 'initialized' event
        readUntil(input, output, messages) { it is InitializedEvent }

        // 4. ConfigurationDone
        DapTestUtils.sendConfigurationDoneRequest(output, seq = 3)

        // 5. Read until 'stopped' event (stopOnEntry)
        readUntil(input, output, messages, maxMessages = 100) { it is StoppedEvent }

        // 6. Disconnect (detach, don't terminate debuggee)
        DapTestUtils.sendDisconnectRequest(output, seq = 4)

        // 7. Read until 'terminated' event
        readUntil(input, output, messages, maxMessages = 100) { it is TerminatedEvent }

        return messages
    }

    /**
     * Reads DAP messages into [dest] until [predicate] matches.
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
            if (predicate(message)) return
        }
        val tail = dest.takeLast(10).joinToString("\n") { "  ${summarize(it)}" }
        error("Predicate not satisfied within $maxMessages messages. Last 10:\n$tail")
    }
}

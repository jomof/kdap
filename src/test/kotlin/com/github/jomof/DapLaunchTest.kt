package com.github.jomof

import org.json.JSONObject
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Tests that the DAP server can launch a program and run it to completion.
 *
 * Inspired by the simplest launch test in CodeLLDB's adapter test suite
 * (.codelldb-inspiration/tests/adapter.test.ts → 'run program to the end'),
 * which launches the debuggee binary and waits for the `terminated` event.
 *
 * The full DAP message sequence is:
 * 1. Client sends `initialize` → adapter responds with capabilities
 * 2. Client sends `launch` (with program path)
 * 3. Adapter sends `initialized` event (ready for breakpoint configuration)
 * 4. Client sends `configurationDone` (no breakpoints to set)
 * 5. Program runs to completion
 * 6. Adapter sends `exited` event (with exit code)
 * 7. Adapter sends `terminated` event
 *
 * The debuggee binary (from debuggee/build/debuggee, built by cmake) with no
 * arguments prints "No testcase was specified." and exits with -1 (255). This
 * is intentional — we only care that the full launch lifecycle completes.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DapLaunchTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(ConnectionMode::class)
    fun `launch program runs to terminated event`(mode: ConnectionMode) {
        val debuggee = DapTestUtils.resolveDebuggeeBinary()
        mode.connect().use { ctx ->
            runLaunchToTerminated(ctx, mode, debuggee.absolutePath)
        }
    }

    /**
     * Executes the full DAP launch lifecycle:
     *   initialize → launch → (initialized event) → configurationDone → (terminated event)
     *
     * On failure, the exception is enriched with the current phase, a log of
     * every DAP message exchanged, and server diagnostics (stderr, process state)
     * so CI failures are actionable.
     */
    private fun runLaunchToTerminated(ctx: ConnectionContext, mode: ConnectionMode, program: String) {
        val input = ctx.inputStream
        val output = ctx.outputStream
        var phase = "setup"
        // Accumulates a human-readable log of every DAP message for failure diagnostics.
        val messageLog = mutableListOf<String>()

        try {
            // 1. Initialize (may already be done as a connection handshake)
            phase = "initialize (sending request and reading response)"
            val initResponse = ctx.initializeResponse ?: run {
                messageLog.add("→ sent: initialize request (seq=1)")
                DapTestUtils.sendInitializeRequest(output)
                DapTestUtils.readDapMessage(input).also {
                    messageLog.add("← recv: initialize response")
                }
            }
            if (ctx.initializeResponse != null) {
                messageLog.add("(initialize already completed during connection handshake)")
            }
            DapTestUtils.assertValidInitializeResponse(initResponse)

            // 2. Launch (server-specific format)
            phase = "launch (sending launch request for $program)"
            messageLog.add("→ sent: launch request (seq=2, program=$program)")
            mode.serverKind.testServer.sendLaunchRequest(output, seq = 2, program = program)

            // 3. Wait for 'initialized' event (adapter is ready for breakpoint config)
            phase = "waiting for 'initialized' event"
            readEventAndLog(input, "initialized", messageLog)

            // 4. ConfigurationDone (we have no breakpoints to set)
            phase = "configurationDone"
            messageLog.add("→ sent: configurationDone request (seq=3)")
            DapTestUtils.sendConfigurationDoneRequest(output, seq = 3)

            // 5. Wait for 'terminated' event (program ran to completion)
            //    Other messages (launch response, configurationDone response, process
            //    event, exited event, thread events, module events, etc.) are skipped.
            //    macOS debuggees load 100+ dylibs → 100+ module events, so we need a
            //    generous limit here.
            phase = "waiting for 'terminated' event"
            readEventAndLog(input, "terminated", messageLog, maxMessages = 500)

            // If we get here, the full launch lifecycle completed successfully.
        } catch (e: Exception) {
            val log = messageLog.joinToString("\n  ", prefix = "  ")
            throw AssertionError(
                "[$mode] Failed during phase: $phase\n" +
                "DAP message log (${messageLog.size} entries):\n$log\n${ctx.diagnostics()}", e
            )
        }
    }

    /**
     * Reads DAP messages until [eventType] is found, logging every message to [log].
     * Failed responses are logged with their full error message.
     */
    private fun readEventAndLog(
        input: InputStream,
        eventType: String,
        log: MutableList<String>,
        maxMessages: Int = 50,
    ): String {
        repeat(maxMessages) {
            val message = DapTestUtils.readDapMessage(input)
            val json = JSONObject(message)
            val type = json.optString("type", "?")
            when (type) {
                "event" -> {
                    val event = json.optString("event")
                    if (event == eventType) {
                        log.add("← recv: event:$event (TARGET — found)")
                        return message
                    }
                    log.add("← recv: event:$event")
                }
                "response" -> {
                    val cmd = json.optString("command")
                    val success = json.optBoolean("success")
                    val msg = json.optString("message", "")
                    if (success) {
                        log.add("← recv: response:$cmd success=true")
                    } else {
                        // Failed response — include full error detail, this is critical
                        log.add("← recv: response:$cmd success=false message=\"$msg\" body=${json.optJSONObject("body")}")
                    }
                }
                else -> log.add("← recv: $type")
            }
        }
        error("No '$eventType' event within $maxMessages messages")
    }
}

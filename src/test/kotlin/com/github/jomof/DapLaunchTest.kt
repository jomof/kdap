package com.github.jomof

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.io.InputStream
import java.io.OutputStream

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
class DapLaunchTest {

    companion object {
        /**
         * Resolves the debuggee binary built by cmake. Returns null if not found.
         */
        fun resolveDebuggeeBinary(): File? {
            val cwd = File(System.getProperty("user.dir"))
            val candidates = listOf(
                File(cwd, "debuggee/build/debuggee"),
                File(cwd, "debuggee/build/debuggee.exe"),       // Windows
                File(cwd, "debuggee/build/Debug/debuggee.exe"), // MSVC multi-config
            )
            return candidates.firstOrNull { it.isFile && it.canExecute() }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ConnectionMode::class)
    fun `launch program runs to terminated event`(mode: ConnectionMode) {
        val debuggee = resolveDebuggeeBinary()
        assertNotNull(debuggee) {
            "debuggee binary not found — run: cmake -B debuggee/build debuggee && cmake --build debuggee/build"
        }
        mode.connect().use { ctx ->
            runLaunchToTerminated(ctx, mode, debuggee!!.absolutePath)
        }
    }

    /**
     * Executes the full DAP launch lifecycle:
     *   initialize → launch → (initialized event) → configurationDone → (terminated event)
     *
     * On failure, the exception is enriched with the current phase and server
     * diagnostics (stderr, process state) so CI failures are actionable.
     */
    private fun runLaunchToTerminated(ctx: ConnectionContext, mode: ConnectionMode, program: String) {
        val input = ctx.inputStream
        val output = ctx.outputStream
        var phase = "setup"

        try {
            // 1. Initialize
            phase = "initialize (sending request and reading response)"
            DapTestUtils.sendInitializeRequest(output)
            val initResponse = DapTestUtils.readDapMessage(input)
            DapTestUtils.assertValidInitializeResponse(initResponse)

            // 2. Launch (server-specific format)
            phase = "launch (sending launch request for $program)"
            mode.serverKind.testServer.sendLaunchRequest(output, seq = 2, program = program)

            // 3. Wait for 'initialized' event (adapter is ready for breakpoint config)
            phase = "waiting for 'initialized' event"
            DapTestUtils.readEventOfType(input, "initialized")

            // 4. ConfigurationDone (we have no breakpoints to set)
            phase = "configurationDone"
            DapTestUtils.sendConfigurationDoneRequest(output, seq = 3)

            // 5. Wait for 'terminated' event (program ran to completion)
            //    Other messages (launch response, configurationDone response, process
            //    event, exited event, thread events, module events, etc.) are skipped.
            //    macOS debuggees load 100+ dylibs → 100+ module events, so we need a
            //    generous limit here.
            phase = "waiting for 'terminated' event"
            DapTestUtils.readEventOfType(input, "terminated", maxMessages = 500)

            // If we get here, the full launch lifecycle completed successfully.
        } catch (e: Exception) {
            val diag = try { ctx.diagnostics() } catch (_: Exception) { "(diagnostics unavailable)" }
            throw AssertionError("[$mode] Failed during phase: $phase\n$diag", e)
        }
    }
}

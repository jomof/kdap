package com.github.jomof

import com.github.jomof.dap.messages.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tests remote launch via lldb-server with different DAP adapters.
 *
 * The sequence is:
 * 1. Start `lldb-server platform --server --listen` (TCP or unix socket)
 * 2. Connect a DAP adapter (CodeLLDB or KDAP) via stdio
 * 3. Send `initialize`
 * 4. Send `launch` with `initCommands` that select + connect to the
 *    remote platform
 * 5. Wait for `initialized` → send `configurationDone` → wait for
 *    `terminated`
 *
 * This verifies the full remote launch lifecycle locally (loopback).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DapRemoteLaunchTest {

    @Test
    fun `codelldb remote launch via lldb-server`() {
        val debuggee = DapTestUtils.resolveDebuggeeBinary()
        DapConnection.createRemoteCodeLldb(debuggee).use { conn ->
            remoteLaunchLifecycle(conn, debuggee, waitForTerminated = true)
        }
    }

    @Test
    fun `kdap remote launch via lldb-server`() {
        val debuggee = DapTestUtils.resolveDebuggeeBinary()
        val sbLogFile = File("sb-watcher.txt")
        DapConnection.createRemoteKdap(debuggee, sbLogFile).use { conn ->
            // Verify the full remote launch lifecycle — the process should
            // launch on the remote platform, run to completion, and we
            // should see exited + terminated events, just like CodeLLDB.
            remoteLaunchLifecycle(conn, debuggee, waitForTerminated = true)
        }
        println("SB call trace written to: ${sbLogFile.absolutePath}")
    }

    /**
     * Shared remote-launch protocol exercised by both adapters.
     *
     * @param waitForTerminated whether to wait for the `terminated` event.
     *   CodeLLDB sends it; lldb-dap (used by KDAP) does not in remote mode.
     */
    private fun remoteLaunchLifecycle(
        conn: DapConnection,
        debuggee: File,
        waitForTerminated: Boolean,
    ) {
        try {
            // 1. Initialize
            conn.send(InitializeRequest())
            val initResponse = conn.receive()
            require(initResponse is DapResponse && initResponse.success) {
                "Expected successful initialize response, got: $initResponse"
            }

            // 2. Launch with initCommands for remote platform
            conn.send(LaunchRequest(
                arguments = LaunchRequestArguments(
                    common = CommonLaunchFields(
                        name = "remote-test",
                        initCommands = listOf(
                            "platform select ${conn.platformName}",
                            "platform connect ${conn.platformConnectUrl}",
                        ),
                    ),
                    program = debuggee.absolutePath,
                    terminal = TerminalKind.Console,
                ),
            ))

            // 3. Wait for 'initialized' event
            conn.waitForEvent("initialized")

            // 4. configurationDone
            conn.send(ConfigurationDoneRequest())

            // 5. Wait for launch and configurationDone responses.
            //    KDAP sends these after completeLaunch finishes, so waiting
            //    ensures we capture the full SB call trace.
            conn.waitForResponse("launch")
            conn.waitForResponse("configurationDone")

            // 6. Wait for 'terminated' event (adapter-dependent)
            if (waitForTerminated) {
                conn.waitForEvent("terminated", maxMessages = 500)
            }
        } catch (e: Exception) {
            throw AssertionError("Remote launch failed\n${conn.diagnostics()}", e)
        }
    }
}

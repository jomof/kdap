package com.github.jomof

import com.github.jomof.dap.messages.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Tests that CodeLLDB can launch a debuggee via lldb-server remote
 * debugging (the "Remote launch" workflow from MANUAL.md).
 *
 * The sequence is:
 * 1. Start `lldb-server platform --server --listen 127.0.0.1:<port>`
 * 2. Connect CodeLLDB via stdio
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

                // 5. Wait for 'terminated' event
                conn.waitForEvent("terminated", maxMessages = 500)

            } catch (e: Exception) {
                throw AssertionError("Remote launch failed\n${conn.diagnostics()}", e)
            }
        }
    }
}

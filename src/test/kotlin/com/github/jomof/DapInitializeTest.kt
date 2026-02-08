package com.github.jomof

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource

/**
 * Tests that the DAP server responds to `initialize`. Runs for each connection mode
 * (our server: stdio, TCP listen, TCP connect, in-process; lldb-dap: TCP_LLDB).
 */
class DapInitializeTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(ConnectionMode::class)
    fun `initialize receives valid response with capabilities`(mode: ConnectionMode) {
        mode.connect().use { ctx ->
            val responseBody = DapTestUtils.sendInitializeAndReadResponse(ctx.inputStream, ctx.outputStream)
            DapTestUtils.assertValidInitializeResponse(responseBody)
        }
    }

    /** Exercises our server's catch block: _triggerError throws, server returns internal error. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.github.jomof.ConnectionMode#ourServerModes")
    fun `triggerError request receives expected error response`(mode: ConnectionMode) {
        mode.connect().use { ctx ->
            DapTestUtils.sendTriggerErrorRequest(ctx.outputStream)
            val responseBody = DapTestUtils.readResponseBody(ctx.inputStream)
            DapTestUtils.assertInternalErrorResponse(responseBody)
        }
    }
}

package com.github.jomof

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Tests that the DAP server responds to `initialize`. Runs the same assertion
 * for each connection mode: stdio, TCP listen, TCP connect, and in-process (pipes).
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

    /** Exercises the server's catch block: a request that throws returns a JSON-RPC internal error. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(ConnectionMode::class)
    fun `triggerError request receives internal error response`(mode: ConnectionMode) {
        mode.connect().use { ctx ->
            DapTestUtils.sendTriggerErrorRequest(ctx.outputStream)
            val responseBody = DapTestUtils.readResponseBody(ctx.inputStream)
            DapTestUtils.assertInternalErrorResponse(responseBody)
        }
    }
}

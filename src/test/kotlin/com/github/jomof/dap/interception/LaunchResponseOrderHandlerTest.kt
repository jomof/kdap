package com.github.jomof.dap.interception

import com.github.jomof.dap.messages.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LaunchResponseOrderHandler]. Verifies that launch and
 * configurationDone responses are reordered to match CodeLLDB's sequence.
 */
class LaunchResponseOrderHandlerTest {

    private val handler = LaunchResponseOrderHandler()

    private val launchResponse = DapResponse(
        seq = 1, requestSeq = 2, command = "launch", success = true,
    )
    private val configDoneResponse = DapResponse(
        seq = 3, requestSeq = 4, command = "configurationDone", success = true,
    )
    private val continuedEvent = ContinuedEvent(seq = 5, allThreadsContinued = true)

    @Test
    fun `reorders launch response, continued, and configurationDone response`() {
        // Simulate lldb-dap order: launch_resp, ..., continued, configDone_resp
        val r1 = handler.onBackendMessage(launchResponse)
        assertEquals(0, r1.size, "launch response should be buffered")

        val output = OutputEvent.console("Launching\n")
        val r2 = handler.onBackendMessage(output)
        assertEquals(1, r2.size, "output event should pass through")
        assertSame(output, r2[0])

        val r3 = handler.onBackendMessage(continuedEvent)
        assertEquals(0, r3.size, "continued event should be buffered")

        val r4 = handler.onBackendMessage(configDoneResponse)
        assertEquals(3, r4.size, "configDone should flush all three")
        assertSame(launchResponse, r4[0])
        assertSame(configDoneResponse, r4[1])
        assertSame(continuedEvent, r4[2])
    }

    @Test
    fun `configurationDone without buffered launch response passes through with continued`() {
        // No launch response was buffered (e.g., attach scenario)
        val r1 = handler.onBackendMessage(continuedEvent)
        assertEquals(0, r1.size)

        val r2 = handler.onBackendMessage(configDoneResponse)
        assertEquals(2, r2.size, "should release configDone + continued")
        assertSame(configDoneResponse, r2[0])
        assertSame(continuedEvent, r2[1])
    }

    @Test
    fun `configurationDone without buffered continued releases only responses`() {
        val r1 = handler.onBackendMessage(launchResponse)
        assertEquals(0, r1.size)

        val r2 = handler.onBackendMessage(configDoneResponse)
        assertEquals(2, r2.size, "should release launch + configDone")
        assertSame(launchResponse, r2[0])
        assertSame(configDoneResponse, r2[1])
    }

    @Test
    fun `non-buffered messages pass through unchanged`() {
        val events = listOf(
            InitializedEvent(seq = 1),
            OutputEvent.console("hello\n"),
            StoppedEvent(seq = 2),
            ExitedEvent(seq = 3, exitCode = 0),
            TerminatedEvent(seq = 4),
        )
        for (event in events) {
            val result = handler.onBackendMessage(event)
            assertEquals(1, result.size, "should pass through: ${event::class.simpleName}")
            assertSame(event, result[0])
        }
    }

    @Test
    fun `other responses pass through unchanged`() {
        val initResponse = DapResponse(
            seq = 1, requestSeq = 1, command = "initialize", success = true,
        )
        val result = handler.onBackendMessage(initResponse)
        assertEquals(1, result.size)
        assertSame(initResponse, result[0])
    }
}

package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.messages.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LaunchEventsHandler]. Verifies that launch status output
 * events ("Launching:" and "Launched process") are injected before the
 * `process` event, and only after a `launch` request has been observed.
 */
class LaunchEventsHandlerTest {

    private val handler = LaunchEventsHandler()

    @Test
    fun `observes launch request and returns Forward`() {
        val request = LaunchRequest(seq = 2, program = "/usr/bin/test")
        val action = handler.onRequest(request)
        assertInstanceOf(RequestAction.Forward::class.java, action,
            "LaunchEventsHandler should always forward requests")
    }

    @Test
    fun `injects launching and launched events before process event`() {
        handler.onRequest(LaunchRequest(seq = 2, program = "/usr/bin/test"))

        val processEvent = ProcessEvent(
            seq = 5, name = "/usr/bin/test", systemProcessId = 42,
            isLocalProcess = true, startMethod = "launch",
        )
        val result = handler.onBackendMessage(processEvent)

        assertEquals(3, result.size, "Should inject two events before process event")

        val launching = assertInstanceOf(OutputEvent::class.java, result[0])
        assertEquals("Launching: /usr/bin/test\n", launching.output)
        assertEquals("console", launching.category)

        val launched = assertInstanceOf(OutputEvent::class.java, result[1])
        assertEquals("Launched process 42 from '/usr/bin/test'\n", launched.output)
        assertEquals("console", launched.category)

        assertSame(processEvent, result[2])
    }

    @Test
    fun `does not inject launch events twice`() {
        handler.onRequest(LaunchRequest(seq = 2, program = "/usr/bin/test"))
        handler.onBackendMessage(ProcessEvent(
            seq = 5, name = "/usr/bin/test", systemProcessId = 42,
            isLocalProcess = true, startMethod = "launch",
        ))

        val secondProcess = ProcessEvent(
            seq = 10, name = "other", systemProcessId = 99,
            isLocalProcess = true, startMethod = "launch",
        )
        val result = handler.onBackendMessage(secondProcess)
        assertEquals(1, result.size, "Second process event should not trigger injection")
        assertSame(secondProcess, result[0])
    }

    @Test
    fun `process event without prior launch request does not inject`() {
        val processEvent = ProcessEvent(
            seq = 5, name = "/usr/bin/test", systemProcessId = 42,
            isLocalProcess = true, startMethod = "launch",
        )
        val result = handler.onBackendMessage(processEvent)
        assertEquals(1, result.size, "Should forward unchanged when no launch program is tracked")
        assertSame(processEvent, result[0])
    }

    @Test
    fun `non-process backend messages are forwarded unchanged`() {
        handler.onRequest(LaunchRequest(seq = 2, program = "/usr/bin/test"))
        val event = StoppedEvent(seq = 10)
        val result = handler.onBackendMessage(event)
        assertEquals(1, result.size)
        assertSame(event, result[0])
    }
}

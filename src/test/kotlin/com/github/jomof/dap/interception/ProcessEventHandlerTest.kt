package com.github.jomof.dap.interception

import com.github.jomof.dap.messages.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ProcessEventHandler]. Verifies that `process` events from
 * lldb-dap are replaced with `continued` events to match CodeLLDB's behavior.
 */
class ProcessEventHandlerTest {

    private val handler = ProcessEventHandler()

    @Test
    fun `replaces process event with continued event`() {
        val processEvent = ProcessEvent(
            seq = 5, name = "/usr/bin/test", systemProcessId = 42,
            isLocalProcess = true, startMethod = "launch",
        )
        val result = handler.onBackendMessage(processEvent)

        assertEquals(1, result.size, "Should produce exactly one event")
        val continued = assertInstanceOf(ContinuedEvent::class.java, result[0])
        assertEquals(true, continued.allThreadsContinued)
    }

    @Test
    fun `non-process events are forwarded unchanged`() {
        val event = StoppedEvent(seq = 10)
        val result = handler.onBackendMessage(event)
        assertEquals(1, result.size)
        assertSame(event, result[0])
    }

    @Test
    fun `output events are forwarded unchanged`() {
        val event = OutputEvent.console("some message\n")
        val result = handler.onBackendMessage(event)
        assertEquals(1, result.size)
        assertSame(event, result[0])
    }

    @Test
    fun `initialized event is forwarded unchanged`() {
        val event = InitializedEvent(seq = 1)
        val result = handler.onBackendMessage(event)
        assertEquals(1, result.size)
        assertSame(event, result[0])
    }

    @Test
    fun `replaces every process event (not one-shot)`() {
        val first = ProcessEvent(seq = 5, name = "a", systemProcessId = 1)
        val second = ProcessEvent(seq = 10, name = "b", systemProcessId = 2)

        val result1 = handler.onBackendMessage(first)
        val result2 = handler.onBackendMessage(second)

        assertInstanceOf(ContinuedEvent::class.java, result1[0])
        assertInstanceOf(ContinuedEvent::class.java, result2[0])
    }
}

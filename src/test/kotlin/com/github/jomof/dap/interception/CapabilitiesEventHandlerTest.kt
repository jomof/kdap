package com.github.jomof.dap.interception

import com.github.jomof.dap.messages.CapabilitiesEvent
import com.github.jomof.dap.messages.InitializedEvent
import com.github.jomof.dap.messages.OutputEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CapabilitiesEventHandlerTest {

    private val handler = CapabilitiesEventHandler()

    @Test
    fun `suppresses capabilities event`() {
        val event = CapabilitiesEvent(
            seq = 5,
            capabilities = mapOf("supportsRestartRequest" to true),
        )
        val result = handler.onBackendMessage(event)
        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `suppresses capabilities event with multiple capabilities`() {
        val event = CapabilitiesEvent(
            seq = 5,
            capabilities = mapOf(
                "supportsRestartRequest" to true,
                "supportsStepInTargetsRequest" to true,
            ),
        )
        val result = handler.onBackendMessage(event)
        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `initialized event is forwarded unchanged`() {
        val event = InitializedEvent(seq = 1)
        val result = handler.onBackendMessage(event)
        assertEquals(listOf(event), result)
    }

    @Test
    fun `output events are forwarded unchanged`() {
        val event = OutputEvent(seq = 2, category = "console", output = "hello\n")
        val result = handler.onBackendMessage(event)
        assertEquals(listOf(event), result)
    }
}

package com.github.jomof.dap

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.interception.ConsoleModeHandler
import com.github.jomof.dap.interception.TriggerErrorHandler
import com.github.jomof.dap.messages.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Compositional tests for [KdapInterceptor]. Verifies that the composed
 * handler chain works correctly end-to-end. Individual handler behavior
 * is tested in the `com.github.jomof.dap.interception` package.
 */
class KdapInterceptorTest {

    private val interceptor = KdapInterceptor()

    // ── Pass-through for non-intercepted commands ──────────────────────────

    @Test
    fun `initialize request is forwarded unchanged`() {
        val request = InitializeRequest(seq = 1)
        assertInstanceOf(RequestAction.Forward::class.java, interceptor.onRequest(request))
    }

    @Test
    fun `launch request is forwarded unchanged`() {
        val request = LaunchRequest(seq = 2, program = "/bin/ls")
        assertInstanceOf(RequestAction.Forward::class.java, interceptor.onRequest(request))
    }

    @Test
    fun `setBreakpoints request is forwarded unchanged`() {
        val request = SetBreakpointsRequest(seq = 6)
        assertInstanceOf(RequestAction.Forward::class.java, interceptor.onRequest(request))
    }

    @Test
    fun `disconnect request is forwarded unchanged`() {
        val request = DisconnectRequest(seq = 99)
        assertInstanceOf(RequestAction.Forward::class.java, interceptor.onRequest(request))
    }

    @Test
    fun `non-intercepted backend messages are forwarded unchanged`() {
        val event = StoppedEvent(seq = 10)
        val result = interceptor.onBackendMessage(event)
        assertEquals(1, result.size)
        assertSame(event, result[0])
    }

    // ── Full launch sequence (exercises multiple handlers together) ────────

    @Test
    fun `full launch sequence produces expected composed events`() {
        // 1. Launch request: observed by LaunchEventsHandler, forwarded.
        val launchAction = interceptor.onRequest(LaunchRequest(seq = 2, program = "/usr/bin/test"))
        assertInstanceOf(RequestAction.Forward::class.java, launchAction)

        // 2. Launch response from backend: buffered by LaunchResponseOrderHandler.
        val launchResponse = DapResponse(seq = 2, requestSeq = 2, command = "launch", success = true)
        val launchRespResult = interceptor.onBackendMessage(launchResponse)
        assertEquals(0, launchRespResult.size, "Launch response should be buffered")

        // 3. Initialized event from backend: ConsoleModeHandler injects
        //    console mode announcement before it.
        val initializedResult = interceptor.onBackendMessage(InitializedEvent(seq = 1))
        assertEquals(2, initializedResult.size, "Console mode should be injected before initialized")
        val consoleMode = assertInstanceOf(OutputEvent::class.java, initializedResult[0])
        assertEquals(ConsoleModeHandler.CONSOLE_MODE_MESSAGE, consoleMode.output)
        assertInstanceOf(InitializedEvent::class.java, initializedResult[1])

        // 4. Process event from backend: LaunchEventsHandler injects
        //    "Launching:" and "Launched process" before it, then
        //    ProcessEventHandler replaces the process event with continued,
        //    then LaunchResponseOrderHandler buffers the continued event.
        val processEvent = ProcessEvent(
            seq = 5, name = "/usr/bin/test", systemProcessId = 42,
            isLocalProcess = true, startMethod = "launch",
        )
        val processResult = interceptor.onBackendMessage(processEvent)
        assertEquals(2, processResult.size,
            "Two launch output events (continued is buffered)")
        val launching = assertInstanceOf(OutputEvent::class.java, processResult[0])
        assertTrue(launching.output.startsWith("Launching:"))
        val launched = assertInstanceOf(OutputEvent::class.java, processResult[1])
        assertTrue(launched.output.startsWith("Launched process"))

        // 5. ConfigurationDone response from backend: triggers release of
        //    buffered launch response, configDone response, and continued event.
        val configDoneResponse = DapResponse(seq = 6, requestSeq = 3, command = "configurationDone", success = true)
        val configDoneResult = interceptor.onBackendMessage(configDoneResponse)
        assertEquals(3, configDoneResult.size,
            "Should release launch response, configDone response, continued")
        assertSame(launchResponse, configDoneResult[0])
        assertSame(configDoneResponse, configDoneResult[1])
        val continued = assertInstanceOf(ContinuedEvent::class.java, configDoneResult[2])
        assertEquals(true, continued.allThreadsContinued)
    }

    // ── Handler interaction: evaluate + triggerError don't conflict ────────

    @Test
    fun `evaluate rewriting works alongside other handlers`() {
        val request = EvaluateRequest(seq = 5, expression = "version", context = "_command")
        val action = assertInstanceOf(RequestAction.ForwardModified::class.java, interceptor.onRequest(request))
        val modified = assertInstanceOf(EvaluateRequest::class.java, action.modifiedRequest)
        assertEquals("repl", modified.context)
    }

    @Test
    fun `_triggerError works alongside other handlers`() {
        val request = UnknownRequest(seq = 10, command = "_triggerError")
        val action = assertInstanceOf(RequestAction.Respond::class.java, interceptor.onRequest(request))
        val response = assertInstanceOf(DapResponse::class.java, action.response)
        assertFalse(response.success)
        assertEquals(TriggerErrorHandler.INTERNAL_ERROR_MESSAGE, response.message)
    }

    @Test
    fun `launch response without prior launch request is still buffered`() {
        val response = DapResponse(
            seq = 3, requestSeq = 2, command = "launch",
            success = true, body = emptyMap(),
        )
        val result = interceptor.onBackendMessage(response)
        assertEquals(0, result.size, "Launch response is buffered by LaunchResponseOrderHandler")

        // Released when configurationDone response arrives
        val configDone = DapResponse(seq = 4, requestSeq = 3, command = "configurationDone", success = true)
        val released = interceptor.onBackendMessage(configDone)
        assertEquals(2, released.size)
        assertSame(response, released[0])
        assertSame(configDone, released[1])
    }
}

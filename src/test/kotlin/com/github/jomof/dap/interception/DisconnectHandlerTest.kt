package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.debugsession.DebugSession
import com.github.jomof.dap.messages.DisconnectRequest
import com.github.jomof.dap.messages.InitializeRequest
import com.github.jomof.dap.messages.SetBreakpointsRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DisconnectHandler]. Verifies that `disconnect` requests
 * return [RequestAction.HandleAsync] and other requests pass through.
 */
class DisconnectHandlerTest {

    private val session = DebugSession()
    private val handler = DisconnectHandler(session)

    @Test
    fun `disconnect request returns HandleAsync`() {
        val request = DisconnectRequest(seq = 1)
        assertInstanceOf(RequestAction.HandleAsync::class.java, handler.onRequest(request))
    }

    @Test
    fun `initialize request is forwarded unchanged`() {
        val request = InitializeRequest(seq = 2)
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }

    @Test
    fun `non-disconnect request is forwarded unchanged`() {
        val request = SetBreakpointsRequest(seq = 3)
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }
}

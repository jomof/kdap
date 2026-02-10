package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.debugsession.DebugSession
import com.github.jomof.dap.messages.InitializeRequest
import com.github.jomof.dap.messages.LaunchRequest
import com.github.jomof.dap.messages.SetBreakpointsRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LaunchHandler]. Verifies that `launch` requests
 * return [RequestAction.HandleAsync] and other requests pass through.
 */
class LaunchHandlerTest {

    private val session = DebugSession()
    private val handler = LaunchHandler(session)

    @Test
    fun `launch request returns HandleAsync`() {
        val request = LaunchRequest(seq = 1, program = "/bin/ls")
        assertInstanceOf(RequestAction.HandleAsync::class.java, handler.onRequest(request))
    }

    @Test
    fun `initialize request is forwarded unchanged`() {
        val request = InitializeRequest(seq = 2)
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }

    @Test
    fun `non-launch request is forwarded unchanged`() {
        val request = SetBreakpointsRequest(seq = 3)
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }
}

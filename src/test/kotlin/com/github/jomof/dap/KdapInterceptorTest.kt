package com.github.jomof.dap

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.messages.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Compositional tests for [KdapInterceptor]. Verifies that the composed
 * handler chain works correctly end-to-end. Individual handler behavior
 * is tested in the `com.github.jomof.dap.interception` package.
 *
 * Launch/attach/disconnect/terminate requests return [RequestAction.HandleAsync]
 * via their respective handlers (backed by [com.github.jomof.dap.debugsession.DebugSession]).
 * Integration tests (`DapLaunchSequenceTest`, `DapAttachSequenceTest`) cover
 * the end-to-end event sequences.
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
    fun `setBreakpoints request is forwarded unchanged`() {
        val request = SetBreakpointsRequest(seq = 6)
        assertInstanceOf(RequestAction.Forward::class.java, interceptor.onRequest(request))
    }

    @Test
    fun `non-intercepted backend messages are forwarded unchanged`() {
        val event = StoppedEvent(seq = 10)
        val result = interceptor.onBackendMessage(event)
        assertEquals(1, result.size)
        assertSame(event, result[0])
    }

    // ── LaunchHandler intercepts launch/attach/disconnect/terminate ────────

    @Test
    fun `launch request returns HandleAsync`() {
        val request = LaunchRequest(seq = 2, arguments = LaunchRequestArguments(program = "/bin/ls"))
        assertInstanceOf(RequestAction.HandleAsync::class.java, interceptor.onRequest(request))
    }

    @Test
    fun `attach request returns HandleAsync`() {
        val request = AttachRequest(seq = 3)
        assertInstanceOf(RequestAction.HandleAsync::class.java, interceptor.onRequest(request))
    }

    @Test
    fun `disconnect request returns HandleAsync`() {
        val request = DisconnectRequest(seq = 99)
        assertInstanceOf(RequestAction.HandleAsync::class.java, interceptor.onRequest(request))
    }

    @Test
    fun `terminate request returns HandleAsync`() {
        val request = TerminateRequest(seq = 100)
        assertInstanceOf(RequestAction.HandleAsync::class.java, interceptor.onRequest(request))
    }

    // ── Handler interaction: evaluate rewriting ─────────────────────────────

    @Test
    fun `evaluate rewriting works alongside other handlers`() {
        val request = EvaluateRequest(seq = 5, expression = "version", context = "_command")
        val action = assertInstanceOf(RequestAction.ForwardModified::class.java, interceptor.onRequest(request))
        val modified = assertInstanceOf(EvaluateRequest::class.java, action.modifiedRequest)
        assertEquals("repl", modified.context)
    }
}

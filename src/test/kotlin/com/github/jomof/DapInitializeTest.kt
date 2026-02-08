package com.github.jomof

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource

/**
 * Tests that the DAP server responds to `initialize`. Runs for each connection mode
 * (our server: stdio, TCP listen, TCP connect, in-process; lldb-dap: TCP_LLDB; CodeLLDB: STDIO_CODELDB, TCP_CODELDB).
 *
 * Each server has an expected capabilities baseline below so current behavior is visible in the test.
 * Failures report missing keys or wrong values so we can align implementations.
 */
class DapInitializeTest {

    /** Current capabilities from our KDAP server (body.capabilities). */
    private val expectedOurCapabilitiesBaseline = """
        {}
    """.trimIndent()

    /** Current capabilities from lldb-dap (LLVM prebuilts). */
    private val expectedLldbDapInitializeCapabilitiesBaseline = """
        {
          "supportTerminateDebuggee": true,
          "supportsCompletionsRequest": true,
          "supportsConfigurationDoneRequest": true,
          "supportsDelayedStackTraceLoading": true,
          "supportsDisassembleRequest": true,
          "supportsEvaluateForHovers": true,
          "supportsExceptionInfoRequest": true,
          "supportsExceptionOptions": true,
          "supportsFunctionBreakpoints": true,
          "supportsLoadedSourcesRequest": false,
          "supportsProgressReporting": true,
          "supportsRunInTerminalRequest": true,
          "supportsSetVariable": true,
          "supportsStepBack": false,
          "supportsStepInTargetsRequest": false,
          "supportsValueFormattingOptions": true,
          "completionTriggerCharacters": [".", " ", "\t"]
        }
    """.trimIndent()

    /** Current capabilities from CodeLLDB adapter (codelldb-vsix). */
    private val expectedCodeLldbInitializeCapabilitiesBaseline = """
        {
          "supportTerminateDebuggee": true,
          "supportsCancelRequest": true,
          "supportsClipboardContext": true,
          "supportsCompletionsRequest": true,
          "supportsConditionalBreakpoints": true,
          "supportsConfigurationDoneRequest": true,
          "supportsDataBreakpoints": true,
          "supportsDataBreakpointBytes": true,
          "supportsDelayedStackTraceLoading": true,
          "supportsDisassembleRequest": true,
          "supportsEvaluateForHovers": true,
          "supportsExceptionFilterOptions": true,
          "supportsExceptionInfoRequest": true,
          "supportsFunctionBreakpoints": true,
          "supportsGotoTargetsRequest": true,
          "supportsHitConditionalBreakpoints": true,
          "supportsInstructionBreakpoints": true,
          "supportsLogPoints": true,
          "supportsModulesRequest": true,
          "supportsReadMemoryRequest": true,
          "supportsRestartRequest": true,
          "supportsSetVariable": true,
          "supportsSteppingGranularity": true,
          "supportsWriteMemoryRequest": true
        }
    """.trimIndent()

    @ParameterizedTest(name = "{0}")
    @EnumSource(ConnectionMode::class)
    fun `initialize receives valid response with capabilities`(mode: ConnectionMode) {
        mode.connect().use { ctx ->
            val responseBody = DapTestUtils.sendInitializeAndReadResponse(ctx.inputStream, ctx.outputStream)
            DapTestUtils.assertValidInitializeResponse(responseBody)
            val actualCapabilities = DapTestUtils.parseInitializeCapabilities(responseBody)
            val baseline = when (mode.serverKind) {
                ServerKind.OUR_SERVER -> expectedOurCapabilitiesBaseline
                ServerKind.LLDB_DAP -> expectedLldbDapInitializeCapabilitiesBaseline
                ServerKind.CODELDB -> expectedCodeLldbInitializeCapabilitiesBaseline
            }
            val diff = DapTestUtils.compareCapabilitiesToBaseline(actualCapabilities, baseline)
            Assertions.assertTrue(diff == null) {
                "Initialize capabilities differ from baseline ($mode):\n$diff\n\nActual capabilities:\n${actualCapabilities.toString(2)}"
            }
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

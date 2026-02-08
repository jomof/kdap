package com.github.jomof

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource

/**
 * Tests that the DAP server responds to `initialize`. Runs for each connection mode
 * (our server: stdio, TCP listen, TCP connect, in-process; lldb-dap: TCP_LLDB; CodeLLDB: STDIO_CODELDB, TCP_CODELDB).
 *
 * Each server has a complete capabilities baseline below so the full response shape is
 * documented and structural differences between server types are immediately visible.
 * Strict comparison catches both missing and unexpected keys.
 */
class DapInitializeTest {

    /** Current capabilities from our KDAP server — empty while we build out features. */
    private val expectedOurCapabilitiesBaseline = """
        {}
    """.trimIndent()

    /** Current capabilities from lldb-dap (LLVM 18.1.8 prebuilts). */
    private val expectedLldbDapInitializeCapabilitiesBaseline = """
        {
          "supportTerminateDebuggee": true,
          "supportsCompletionsRequest": true,
          "supportsConfigurationDoneRequest": true,
          "supportsConditionalBreakpoints": true,
          "supportsDelayedStackTraceLoading": true,
          "supportsDisassembleRequest": true,
          "supportsEvaluateForHovers": true,
          "supportsExceptionInfoRequest": true,
          "supportsExceptionOptions": true,
          "supportsFunctionBreakpoints": true,
          "supportsGotoTargetsRequest": false,
          "supportsHitConditionalBreakpoints": true,
          "supportsLoadedSourcesRequest": false,
          "supportsLogPoints": true,
          "supportsModulesRequest": true,
          "supportsProgressReporting": true,
          "supportsRestartFrame": false,
          "supportsRestartRequest": true,
          "supportsRunInTerminalRequest": true,
          "supportsSetVariable": true,
          "supportsStepBack": false,
          "supportsStepInTargetsRequest": false,
          "supportsValueFormattingOptions": true,
          "completionTriggerCharacters": [".", " ", "\t"],
          "exceptionBreakpointFilters": [
            { "filter": "cpp_catch",   "default": false, "label": "C++ Catch" },
            { "filter": "cpp_throw",   "default": false, "label": "C++ Throw" },
            { "filter": "objc_catch",  "default": false, "label": "Objective-C Catch" },
            { "filter": "objc_throw",  "default": false, "label": "Objective-C Throw" },
            { "filter": "swift_catch", "default": false, "label": "Swift Catch" },
            { "filter": "swift_throw", "default": false, "label": "Swift Throw" }
          ]
        }
    """.trimIndent()

    /** Current capabilities from CodeLLDB adapter (codelldb-vsix, LLDB 21.1.7-codelldb). */
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
          "supportsStepInTargetsRequest": true,
          "supportsSteppingGranularity": true,
          "supportsWriteMemoryRequest": true,
          "exceptionBreakpointFilters": [
            { "filter": "cpp_throw", "default": true,  "supportsCondition": true, "label": "C++: on throw" },
            { "filter": "cpp_catch", "default": false, "supportsCondition": true, "label": "C++: on catch" }
          ]
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
            val diff = DapTestUtils.compareJsonStrict(actualCapabilities, baseline)
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

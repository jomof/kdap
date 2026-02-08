package com.github.jomof

import com.github.jomof.dap.LldbDapProcess
import java.io.File
import java.net.ServerSocket

/**
 * Test harness for running tests against lldb-dap. Thin layer over the
 * production [LldbDapProcess] that adds test-specific concerns:
 *
 * - **Discovery override**: the `KDAP_LLDB_ROOT` environment variable lets CI
 *   point to a non-default lldb installation. Production code takes an explicit
 *   path; only tests use this fallback.
 * - **Availability check**: [isAvailable] for `assumeTrue` / skip logic.
 * - **TCP convenience**: [startTcp] picks a free port and starts lldb-dap in
 *   TCP mode, returning both the process wrapper and the port.
 *
 * Run `scripts/download-lldb.sh` to populate `prebuilts/lldb` for the current
 * platform if lldb-dap is not available.
 */
object LldbDapHarness {

    /** Default search directory relative to the project root. */
    private val DEFAULT_SEARCH_DIR = File("prebuilts/lldb")

    /**
     * Resolves the lldb-dap executable, checking `KDAP_LLDB_ROOT` first
     * (for CI), then the default `prebuilts/lldb` layout.
     */
    fun resolveLldbDapPath(): File? {
        val searchDir = System.getenv("KDAP_LLDB_ROOT")?.let { File(it) }
            ?: DEFAULT_SEARCH_DIR
        return LldbDapProcess.findLldbDap(searchDir)
    }

    /** True if lldb-dap is available for the current platform. */
    fun isAvailable(): Boolean = resolveLldbDapPath() != null

    /**
     * Starts lldb-dap in stdio mode via the production [LldbDapProcess] API.
     * @throws IllegalStateException if lldb-dap is not found.
     */
    fun start(): LldbDapProcess {
        val path = resolveLldbDapPath()
            ?: throw IllegalStateException(
                "lldb-dap not found (run scripts/download-lldb.sh or set KDAP_LLDB_ROOT)"
            )
        return LldbDapProcess.start(path)
    }

    /**
     * Starts lldb-dap in TCP mode on a free port.
     * @return the [LldbDapProcess] and the port it is listening on.
     * @throws IllegalStateException if lldb-dap is not found.
     */
    fun startTcp(): Pair<LldbDapProcess, Int> {
        val port = ServerSocket(0).use { it.localPort }
        val path = resolveLldbDapPath()
            ?: throw IllegalStateException(
                "lldb-dap not found (run scripts/download-lldb.sh or set KDAP_LLDB_ROOT)"
            )
        val lldbDap = LldbDapProcess.start(path, "-p", port.toString())
        return lldbDap to port
    }
}

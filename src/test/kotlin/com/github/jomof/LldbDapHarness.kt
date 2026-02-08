package com.github.jomof

import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Test harness for starting lldb-dap (the LLVM DAP server) so tests can run
 * against it directly. Uses [KDAP_LLDB_ROOT] or `prebuilts/lldb` + platform id.
 * Run scripts/download-lldb.sh to populate prebuilts for the current platform.
 *
 * Official lldb-dap uses -p / --port for TCP: it listens on the given port and
 * accepts one DAP connection. There is no --connection listen://...; that was
 * from a different interface. We pick a free port and start lldb-dap -p &lt;port&gt;.
 */
object LldbDapHarness {

    /**
     * Resolves the lldb-dap executable path, or null if not found.
     * Single path: prebuilts/lldb/<platform_id>/bin/lldb-dap[.exe].
     */
    fun resolveLldbDapPath(): File? {
        val root = System.getenv("KDAP_LLDB_ROOT")?.let { File(it) }
            ?: File("prebuilts/lldb")
        val platformId = platformId() ?: return null
        val binDir = File(root, "$platformId/bin")
        return when {
            File(binDir, "lldb-dap.exe").exists() -> File(binDir, "lldb-dap.exe")
            File(binDir, "lldb-dap").exists() -> File(binDir, "lldb-dap")
            else -> null
        }
    }

    /** True if lldb-dap is available for the current platform. */
    fun isAvailable(): Boolean = resolveLldbDapPath() != null

    /**
     * Starts lldb-dap with [args] (e.g. empty for stdio). Throws if not available.
     * Sets library path so lldb-dap can load liblldb (lib dir next to bin).
     */
    fun startLldbDap(vararg args: String): Process {
        val exe = resolveLldbDapPath()
            ?: throw IllegalStateException("lldb-dap not found; run scripts/download-lldb.sh or set KDAP_LLDB_ROOT")
        val binDir = exe.parentFile!!
        val platformDir = binDir.parentFile!!
        val libDir = File(platformDir, "lib")
        val cmd = mutableListOf(exe.absolutePath)
        cmd.addAll(args)
        val processBuilder = ProcessBuilder(cmd)
            .redirectErrorStream(false)
            .directory(platformDir.absoluteFile)
        val env = processBuilder.environment()
        if (!env.containsKey("TERM")) env["TERM"] = "dumb"
        // macOS: avoid setting DYLD_LIBRARY_PATH so the binary uses system libc++ (rpath for liblldb is enough).
        if (libDir.exists()) {
            val os = System.getProperty("os.name").lowercase()
            if (os.contains("linux")) {
                val libPath = libDir.absolutePath
                env["LD_LIBRARY_PATH"] = (env["LD_LIBRARY_PATH"]?.let { "$libPath:$it" } ?: libPath)
            }
        }
        return processBuilder.start()
    }

    fun stopProcess(process: Process) {
        process.destroy()
        process.waitFor(2, TimeUnit.SECONDS)
        if (process.isAlive) process.destroyForcibly()
    }

    /**
     * Starts lldb-dap in TCP mode using the official -p / --port interface.
     * Picks a free port, starts `lldb-dap -p &lt;port&gt;`, and returns (process, port).
     * Caller should use [DapProcessHarness.connectToPort] to connect (it retries until the server is listening).
     */
    fun startLldbDapTcp(): Pair<Process, Int> {
        val port = ServerSocket(0).use { it.localPort }
        val process = startLldbDap("-p", port.toString())
        return process to port
    }

    private fun platformId(): String? {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val osPart = when {
            os.contains("mac") || os.contains("darwin") -> "darwin"
            os.contains("linux") -> "linux"
            os.contains("win") -> "win32"
            else -> return null
        }
        val archPart = when (arch) {
            "aarch64", "arm64" -> "arm64"
            "x86_64", "amd64" -> "x64"
            else -> arch
        }
        return "$osPart-$archPart"
    }
}

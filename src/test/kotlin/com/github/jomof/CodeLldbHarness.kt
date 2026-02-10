package com.github.jomof

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Test harness for starting the CodeLLDB adapter (from codelldb-vsix or [KDAP_CODELDB_EXTENSION])
 * so tests can run against it. The adapter supports stdio (no args) and TCP (--port N).
 *
 * Resolves extension root: env KDAP_CODELDB_EXTENSION, or codelldb-vsix/extension relative to
 * user.dir (project root when tests run from Gradle). Adapter binary: extension/adapter/codelldb.
 */
object CodeLldbHarness {

    /**
     * Resolves the CodeLLDB extension root directory (contains adapter/, lldb/, package.json), or null if not found.
     */
    fun resolveExtensionRoot(): File? {
        val envRoot = System.getenv("KDAP_CODELDB_EXTENSION")?.let { File(it) }
        if (envRoot != null && envRoot.isDirectory) return envRoot
        val cwd = File(System.getProperty("user.dir"))
        val vsixExt = File(cwd, "codelldb-vsix/extension")
        return if (vsixExt.isDirectory) vsixExt else null
    }

    /**
     * Resolves the adapter executable (extension/adapter/codelldb or codelldb.exe on Windows).
     */
    fun resolveAdapterPath(): File? {
        val root = resolveExtensionRoot() ?: return null
        val adapterDir = File(root, "adapter")
        return when {
            File(adapterDir, "codelldb.exe").exists() -> File(adapterDir, "codelldb.exe")
            File(adapterDir, "codelldb").exists() -> File(adapterDir, "codelldb")
            else -> null
        }
    }

    /** True if the CodeLLDB adapter is available for the current platform. */
    fun isAvailable(): Boolean = resolveAdapterPath() != null

    /**
     * Starts the CodeLLDB adapter with [args] (e.g. empty for stdio, or "--port", port.toString()).
     * Working directory is the extension root so the adapter finds adapter/scripts, lldb/, etc.
     */
    fun startAdapter(vararg args: String): Process {
        val exe = resolveAdapterPath()
            ?: throw IllegalStateException("CodeLLDB adapter not found; run scripts/download-codelldb-vsix.sh or set KDAP_CODELDB_EXTENSION")
        val root = resolveExtensionRoot()!!
        val cmd = mutableListOf(exe.absolutePath)
        cmd.addAll(args.toList())
        val processBuilder = ProcessBuilder(cmd)
            .redirectErrorStream(false)
            .directory(root)
        val env = processBuilder.environment()
        if (!env.containsKey("TERM")) env["TERM"] = "dumb"
        return processBuilder.start()
    }

    fun stopProcess(process: Process) {
        process.destroy()
        process.waitFor(2, TimeUnit.SECONDS)
        if (process.isAlive) process.destroyForcibly()
    }

}

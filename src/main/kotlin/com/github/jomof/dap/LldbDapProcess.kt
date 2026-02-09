package com.github.jomof.dap

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages the lifecycle of an `lldb-dap` subprocess and provides streams for
 * DAP communication.
 *
 * The [lldbDapPath] is provided explicitly — no environment variables. Use
 * [findLldbDap] to discover the binary in a standard LLVM directory layout,
 * or construct directly when the path is already known.
 *
 * **Stdio mode** (default, no extra args): DAP messages flow over the process's
 * stdin/stdout via [inputStream] and [outputStream].
 *
 * **TCP mode** (use [startTcp]): DAP messages flow over TCP; the process's
 * stdout and stderr are available via [inputStream] and [errorStream].
 *
 * Example:
 * ```
 * val exe = LldbDapProcess.findLldbDap(File("prebuilts/lldb"))
 *     ?: error("lldb-dap not found")
 * LldbDapProcess.start(exe).use { lldb ->
 *     DapFraming.writeMessage(lldb.outputStream, initializeRequest)
 *     val response = DapFraming.readMessage(lldb.inputStream)
 * }
 * ```
 */
class LldbDapProcess private constructor(
    private val process: Process
) : AutoCloseable {

    /** Reads from lldb-dap's stdout (DAP messages in stdio mode). */
    val inputStream: InputStream get() = process.inputStream

    /** Writes to lldb-dap's stdin (DAP messages in stdio mode). */
    val outputStream: OutputStream get() = process.outputStream

    /** Reads from lldb-dap's stderr (diagnostics and logging). */
    val errorStream: InputStream get() = process.errorStream

    /** True if the lldb-dap process is still running. */
    val isAlive: Boolean get() = process.isAlive

    /** The process ID, useful for diagnostics. */
    val pid: Long get() = process.pid()

    /**
     * The exit code, or throws if the process has not yet terminated.
     * Check [isAlive] first.
     */
    val exitValue: Int get() = process.exitValue()

    /**
     * Returns a human-readable summary of the lldb-dap process state:
     * alive/exited status, PID, and exit code.
     *
     * This intentionally does **not** capture stderr — callers decide
     * whether and how to buffer stderr (see [errorStream]).
     */
    fun diagnostics(): String = buildString {
        if (isAlive) {
            appendLine("lldb-dap: alive (pid $pid)")
        } else {
            appendLine("lldb-dap: exited with code $exitValue")
        }
    }

    /**
     * Gracefully stops the lldb-dap process. Waits up to 2 seconds for
     * orderly shutdown before forcing termination.
     */
    override fun close() {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }

    companion object {
        /**
         * Starts `lldb-dap` in TCP listen mode on [port]. Automatically detects
         * the correct CLI syntax:
         * - LLVM >= 19: `--connection listen://localhost:<port>`
         * - LLVM <= 18: `-p <port>`
         *
         * @param lldbDapPath absolute or relative path to the `lldb-dap` executable.
         * @param port the TCP port to listen on.
         * @return the running [LldbDapProcess]. DAP messages flow over TCP, not
         *   stdin/stdout; use [inputStream]/[errorStream] for diagnostic capture.
         */
        fun startTcp(lldbDapPath: File, port: Int): LldbDapProcess {
            val args = tcpListenArgs(lldbDapPath, port)
            return start(lldbDapPath, *args.toTypedArray())
        }

        /**
         * Starts `lldb-dap` as a subprocess. The environment is automatically
         * configured based on the directory layout next to [lldbDapPath]
         * (library paths for `liblldb`, Python site-packages on Linux, etc.).
         *
         * For stdio mode, pass no extra [args]. For TCP mode, prefer [startTcp]
         * which handles CLI version differences automatically.
         *
         * @param lldbDapPath absolute or relative path to the `lldb-dap` executable.
         * @param args additional command-line arguments (empty for stdio mode).
         * @throws IllegalArgumentException if [lldbDapPath] does not exist.
         */
        fun start(lldbDapPath: File, vararg args: String): LldbDapProcess {
            require(lldbDapPath.isFile) {
                "lldb-dap executable not found: $lldbDapPath"
            }
            val binDir = lldbDapPath.parentFile
                ?: error("lldb-dap path has no parent directory: $lldbDapPath")
            val platformDir = binDir.parentFile
                ?: error("lldb-dap bin directory has no parent: $binDir")
            val libDir = File(platformDir, "lib")

            val cmd = mutableListOf(lldbDapPath.absolutePath)
            cmd.addAll(args)

            val pb = ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .directory(platformDir.absoluteFile)

            configureEnvironment(pb.environment(), platformDir, libDir)

            return LldbDapProcess(pb.start())
        }

        /**
         * Discovers the `lldb-dap` binary under [searchDir] using the standard
         * LLVM prebuilts layout:
         *
         *     <searchDir>/<platformId>/bin/lldb-dap[.exe]
         *
         * @param searchDir the root directory containing platform subdirectories.
         * @param platformId the platform identifier (e.g. `"darwin-arm64"`, `"linux-x64"`).
         *   Defaults to [currentPlatformId] for the running JVM.
         * @return the `lldb-dap` [File] if found, or `null`.
         */
        fun findLldbDap(
            searchDir: File,
            platformId: String = currentPlatformId()
                ?: error("Unsupported platform: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        ): File? {
            val binDir = File(searchDir, "$platformId/bin")
            return sequenceOf("lldb-dap.exe", "lldb-dap")
                .map { File(binDir, it) }
                .firstOrNull { it.isFile }
        }

        /**
         * Returns the platform identifier for the current OS and architecture
         * (e.g. `"darwin-arm64"`, `"linux-x64"`, `"win32-x64"`), or `null` if
         * the platform is not recognized.
         *
         * This matches the directory names used by LLVM release downloads and
         * the `scripts/download-lldb.sh` script.
         */
        fun currentPlatformId(): String? {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            val osPart = when {
                "mac" in os || "darwin" in os -> "darwin"
                "linux" in os -> "linux"
                "win" in os -> "win32"
                else -> return null
            }
            val archPart = when (arch) {
                "aarch64", "arm64" -> "arm64"
                "x86_64", "amd64" -> "x64"
                else -> arch
            }
            return "$osPart-$archPart"
        }

        /**
         * Returns the command-line arguments for starting lldb-dap in TCP listen
         * mode. Detects the CLI flavor by inspecting `--help` output:
         * - LLVM >= 19 uses `--connection listen://localhost:<port>`
         * - LLVM <= 18 uses `-p <port>`
         */
        private fun tcpListenArgs(lldbDapPath: File, port: Int): List<String> {
            val helpProcess = ProcessBuilder(lldbDapPath.absolutePath, "--help")
                .redirectErrorStream(true)
                .start()
            val helpOutput = helpProcess.inputStream.bufferedReader().readText()
            helpProcess.waitFor(5, TimeUnit.SECONDS)
            return if ("--connection" in helpOutput) {
                listOf("--connection", "listen://localhost:$port")
            } else {
                listOf("-p", port.toString())
            }
        }

        /**
         * Configures environment variables needed for lldb-dap to locate its
         * dependencies (liblldb, Python packages, etc.) based on the standard
         * LLVM directory layout.
         *
         * Throws [IllegalStateException] if the expected directory layout is
         * broken — failing fast here instead of letting lldb-dap crash later
         * with a cryptic "cannot load liblldb" message.
         */
        private fun configureEnvironment(
            env: MutableMap<String, String>,
            platformDir: File,
            libDir: File
        ) {
            // Prevent interactive prompts in terminal-unaware environments.
            if (!env.containsKey("TERM")) env["TERM"] = "dumb"

            val os = System.getProperty("os.name").lowercase()
            when {
                // Linux: set LD_LIBRARY_PATH so the dynamic linker finds liblldb.so.
                // Also set PYTHONPATH if the prebuilts include Python packages.
                // macOS: do NOT set DYLD_LIBRARY_PATH — the binary's rpath handles it.
                "linux" in os -> {
                    check(libDir.isDirectory) {
                        "LLVM lib directory not found: $libDir — " +
                            "the LLVM installation appears incomplete (run scripts/download-lldb.sh)"
                    }
                    val libPath = libDir.absolutePath
                    env["LD_LIBRARY_PATH"] = env["LD_LIBRARY_PATH"]
                        ?.let { "$libPath:$it" }
                        ?: libPath
                    // Python site-packages are optional — some LLVM builds exclude Python.
                    val pythonSite = File(platformDir, "local/lib/python3.10/dist-packages")
                    if (pythonSite.isDirectory) {
                        env["PYTHONPATH"] = pythonSite.absolutePath
                    }
                }
                // Windows: prepend bin/ to PATH so lldb-dap.exe finds liblldb.dll.
                // DLLs live in bin/ alongside the executable, not in lib/.
                "win" in os -> {
                    val binDir = File(platformDir, "bin")
                    check(binDir.isDirectory) {
                        "LLVM bin directory not found: $binDir — " +
                            "the LLVM installation appears incomplete (run scripts/download-lldb.sh)"
                    }
                    env["PATH"] = env["PATH"]
                        ?.let { "${binDir.absolutePath};$it" }
                        ?: binDir.absolutePath
                }
            }
        }
    }
}

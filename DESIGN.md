# KDAP Design Document

A feature-rich Debug Adapter Protocol (DAP) implementation in Kotlin, built as a **decorator** over the baseline [lldb-dap](https://lldb.llvm.org/use/lldbdap.html) from the LLVM project. The client (IDE/editor) talks only to KDAP; lldb-dap is an implementation detail. The primary scenario is **remote debugging** (remote-forward: lldb-server on target, KDAP + lldb-dap on host), with full local launch/attach support for parity.

---

## 1. Dependencies: LLDB, lldb-server, lldb-dap, and CI

### 1.1 What we need

| Artifact | Role | Notes |
|----------|------|--------|
| **lldb-dap** | Baseline DAP server we wrap | LLVM repo: `lldb/tools/lldb-dap`. Speaks DAP over stdio. |
| **lldb-server** | Remote debug server on target | For remote-forward: runs on device/remote host. |
| **liblldb** (e.g. `liblldb.so` / `LLDB.dylib` / `liblldb.dll`) | Used by lldb-dap at runtime | Included in the official LLVM release. |

We **download the official LLVM release** for the current platform and extract only `bin/lldb-dap`, `bin/lldb-server`, and `lib/` into **`prebuilts/lldb/<platform-id>/`** per §1.3 so that KDAP can run lldb-dap and tests can use lldb-server.

### 1.2 Downloading LLDB prebuilts (implemented)

We **download** the official [LLVM release](https://github.com/llvm/llvm-project/releases) for the current platform and extract only the files we need.

- **Script**: Run **`./scripts/download-lldb.sh`** from the project root (bash; works on Linux, macOS, and Windows via Git Bash). No arguments; use env vars to override.
- **Env vars**: `LLVM_VERSION` (default: `21.1.8`), `PLATFORM_ID` (default: auto-detected from OS/arch).
- **Download location (gitignored)**: Archives are downloaded to **`.lldb-download/`**. The script skips download if the archive is already present.
- **Extraction**: Only **`bin/lldb-dap`**, **`bin/lldb-server`**, and **`lib/`** are extracted (no full unpack of the tarball). Output goes to **`prebuilts/lldb/<platform-id>/`** (source-controlled).
- **Platforms**: `darwin-arm64`, `darwin-x64`, `linux-x64`, `linux-arm64`, `win32-x64`. The script maps OS/arch to the matching LLVM release archive URL.

### 1.3 Layout and discovery (multi-platform)

- The layout must **accommodate multiple target platforms** (OS + architecture), so that a single root can hold binaries for e.g. Linux x64/arm64, macOS x64/arm64, Windows x64, and we choose the right one at runtime.
- **Directory structure** (under `$KDAP_LLDB_ROOT` or `lldb/` in repo/artifacts):
  - One subdirectory per platform, keyed by a **platform id** (e.g. `linux-x64`, `linux-arm64`, `darwin-x64`, `darwin-arm64`, `win32-x64`). Align with common conventions (e.g. typical IDE/editor platform identifiers, or LLVM triple abbreviations).
  - Under each platform dir, the same structure:
    - `<platform-id>/bin/lldb-dap` (or `lldb-dap.exe` on Windows)
    - `<platform-id>/bin/lldb-server` (or `lldb-server.exe` on Windows) for local/CI “remote” tests
    - `<platform-id>/lib/liblldb.*` (`.so` / `.dylib` / `.dll` as appropriate) if lldb-dap expects it next to the binary.
  - Example: `$KDAP_LLDB_ROOT/darwin-arm64/bin/lldb-dap`, `$KDAP_LLDB_ROOT/linux-x64/bin/lldb-server`, etc.
- **At runtime**: KDAP resolves the **current** platform id (e.g. from `os.name`/`os.arch` or a single env var `KDAP_PLATFORM`) and looks under `$KDAP_LLDB_ROOT/<platform-id>/`. No hardcoded absolute paths; use the host’s path separator and executable naming (e.g. `.exe` on Windows only).
- **Discovery**: Env var `KDAP_LLDB_ROOT` (points at the root that contains platform-id subdirs) or a config file path; fallback to a well-known location relative to the KDAP distribution. The **canonical location** is **`prebuilts/lldb/`** (source-controlled); the download script extracts the needed binaries there.

### 1.4 CI (GitHub Actions) (implemented)

- **Workflow**: `.github/workflows/ci.yml` runs on push/PR to `main` and on workflow_dispatch. Two jobs:
  - **assemble**: Runs on `ubuntu-latest`; runs `./gradlew assemble` to verify the Kotlin project builds.
  - **build**: Matrix job that (1) runs **`scripts/download-lldb.sh`** with `PLATFORM_ID` and `LLVM_VERSION`, (2) verifies the prebuilts layout, (3) runs `./gradlew test`.
- **Matrix**: `ubuntu-latest` (linux-x64), `macos-latest` (darwin-arm64), `windows-latest` (win32-x64). Each build job sets `PLATFORM_ID`; the script downloads (if not cached in the runner’s `.lldb-download/`) and extracts into `prebuilts/lldb/<platform_id>/`. A “Verify prebuilts layout” step checks that `lldb-dap` and `lldb-server` are present.
- **Versioning**: The workflow sets `LLVM_VERSION=21.1.8`. Update in the workflow and in the script when upgrading.
- **Dependency updates**: `.github/dependabot.yml` configures weekly updates for the Gradle and GitHub Actions ecosystems.

### 1.5 Summary table

| Concern | Approach |
|---------|----------|
| Source of binaries | Official LLVM release tarballs; download per platform |
| Primary artifact | lldb-dap, lldb-server, lib/ extracted into **`prebuilts/lldb/<platform-id>/`** (source-controlled) per §1.3 |
| Download script | `scripts/download-lldb.sh`; env: `LLVM_VERSION`, `PLATFORM_ID`; downloads to `.lldb-download/` (gitignored); extracts only bin + lib to prebuilts |
| Runtime deps | lldb-dap’s own deps (e.g. liblldb); we only spawn the process |
| Remote | lldb-server from the same release |
| CI | `.github/workflows/ci.yml`: assemble job (Gradle); build job runs download script per matrix, verifies prebuilts, runs Gradle test |
| Versioning | `LLVM_VERSION=21.1.8` in workflow and script; update in both when upgrading |

---

## 2. Features

### 2.1 Inspiration and target: CodeLLDB

We use [CodeLLDB](https://github.com/vadimcn/codelldb) (see `.codelldb-inspiration/`) as the feature and UX reference:

- **Reference code**: `.codelldb-inspiration/src` (Rust adapter, DAP session, debug session, launch/attach, breakpoints, variables, expressions, disassembly, etc.) and `.codelldb-inspiration/tests` (DAP tests using a test client library to drive the adapter over stdio).
- **Reference manual**: `.codelldb-inspiration/MANUAL.md` (launch/attach, remote, reverse debugging, expressions, formatting, excluded callers, etc.).

We aim for **competitive parity** with CodeLLDB for C++ and Rust, with **launch** and **launch remote** (remote-forward with lldb-server) as first-class scenarios.

### 2.2 Implementation order (MVP first)

Rough grouping in implementation order, with emphasis on reaching a usable MVP quickly.

**Phase 0 – Foundation**

- DAP transport: stdio server (accept DAP JSON-RPC, maintain request/response/event ordering).
- **Decorator pipeline**: Accept request → optionally augment → send one or more requests to lldb-dap → collect responses → optionally merge/augment → send response back. See [Layering strategy](#4-layering-strategy) below.
- **Passthrough**: For every request we do not yet handle, forward to lldb-dap and return its response (or a clear “not supported” if we choose not to forward).
- Lifecycle: initialize, launch/attach (basic), configurationDone, terminate/disconnect; ensure lldb-dap process is started and stopped correctly.

**Phase 1 – MVP (minimal useful session)**

- **Launch (local)**: `program`, `args`, `cwd`, `env`; ensure lldb-dap launch args are correctly translated and that we report initialized/terminated/exited.
- **Attach (local)**: `pid` (or equivalent); same translation and lifecycle.
- **Breakpoints**: setBreakpoints (source), setFunctionBreakpoints, setExceptionBreakpoints; pass through or map to lldb-dap; preserve verified/line mapping in responses.
- **Execution**: continue, next, stepIn, stepOut, pause; pass through with correct thread/frame context where needed.
- **Stack & scopes**: threads, stackTrace, scopes, variables; pass through or lightly adapt (e.g. path normalization).
- **Basic evaluate**: evaluate (REPL/watch/hover) – pass through first; enhance later (e.g. expression language selection).

MVP is “run/attach, set breakpoints, step, see stack and variables, evaluate in console” for C/C++ (and optionally Rust if lldb-dap handles it).

**Phase 2 – Remote and robustness**

- **Launch remote (remote-forward)**: User config with `initCommands` (e.g. `platform select remote-linux`, `platform connect connect://host:port`) and optional `preRunCommands`; we ensure lldb-dap is invoked so that it uses the same init/preRun; `program` is local path, lldb-dap/LLDB copies to remote via platform. Design so that “remote” is a configuration variant, not a separate code path for core DAP requests.
- **Attach remote**: Same idea: targetCreateCommands / processCreateCommands (e.g. `gdb-remote host:port`) so that lldb-dap attaches to lldb-server or gdbserver.
- **Source path mapping**: sourceMap / relativePathBase; translate into lldb-dap or LLDB settings (e.g. `target.source-map`) in initCommands or preRunCommands so that paths match what the client expects.
- **Stability**: Timeouts, cancellation (DAP cancel request), and clear error messages when lldb-dap or the debuggee misbehaves.

**Phase 3 – Expression and UX parity**

- **Expressions**: Multiple evaluators (simple vs native vs Python if available); expression prefixes in evaluate (e.g. `?`, `/nat`, `/py`); hit conditions and logpoint expressions.
- **Formatting**: Display format (hex, decimal, etc.) and “toggle pointee summaries”; pass through or implement via adapter settings and translate to LLDB commands/settings.
- **Data breakpoints**: dataBreakpointInfo, setDataBreakpoints; pass through to lldb-dap if it supports, else implement by translating to LLDB watchpoints and mapping back to DAP.
- **Disassembly**: disassemble, instruction breakpoints; “show disassembly” mode (auto/always/never) and instruction-level stepping when in disassembly view.

**Phase 4 – CodeLLDB-level extras**

- **Excluded callers**: Custom request/response (e.g. `_excludeCaller`, `_setExcludedCallers`); maintain set of excluded callers and filter breakpoint stops by call stack.
- **Symbols**: Custom `_symbols` request for “Search Symbols” in the client UI.
- **Restart / restartFrame**: restart (re-run with same config), restartFrame if lldb-dap or LLDB supports.
- **Reverse debugging**: reverseContinue, stepBack; pass through when backend supports (e.g. rr).
- **Core dump / post-mortem**: Attach with target create `-c core` and processCreateCommands `[]`.
- **Graceful shutdown**: Optional signal or commands before terminate (e.g. SIGTERM) so the debuggee can exit cleanly.
- **Cargo / Rust**: If we want Cargo-based launch configs (e.g. “cargo” program attribute), implement in the adapter (resolve binary from Cargo) and then launch via existing launch path.
- **Python scripting**: Optional Python bridge for advanced scripting; lower priority than core C++/Rust debugging.

### 2.3 DAP features CodeLLDB adds beyond baseline lldb-dap

Below is a concise list of DAP-related features and enhancements that CodeLLDB provides and that we should eventually support (for parity), beyond what a minimal lldb-dap setup typically exposes or what the standard DAP spec mandates. This is the “competitive parity” checklist.

- **Launch**
  - Rich launch options: `env`, `envFile`, `stdio` redirection, `terminal` (console / integrated / external), `stopOnEntry`, `targetCreateCommands`, `processCreateCommands`, `preRunCommands`, `postRunCommands`, `preTerminateCommands`, `exitCommands`, `gracefulShutdown`.
  - Cargo-based launch (Rust): resolve binary from Cargo and launch.
  - Source path: `sourceMap`, `relativePathBase`; `breakpointMode` (path vs file).
- **Attach**
  - `waitFor`, `stopOnEntry`, `targetCreateCommands`, `processCreateCommands`; pick-process UX (e.g. command-style variable substitution) can be client-side, but adapter must support attach by pid and by “create then attach” commands.
- **Remote**
  - Remote launch via `platform select` + `platform connect`; remote attach via `gdb-remote` (or equivalent); preRunCommands for `platform put-file`, `platform shell`, etc.
- **Breakpoints**
  - Regex function breakpoints (`/re <regex>`), conditional breakpoints (expression), logpoints (expression in message), hit condition (e.g. `% N`).
  - Data breakpoints (watchpoints) with dataBreakpointInfo / setDataBreakpoints.
  - Exception breakpoints with filters and optional conditions.
- **Execution**
  - stepInTargets / stepIn(targetId); stepBack / reverseContinue for reverse debugging.
  - Excluded callers: custom protocol to “exclude caller” so that a breakpoint does not stop when a given frame is in the stack.
- **Variables / expressions**
  - Multiple expression evaluators (simple, native, Python) and prefixes in evaluate.
  - Format suffixes in expressions (e.g. `,x`, `,x[10]`) and default display format (Display Format command).
  - SetVariable support.
  - “Pointee summaries” vs raw pointer value (toggle).
- **UI / client integration**
  - Disassembly view: auto / always / never; instruction-level stepping when in disassembly.
  - Completions (completions request) for DEBUG CONSOLE.
  - Goto targets / goto (run to cursor).
  - readMemory / writeMemory; View Memory command.
  - Modules (modules request) and loaded modules view.
  - Restart and restartFrame.
- **Custom requests / events**
  - `_adapterSettings`: update display format, disassembly mode, deref pointers, console mode, etc.
  - `_symbols`: symbol search.
  - `_excludeCaller` / `_setExcludedCallers`: excluded callers.
  - `_pythonMessage`: Python scripting bridge.
- **Robustness and polish**
  - Cancellation (cancel request) for long-running requests (evaluate, variables, scopes).
  - Timeouts (e.g. evaluation timeout, summary timeout).
  - Clear errors and console messages; “nofail” style for command sequences.

Implementing all of the above is post-MVP; the list defines the target for “CodeLLDB parity.”

---

## 3. Testability

### 3.1 Design for testability

- **Single process under test**: The “unit” we test is the KDAP server (our Kotlin process). It may spawn lldb-dap as a child; in tests we can either use a real lldb-dap or a **mock DAP peer** that speaks DAP and simulates lldb-dap responses. Prefer starting with real lldb-dap for integration tests and adding a mock only if needed for fast unit tests (e.g. for decorator logic without a real debugger).
- **DAP test client**: Use a DAP client that connects to our server (stdio or TCP) and sends requests/asserts on responses and events. Options:
  - **DAP test client library** (e.g. TypeScript/Node or Kotlin): Start KDAP with stdio, connect a socket to it if KDAP supports “listen on port” for tests, or use the library’s ability to drive a debug adapter via streams. CodeLLDB’s tests (`.codelldb-inspiration/tests`) use this pattern—e.g. `DebugClient`, `launchRequest`, `waitForEvent` in `testUtils.ts` and `adapter.test.ts`—with a client that speaks DAP and can target any DAP server, not a specific IDE.
  - **Kotlin/JVM test**: Implement a small DAP client in Kotlin that sends JSON-RPC over a stream to KDAP; run under JUnit. Gives us tests in the same repo and language as the server.
- **Test matrix**: Run the same DAP tests against:
  - **KDAP** (our server wrapping lldb-dap),
  - **lldb-dap alone** (optional baseline): to capture “expected” behavior and regressions in lldb-dap itself.

### 3.2 Launching KDAP in tests

- **Stdio**: KDAP’s default mode: stdin/stdout for DAP. Test harness spawns KDAP process and connects its stdin/stdout to the test client. No port needed.
- **TCP (optional)**: If we add “listen on port” or “connect to port” for clients or external tools, tests can connect to KDAP over TCP (e.g. “connect to 127.0.0.1:port”). CodeLLDB uses this for “reverse connect” (e.g. `DEBUG_SERVER=port`) so the test starts a server and the adapter connects to it; we can do the same for tests.
- **Environment**: Set `KDAP_LLDB_ROOT` (or equivalent) so KDAP finds lldb-dap; in CI, this points to the unpacked LLDB package from the “Setup LLDB” step.

### 3.3 Launching lldb-dap (for KDAP’s use)

- KDAP spawns lldb-dap as a subprocess (e.g. `$KDAP_LLDB_ROOT/bin/lldb-dap` with appropriate args). No separate “launch lldb-dap for tests” beyond ensuring it’s on the path or under `KDAP_LLDB_ROOT`; the process under test is KDAP.
- For **remote** tests: we need lldb-server (or gdbserver) running. Options:
  - **Same machine**: Start `lldb-server platform --server --listen *:port` (or gdbserver) on the CI runner; tests use launch config with `initCommands` / `processCreateCommands` to connect to `127.0.0.1:port`. No second machine required.
  - **Docker (optional)**: Run lldb-server in a container and connect from the host; useful for testing “real” remote path and path mapping.

### 3.4 Test debuggees (C/C++ and Rust)

- **C/C++**: Small executables with predictable behavior (e.g. main, a few functions, breakpoint-friendly lines). Build with debug info (`-g`, DWARF). Optionally use CMake (or Gradle + a C++ plugin) in repo:
  - `test/debuggee/cpp/` (or `samples/cpp/`): one or more sources; CMakeLists.txt or Gradle that produces `debuggee` (or similar) in `build/` or `out/`. Reuse the idea of CodeLLDB’s `debuggee/cpp` (e.g. `debuggee.cpp`, shared lib, different path/remote path samples) for path mapping and remote tests.
- **Rust**: Small binary (e.g. `test/debuggee/rust/` or `samples/rust/` with Cargo.toml); build with `cargo build` (debug). Include at least one test that launches/attaches to this binary and sets a breakpoint so we validate Rust debug info path.
- **CI**: In GitHub Actions, install a C++ compiler and Rust toolchain; build debuggees as a step (or as a separate job that publishes artifacts); then run DAP tests that point `program` at the built binary. For remote tests, start lldb-server (or gdbserver) and run the same debuggee on the “remote” (localhost) side.

### 3.5 Summary

| What | How |
|------|-----|
| **Test the DAP server** | KDAP process under test; DAP client (TypeScript or Kotlin) drives it over stdio (or TCP). |
| **Test with real backend** | KDAP spawns lldb-dap; use real lldb-dap in integration tests. |
| **Remote tests** | lldb-server (or gdbserver) on same host; launch config with platform connect or gdb-remote to 127.0.0.1. |
| **Debuggees** | C/C++ and Rust samples in repo; built in CI; used by integration tests. |
| **Baseline (optional)** | Same tests against “raw” lldb-dap to document and guard baseline behavior. |

---

## 4. Layering strategy (decorator)

### 4.1 Role of KDAP

- The **client** (host editor, IDE, or other DAP client) talks **only** to KDAP (single DAP endpoint).
- **lldb-dap** is an implementation detail: KDAP may start one lldb-dap process per session and forward DAP messages to it, or aggregate multiple backends later.

### 4.2 Message flow

- **Request from client** → KDAP:
  1. **Parse and classify**: e.g. initialize, launch, setBreakpoints, evaluate, etc.
  2. **Decide**: Handle ourselves, transform and forward to lldb-dap, or pass through unchanged.
  3. **Execute**:
     - **Handle ourselves**: e.g. adapter-specific settings, excluded callers, symbol search; respond from KDAP.
     - **Transform + forward**: Build one or more DAP requests for lldb-dap (e.g. map our launch args to lldb-dap’s, add initCommands from our config), send them, wait for responses.
     - **Pass through**: Send the same request to lldb-dap, wait for response.
  4. **Respond**: Merge or augment lldb-dap responses if needed (e.g. add capabilities, fix path formats), then send a single response back to the client. Preserve `request_seq` and `seq` so the client’s in-flight request matches our response.

- **Events from lldb-dap** → KDAP → client:
  - Forward events (initialized, stopped, continued, exited, terminated, output, etc.); optionally augment or filter (e.g. excluded callers: suppress a stopped event if the stop matches an excluded-caller rule and inject a continue).

### 4.3 Requests we don’t support yet

- **Option A – Pass through**: For any request we don’t explicitly handle, forward it to lldb-dap and return its response (or error). Pro: client always gets “best effort” from lldb-dap. Con: we may expose lldb-dap quirks or capabilities we’d rather hide.
- **Option B – Explicit allowlist**: Only forward requests we have explicitly implemented (or explicitly passthrough); respond with “not supported” for others. Pro: clear contract. Con: we must maintain the list and may lag behind new DAP requests.

**Recommendation**: **Default to pass-through** for unknown requests so that we don’t block the client. Optionally log “unsupported” or “passthrough” for metrics. We can later tighten to an allowlist for specific request types if we want to hide or replace lldb-dap behavior.

### 4.4 Capabilities

- **initialize**: We may return a merged capability set: our own (e.g. supportsExcludeCallers, supportsSymbols) plus what we get from lldb-dap (after we forward initialize to it, if we do). If we don’t forward initialize to lldb-dap and instead drive it ourselves, we must still present a coherent capability set to the client.

### 4.5 Concurrency and ordering

- DAP is request/response + events. Preserve order: responses must match request order when the client sends multiple requests; events can be interleaved but should be forwarded in order. If we send multiple requests to lldb-dap for one client request (e.g. set breakpoints then continue), we need to sequence them and then return one combined response (or the last one) as specified by our design for that feature.

---

## 5. Open questions and big design decisions

### 5.1 Architecture

- **One lldb-dap process per session**: Assumed for simplicity; one KDAP session = one lldb-dap process. Confirm whether we need multiple concurrent sessions (multiple debuggees) in one KDAP process; if so, we need a clear session id → lldb-dap mapping.
- **Process lifecycle**: Who starts lldb-dap? On first `launch` or `attach` (after `initialize` and `launch`/`attach` request), KDAP starts lldb-dap and forwards the request. On `disconnect`/`terminate`, KDAP terminates lldb-dap. Edge cases: client disconnects without disconnect request; lldb-dap crashes (we should report an error and clean up).

### 5.2 Transport and deployment

- **Stdio vs TCP**: Stdio is standard for DAP (many clients launch the adapter with stdio). TCP “listen” or “connect” is useful for tests and for remote or tool-driven connections. Decide if we support both from day one or add TCP later.
- **Packaging**: How we ship KDAP: standalone JAR + script that sets `KDAP_LLDB_ROOT` and runs `java -jar kdap.jar`; or a native image (GraalVM) for faster startup; or a distribution that bundles a specific lldb-dap build. Affects CI and release process.

### 5.3 Versioning and compatibility

- **DAP version**: Which DAP schema version we claim (e.g. from `debugAdapterProtocol.json`). Stay close to what lldb-dap and CodeLLDB use so that the same clients work.
- **lldb-dap version**: Pin one (or a range) of lldb-dap/LLDB versions we test against; document in README. When to bump: when we need a fix or a new feature from a newer lldb-dap.

### 5.4 Error handling and observability

- **Logging**: Structured logs (e.g. JSON) for debugging; log level configurable (env or config). Avoid logging sensitive data (e.g. full launch config with env vars).
- **Errors**: Map lldb-dap errors to DAP error responses with clear messages; consider `showUser` for user-facing vs internal errors.

### 5.5 Security (remote / multi-tenant)

- **Remote**: When we support “connect to remote lldb-server”, we don’t accept arbitrary connections from the internet in the default config; the client runs KDAP and points it at a user-specified host:port. No built-in auth in DAP; if we add a “server mode” where a client connects to a shared KDAP instance, we’d need auth and transport security (TLS) later.

### 5.6 Configuration and extensibility

- **Launch/attach config**: We’ll extend the standard DAP launch/attach arguments with our own fields (e.g. `sourceMap`, `initCommands`, `preRunCommands`). Document these in a schema or MANUAL-style doc and keep them aligned with CodeLLDB where we want parity.
- **Adapter-specific requests**: CodeLLDB uses `_adapterSettings`, `_symbols`, `_excludeCaller`, `_setExcludedCallers`, `_pythonMessage`. We can adopt the same names for parity or prefix with `_kdap`; either way, document and implement in the decorator layer.

### 5.7 Summary of open decisions

| Area | Decision to make |
|------|-------------------|
| Session model | One lldb-dap per session; confirm multi-session need. |
| Transport | Stdio first; add TCP when needed for tests/remote. |
| Unknown requests | Pass through by default; optional allowlist later. |
| Packaging | JAR vs native image vs bundled dist; document in README. |
| LLVM/LLDB version | Pin to an LLVM release tag; CI builds from that tag. |
| Custom requests | Names and semantics for excluded callers, symbols, settings. |

---

## 6. Document status

This is an initial design for iteration.

**Implementation status**

- **§1 Dependencies and CI**: Implemented. LLDB prebuilts are downloaded via `scripts/download-lldb.sh` (official LLVM release per platform); output goes to `prebuilts/lldb/<platform-id>/`. CI (`.github/workflows/ci.yml`) runs Gradle assemble, a matrix download + test job, and Gradle test. Dependabot is configured for Gradle and GitHub Actions.
- **§2–§5**: Design only; decorator pipeline and MVP features are not yet implemented.

**Next priorities**

1. **Decorator pipeline**: Implement the request/response/event forwarding and passthrough so that “run lldb-dap behind KDAP” works end-to-end.
2. **MVP features**: Launch (local), breakpoints, execution, stack/variables, basic evaluate; then remote launch/attach and path mapping.
3. **Testability**: Test harness + C++ and Rust debuggees + optional remote test with lldb-server on localhost.

Sections 2.3 and 2.2 define the feature roadmap and the CodeLLDB parity list; we can tick them off as we implement.

# Build LLDB (lldb-dap, lldb-server, liblldb) from source. Full install goes to a
# staging dir (gitignored); only the binaries we need are copied to
# prebuilts/lldb/<platform-id>/ (source-controlled) per DESIGN.md section 1.2 and 1.3.
# Usage: run from project root (e.g. .\scripts\build-lldb.ps1).
# Requires: Visual Studio with "Desktop development with C++", CMake, Ninja, Git.
# For best results, run from "x64 Native Tools Command Prompt for VS" or after running vcvarsall.bat.

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Resolve-Path (Join-Path $ScriptDir "..")
Set-Location $ProjectRoot

$LlvmTag = if ($env:LLVM_TAG) { $env:LLVM_TAG } else { "llvmorg-18.1.8" }
$PlatformId = if ($env:PLATFORM_ID) { $env:PLATFORM_ID } else { "win32-x64" }
$PrebuiltsLldb = Join-Path $ProjectRoot "prebuilts" "lldb"
$StagingPrefix = Join-Path $ProjectRoot "lldb-build" "install-staging"
$StagingPlatform = Join-Path $StagingPrefix $PlatformId
$OutputPrefix = Join-Path $PrebuiltsLldb $PlatformId
$LldbBuildDir = Join-Path $ProjectRoot "lldb-build"
$LlvmSrcDir = Join-Path $LldbBuildDir "llvm-project"
$BuildDir = Join-Path $LldbBuildDir "build"

Write-Host "PLATFORM_ID=$PlatformId"
Write-Host "LLVM_TAG=$LlvmTag"
Write-Host "Output (source-controlled): $OutputPrefix"
Write-Host "Staging (gitignored): $StagingPlatform"
Write-Host "Build tree (gitignored): $LldbBuildDir"

# Clone or fetch llvm-project
if (-not (Test-Path (Join-Path $LlvmSrcDir ".git"))) {
    Write-Host "Cloning llvm-project at $LlvmTag (shallow)..."
    New-Item -ItemType Directory -Force -Path (Split-Path $LlvmSrcDir) | Out-Null
    git clone --depth 1 --branch $LlvmTag https://github.com/llvm/llvm-project.git $LlvmSrcDir
} else {
    Write-Host "Using existing clone at $LlvmSrcDir"
    Push-Location $LlvmSrcDir
    git fetch --depth 1 origin tag $LlvmTag 2>$null
    git checkout $LlvmTag 2>$null
    Pop-Location
}

# Configure (install to staging)
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
cmake -B $BuildDir -G Ninja `
  -DCMAKE_BUILD_TYPE=RelWithDebInfo `
  -DCMAKE_INSTALL_PREFIX=$StagingPlatform `
  -DLLVM_ENABLE_PROJECTS="clang;lldb" `
  -DLLVM_TARGETS_TO_BUILD=host `
  -DLLVM_INCLUDE_TESTS=OFF `
  (Join-Path $LlvmSrcDir "llvm")

# Build and install to staging
Write-Host "Building and installing to staging (lldb, lldb-server, lldb-dap + install deps)..."
ninja -C $BuildDir install

# Copy only the binaries we need into source-controlled prebuilts
Write-Host "Copying lldb-dap, lldb-server, liblldb to $OutputPrefix..."
New-Item -ItemType Directory -Force -Path (Join-Path $OutputPrefix "bin") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $OutputPrefix "lib") | Out-Null
Copy-Item -Force (Join-Path $StagingPlatform "bin" "lldb-dap*") (Join-Path $OutputPrefix "bin") -ErrorAction SilentlyContinue
Copy-Item -Force (Join-Path $StagingPlatform "bin" "lldb-server*") (Join-Path $OutputPrefix "bin") -ErrorAction SilentlyContinue
Copy-Item -Force (Join-Path $StagingPlatform "lib" "liblldb*") (Join-Path $OutputPrefix "lib") -ErrorAction SilentlyContinue
if (Test-Path (Join-Path $StagingPlatform "bin" "liblldb*")) {
    Copy-Item -Force (Join-Path $StagingPlatform "bin" "liblldb*") (Join-Path $OutputPrefix "bin") -ErrorAction SilentlyContinue
}

Write-Host "Done. Binaries are in $OutputPrefix (source-controlled)."
Write-Host "Set KDAP_LLDB_ROOT to $PrebuiltsLldb when running KDAP."

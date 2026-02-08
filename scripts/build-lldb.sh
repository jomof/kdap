#!/usr/bin/env bash
# Build LLDB (lldb-dap, lldb-server, liblldb) from source. Full install goes to a
# staging dir (gitignored); only the binaries we need are copied to
# prebuilts/lldb/<platform-id>/ (source-controlled) per DESIGN.md §1.2 and §1.3.
# Usage: run from project root. Env: LLVM_TAG, PLATFORM_ID (optional).
# CI: cache lldb-build/ by platform and LLVM_TAG; output goes to prebuilts/lldb/.

set -euo pipefail

# --- Project root (directory containing DESIGN.md / .git)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

# --- Defaults (override with env)
: "${LLVM_TAG:=llvmorg-18.1.8}"
: "${CMAKE_BUILD_TYPE:=RelWithDebInfo}"

# --- Output: source-controlled prebuilts (only the binaries we need)
PREBUILTS_LLDB="$PROJECT_ROOT/prebuilts/lldb"

# --- Staging: full CMake install (gitignored, under lldb-build)
STAGING_PREFIX="$PROJECT_ROOT/lldb-build/install-staging"

# --- Platform id for install layout (§1.3)
if [[ -n "${PLATFORM_ID:-}" ]]; then
  PLATFORM_ID="$PLATFORM_ID"
else
  OS="$(uname -s)"
  ARCH="$(uname -m)"
  case "$OS" in
    Linux)
      case "$ARCH" in
        x86_64)     PLATFORM_ID=linux-x64 ;;
        aarch64|arm64) PLATFORM_ID=linux-arm64 ;;
        *)          PLATFORM_ID=linux-"$ARCH" ;;
      esac ;;
    Darwin)
      case "$ARCH" in
        arm64)  PLATFORM_ID=darwin-arm64 ;;
        x86_64) PLATFORM_ID=darwin-x64 ;;
        *)      PLATFORM_ID=darwin-"$ARCH" ;;
      esac ;;
    *)
      echo "Unsupported OS: $OS" >&2
      exit 1 ;;
  esac
fi

STAGING_PLATFORM="$STAGING_PREFIX/$PLATFORM_ID"
OUTPUT_PREFIX="$PREBUILTS_LLDB/$PLATFORM_ID"
LLVM_SRCDIR="$PROJECT_ROOT/lldb-build/llvm-project"
BUILD_DIR="$PROJECT_ROOT/lldb-build/build"

echo "PLATFORM_ID=$PLATFORM_ID"
echo "LLVM_TAG=$LLVM_TAG"
echo "Output (source-controlled): $OUTPUT_PREFIX"
echo "Staging (gitignored): $STAGING_PLATFORM"
echo "Build tree (gitignored): $PROJECT_ROOT/lldb-build/"

# --- Clone or fetch llvm-project (shallow at tag)
if [[ ! -d "$LLVM_SRCDIR/.git" ]]; then
  echo "Cloning llvm-project at $LLVM_TAG (shallow)..."
  mkdir -p "$(dirname "$LLVM_SRCDIR")"
  git clone --depth 1 --branch "$LLVM_TAG" https://github.com/llvm/llvm-project.git "$LLVM_SRCDIR"
else
  echo "Using existing clone at $LLVM_SRCDIR"
  (cd "$LLVM_SRCDIR" && git fetch --depth 1 origin tag "$LLVM_TAG" 2>/dev/null || true)
  (cd "$LLVM_SRCDIR" && git checkout "$LLVM_TAG" 2>/dev/null || true)
fi

# --- Configure (install to staging per-platform so we can copy from it)
mkdir -p "$BUILD_DIR"
cmake -B "$BUILD_DIR" -G Ninja \
  -DCMAKE_BUILD_TYPE="$CMAKE_BUILD_TYPE" \
  -DCMAKE_INSTALL_PREFIX="$STAGING_PLATFORM" \
  -DLLVM_ENABLE_PROJECTS="clang;lldb" \
  -DLLVM_TARGETS_TO_BUILD=host \
  -DLLVM_INCLUDE_TESTS=OFF \
  "$LLVM_SRCDIR/llvm"

# --- Build and install to staging (ninja install builds any install-only deps and then installs)
echo "Building and installing to staging (lldb, lldb-server, lldb-dap + install deps)..."
ninja -C "$BUILD_DIR" install

# --- Copy only the binaries we need into source-controlled prebuilts
echo "Copying lldb-dap, lldb-server, liblldb to $OUTPUT_PREFIX..."
mkdir -p "$OUTPUT_PREFIX/bin" "$OUTPUT_PREFIX/lib"
cp -f "$STAGING_PLATFORM/bin/lldb-dap"* "$OUTPUT_PREFIX/bin/" 2>/dev/null || true
cp -f "$STAGING_PLATFORM/bin/lldb-server"* "$OUTPUT_PREFIX/bin/" 2>/dev/null || true
cp -f "$STAGING_PLATFORM/lib/liblldb"* "$OUTPUT_PREFIX/lib/" 2>/dev/null || true
# On macOS we may have a .dylib and a symlink; on Windows .dll may be in bin
if [[ -d "$STAGING_PLATFORM/bin" ]]; then
  for f in "$STAGING_PLATFORM/bin"/liblldb*; do
    [[ -e "$f" ]] && cp -f "$f" "$OUTPUT_PREFIX/bin/" 2>/dev/null || true
  done
fi

echo "Done. Binaries are in $OUTPUT_PREFIX (source-controlled)."
echo "Set KDAP_LLDB_ROOT to $PREBUILTS_LLDB when running KDAP."

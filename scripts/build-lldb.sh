#!/usr/bin/env bash
# Builds lldb-dap and lldb-server from LLVM source with Python support.
# Output goes to lldb-install/<platform_id>/ (e.g. lldb-install/darwin-arm64/).
# Skips if already built (unless --force).
#
# Usage: run from project root.
#   ./scripts/build-lldb.sh              # current platform, skip if present
#   ./scripts/build-lldb.sh --force      # current platform, rebuild
#   ./scripts/build-lldb.sh --platform linux-x64   # build for linux-x64
#
# Env: LLVM_VERSION (default 21.1.8), PLATFORM_ID (auto-detected).
# Requires: cmake, ninja (or make), python3, python3-dev, swig, C++ compiler.

set -e

VERSION="${LLVM_VERSION:-21.1.8}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE_DIR="$PROJECT_ROOT/.llvm-source"
BUILD_DIR="$PROJECT_ROOT/lldb-build"
INSTALL_ROOT="$PROJECT_ROOT/lldb-install"
FORCE=0
PLATFORM_ARG=""

# --- Parse flags
while [[ $# -gt 0 ]]; do
  case "$1" in
    --force)   FORCE=1; shift ;;
    --platform)
      if [[ $# -lt 2 ]]; then
        echo "--platform requires an argument (e.g. linux-x64, darwin-arm64)" >&2
        exit 1
      fi
      PLATFORM_ARG="$2"; shift 2 ;;
    *)
      echo "Unknown option: $1" >&2
      echo "Usage: $0 [--force] [--platform <id>]" >&2
      echo "  --force    Rebuild even if lldb-dap is already present" >&2
      echo "  --platform darwin-arm64|darwin-x64|linux-x64|linux-arm64|win32-x64" >&2
      exit 1 ;;
  esac
done

cd "$PROJECT_ROOT"

# --- Platform id (align with LldbDapProcess.currentPlatformId())
if [[ -n "$PLATFORM_ARG" ]]; then
  PLATFORM_ID="$PLATFORM_ARG"
elif [[ -n "${PLATFORM_ID:-}" ]]; then
  PLATFORM_ID="$PLATFORM_ID"
else
  OS="$(uname -s)"
  ARCH="$(uname -m)"
  case "$OS" in
    Linux)
      case "$ARCH" in
        x86_64)        PLATFORM_ID=linux-x64 ;;
        aarch64|arm64) PLATFORM_ID=linux-arm64 ;;
        *)             PLATFORM_ID=linux-"$ARCH" ;;
      esac ;;
    Darwin)
      case "$ARCH" in
        arm64)  PLATFORM_ID=darwin-arm64 ;;
        x86_64) PLATFORM_ID=darwin-x64 ;;
        *)      PLATFORM_ID=darwin-"$ARCH" ;;
      esac ;;
    MINGW*|MSYS*|CYGWIN*)
      case "$ARCH" in
        x86_64) PLATFORM_ID=win32-x64 ;;
        *)      PLATFORM_ID=win32-"$ARCH" ;;
      esac ;;
    *)
      echo "Unsupported OS: $OS" >&2
      exit 1 ;;
  esac
fi

INSTALL_DIR="$INSTALL_ROOT/$PLATFORM_ID"
EXE_NAME="lldb-dap"
SERVER_NAME="lldb-server"
[[ "$PLATFORM_ID" == win32-* ]] && EXE_NAME="lldb-dap.exe"
[[ "$PLATFORM_ID" == win32-* ]] && SERVER_NAME="lldb-server.exe"

# --- Already built? Skip unless --force.
if [[ $FORCE -eq 0 ]] && [[ -x "$INSTALL_DIR/bin/$EXE_NAME" || -f "$INSTALL_DIR/bin/$EXE_NAME" ]]; then
  echo "Already built: $INSTALL_DIR/bin/$EXE_NAME"
  exit 0
fi

echo "=== Building LLDB from source (LLVM $VERSION, platform $PLATFORM_ID) ==="
echo "    Source:  $SOURCE_DIR"
echo "    Build:   $BUILD_DIR"
echo "    Install: $INSTALL_DIR"

# --- Determine LLVM target architecture from platform id
case "$PLATFORM_ID" in
  *-arm64) LLVM_TARGET_ARCH="AArch64" ;;
  *-x64)   LLVM_TARGET_ARCH="X86" ;;
  *)
    echo "Cannot determine LLVM target arch for platform: $PLATFORM_ID" >&2
    exit 1 ;;
esac

# --- Check prerequisites
check_cmd() {
  if ! command -v "$1" &>/dev/null; then
    echo "Required command not found: $1" >&2
    echo "Install it and try again." >&2
    exit 1
  fi
}
check_cmd cmake
check_cmd python3
check_cmd swig

# Prefer ninja if available; fall back to make.
if command -v ninja &>/dev/null; then
  CMAKE_GENERATOR="Ninja"
elif command -v make &>/dev/null; then
  CMAKE_GENERATOR="Unix Makefiles"
else
  echo "Neither ninja nor make found. Install one and try again." >&2
  exit 1
fi

# --- Download LLVM source if needed
SOURCE_ARCHIVE="llvm-project-${VERSION}.src.tar.xz"
SOURCE_URL="https://github.com/llvm/llvm-project/releases/download/llvmorg-${VERSION}/${SOURCE_ARCHIVE}"
LLVM_SRC_DIR="$SOURCE_DIR/llvm-project-${VERSION}.src"

if [[ ! -d "$LLVM_SRC_DIR/llvm" ]]; then
  mkdir -p "$SOURCE_DIR"
  if [[ ! -f "$SOURCE_DIR/$SOURCE_ARCHIVE" ]]; then
    echo "Downloading LLVM $VERSION source..."
    curl -fSL --connect-timeout 60 --max-time 1200 -o "$SOURCE_DIR/$SOURCE_ARCHIVE" "$SOURCE_URL"
  fi
  echo "Extracting source (this may take a minute)..."
  # The LLVM tarball contains symlinks (test fixtures, build utils) that
  # cannot be created on Windows.  These are not needed to build LLDB,
  # so we tolerate extraction errors on Windows.
  if [[ "$PLATFORM_ID" == win32-* ]]; then
    tar -xJf "$SOURCE_DIR/$SOURCE_ARCHIVE" -C "$SOURCE_DIR" || true
  else
    tar -xJf "$SOURCE_DIR/$SOURCE_ARCHIVE" -C "$SOURCE_DIR"
  fi
  if [[ ! -d "$LLVM_SRC_DIR/llvm" ]]; then
    echo "Extraction did not produce expected directory: $LLVM_SRC_DIR/llvm" >&2
    exit 1
  fi
  # Remove the archive to save disk space.
  rm -f "$SOURCE_DIR/$SOURCE_ARCHIVE"
fi

# --- Find Python3 for LLDB scripting support
PYTHON3_EXE="$(command -v python3)"
echo "Using Python3: $PYTHON3_EXE ($(python3 --version 2>&1))"

# Resolve Python3 root directory so CMake can find headers and libraries.
# python3-config --prefix gives us the installation root (e.g. /opt/homebrew/Cellar/python@3.13/3.13.2/Frameworks/Python.framework/Versions/3.13).
PYTHON3_ROOT=""
if command -v python3-config &>/dev/null; then
  PYTHON3_ROOT="$(python3-config --prefix 2>/dev/null || true)"
fi
if [[ -z "$PYTHON3_ROOT" ]]; then
  PYTHON3_ROOT="$(python3 -c 'import sys; print(sys.prefix)' 2>/dev/null || true)"
fi
if [[ -n "$PYTHON3_ROOT" ]]; then
  echo "Python3 root: $PYTHON3_ROOT"
fi

# --- Configure CMake
mkdir -p "$BUILD_DIR"

CMAKE_ARGS=(
  -G "$CMAKE_GENERATOR"
  -S "$LLVM_SRC_DIR/llvm"
  -B "$BUILD_DIR"
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_INSTALL_PREFIX="$INSTALL_DIR"
  -DLLVM_ENABLE_PROJECTS="clang;lldb"
  -DLLVM_TARGETS_TO_BUILD="$LLVM_TARGET_ARCH"
  -DLLDB_ENABLE_PYTHON=ON
  -DPython3_EXECUTABLE="$PYTHON3_EXE"
  -DLLDB_ENABLE_LIBEDIT=OFF
  -DLLDB_ENABLE_CURSES=OFF
  -DLLDB_ENABLE_LZMA=OFF
  -DLLDB_ENABLE_LUA=OFF
  -DLLVM_ENABLE_TERMINFO=OFF
  -DLLVM_ENABLE_ZLIB=OFF
  -DLLVM_ENABLE_ZSTD=OFF
  -DLLDB_INCLUDE_TESTS=OFF
  -DLLVM_INCLUDE_TESTS=OFF
  -DLLVM_INCLUDE_BENCHMARKS=OFF
)

# Help CMake find Python development files (headers + libs).
if [[ -n "$PYTHON3_ROOT" ]]; then
  CMAKE_ARGS+=(-DPython3_ROOT_DIR="$PYTHON3_ROOT")
fi

# Windows-specific: don't embed Python home (let it find Python at runtime).
if [[ "$PLATFORM_ID" == win32-* ]]; then
  CMAKE_ARGS+=(-DLLDB_EMBED_PYTHON_HOME=OFF)
fi

echo ""
echo "=== Configuring CMake (generator: $CMAKE_GENERATOR) ==="
cmake "${CMAKE_ARGS[@]}"

# --- Build specific targets only (not the entire LLVM project)
NPROC=$(nproc 2>/dev/null || sysctl -n hw.logicalcpu 2>/dev/null || echo 4)
echo ""
echo "=== Building lldb-dap, lldb-server, liblldb, Python bindings (${NPROC} parallel jobs) ==="
cmake --build "$BUILD_DIR" --target lldb-dap lldb-server liblldb lldb-python-scripts -- -j"$NPROC"

# --- Install using component-based installs (a full cmake --install fails
#     because it tries to install all configured targets, not just what we built).
echo ""
echo "=== Installing to $INSTALL_DIR ==="
cmake --install "$BUILD_DIR" --component lldb-dap --prefix "$INSTALL_DIR" --strip
cmake --install "$BUILD_DIR" --component lldb-server --prefix "$INSTALL_DIR" --strip
cmake --install "$BUILD_DIR" --component liblldb --prefix "$INSTALL_DIR" --strip
cmake --install "$BUILD_DIR" --component lldb-python-scripts --prefix "$INSTALL_DIR"

# --- Verify the install
echo ""
echo "=== Verifying installation ==="
if [[ -x "$INSTALL_DIR/bin/$EXE_NAME" ]] || [[ -f "$INSTALL_DIR/bin/$EXE_NAME" ]]; then
  echo "OK: $INSTALL_DIR/bin/$EXE_NAME"
else
  echo "FAIL: $INSTALL_DIR/bin/$EXE_NAME not found after install" >&2
  exit 1
fi

PYTHON_SITE=$(find "$INSTALL_DIR/lib" -path "*/site-packages/lldb" -o -path "*/dist-packages/lldb" 2>/dev/null | head -1)
if [[ -n "$PYTHON_SITE" ]]; then
  echo "OK: Python LLDB module at $PYTHON_SITE"
else
  echo "WARN: No Python LLDB module found under $INSTALL_DIR/lib/"
fi

# --- Clean up source and build dirs to reclaim disk space
echo ""
echo "=== Cleaning up build artifacts ==="
rm -rf "$BUILD_DIR"
rm -rf "$SOURCE_DIR"
echo "Removed $BUILD_DIR and $SOURCE_DIR"

echo ""
echo "=== Done. LLDB installed to $INSTALL_DIR ==="

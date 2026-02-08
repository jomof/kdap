#!/usr/bin/env bash
# Downloads the official LLVM release and extracts only the files needed for
# lldb-dap + lldb-server: bin binaries and minimal libs (no full lib/ dump).
# Skips if required files already present (unless --force). Use --platform to
# download for another OS (e.g. on Mac: --platform linux-x64 or --platform win32-x64).
#
# Usage: run from project root.
#   ./scripts/download-lldb.sh              # current platform, skip if present
#   ./scripts/download-lldb.sh --force      # current platform, re-download/re-extract
#   ./scripts/download-lldb.sh --platform linux-x64   # download for linux-x64
# Env: LLVM_VERSION (default 21.1.8).

set -e

VERSION="${LLVM_VERSION:-21.1.8}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DOWNLOAD_DIR="$PROJECT_ROOT/.lldb-download"
PREBUILTS_DIR="$PROJECT_ROOT/prebuilts/lldb"
FORCE=0
PLATFORM_ARG=""

# --- Parse flags
while [[ $# -gt 0 ]]; do
  case "$1" in
    --force)   FORCE=1; shift ;;
    --platform)
      if [[ $# -lt 2 ]]; then
        echo "--platform requires an argument (e.g. linux-x64, win32-x64)" >&2
        exit 1
      fi
      PLATFORM_ARG="$2"; shift 2 ;;
    *)
      echo "Unknown option: $1" >&2
      echo "Usage: $0 [--force] [--platform <id>]" >&2
      echo "  --force    Re-download and re-extract even if prebuilts present" >&2
      echo "  --platform darwin-arm64|darwin-x64|linux-x64|linux-arm64|win32-x64" >&2
      exit 1 ;;
  esac
done

cd "$PROJECT_ROOT"

# --- Platform id (align with LldbDapHarness.platformId())
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

# --- Archive name and URL per platform
case "$PLATFORM_ID" in
  darwin-arm64)  ARCHIVE="LLVM-${VERSION}-macOS-ARM64.tar.xz" ;;
  darwin-x64)    ARCHIVE="LLVM-${VERSION}-macOS-X64.tar.xz" ;;
  linux-x64)     ARCHIVE="LLVM-${VERSION}-Linux-X64.tar.xz" ;;
  linux-arm64)   ARCHIVE="LLVM-${VERSION}-Linux-ARM64.tar.xz" ;;
  win32-x64)     ARCHIVE="clang+llvm-${VERSION}-x86_64-pc-windows-msvc.tar.xz" ;;
  *)
    echo "Unsupported platform: $PLATFORM_ID" >&2
    exit 1 ;;
esac

URL="https://github.com/llvm/llvm-project/releases/download/llvmorg-${VERSION}/${ARCHIVE}"
TARGET="$PREBUILTS_DIR/$PLATFORM_ID"
EXE_NAME="lldb-dap"
[[ "$PLATFORM_ID" == win32-* ]] && EXE_NAME="lldb-dap.exe"
SERVER_NAME="lldb-server"
[[ "$PLATFORM_ID" == win32-* ]] && SERVER_NAME="lldb-server.exe"

# --- Required files present? Skip unless --force.
required_present() {
  [[ -f "$TARGET/bin/$EXE_NAME" ]] || [[ -x "$TARGET/bin/$EXE_NAME" ]] || return 1
  [[ -f "$TARGET/bin/$SERVER_NAME" ]] || [[ -x "$TARGET/bin/$SERVER_NAME" ]] || return 1
  [[ -d "$TARGET/lib" ]] && [[ -n "$(ls -A "$TARGET/lib" 2>/dev/null)" ]] || return 1
  return 0
}
if [[ $FORCE -eq 0 ]] && required_present; then
  echo "Already present: $TARGET"
  exit 0
fi

mkdir -p "$DOWNLOAD_DIR" "$TARGET"

# --- Download if missing
if [[ ! -f "$DOWNLOAD_DIR/$ARCHIVE" ]]; then
  echo "Downloading $URL ... (connect timeout 60s, max 600s)"
  curl -fSL --connect-timeout 60 --max-time 600 -o "$DOWNLOAD_DIR/$ARCHIVE" "$URL"
fi

# --- List archive to get TOPLEVEL and select only the members we need (no full lib/ dump)
echo "Listing archive (may take a minute for large tarballs) ..."
TAR_LIST=$(mktemp)
trap 'rm -f "$TAR_LIST" "$MEMBERS_FILE"' EXIT
tar -tf "$DOWNLOAD_DIR/$ARCHIVE" > "$TAR_LIST"
TOPLEVEL=$(head -1 "$TAR_LIST" | cut -d/ -f1)
if [[ -z "$TOPLEVEL" ]]; then
  echo "Could not determine tarball top-level directory" >&2
  exit 1
fi

MEMBERS_FILE=$(mktemp)
case "$PLATFORM_ID" in
  darwin-*)
    # Mac: exactly 4 files — bin/lldb-dap, bin/lldb-server, lib/liblldb*.dylib
    grep -E "^$TOPLEVEL/(bin/(lldb-dap|lldb-server)\$|lib/liblldb.*\.dylib\$)" "$TAR_LIST" > "$MEMBERS_FILE" || true
    ;;
  linux-*)
    # Linux: bin + minimal .so set + Python lldb module (local/lib/python3.10/dist-packages/lldb) for lldb-dap.
    grep -E "^$TOPLEVEL/bin/(lldb-dap|lldb-server)\$" "$TAR_LIST" > "$MEMBERS_FILE"
    grep -E "^$TOPLEVEL/lib/(.*/)?(liblldb|libLLVM|libclang\.so|libclang-cpp|libc\+\+|libc\+\+abi|libunwind|libRemarks|libLTO)\.so(\.[0-9]+)*\$" "$TAR_LIST" >> "$MEMBERS_FILE" || true
    grep -E "^$TOPLEVEL/local/lib/python3[.]10/dist-packages/lldb" "$TAR_LIST" >> "$MEMBERS_FILE" || true
    ;;
  win32-*)
    # Windows: bin exes + lib/*.dll only
    grep -E "^$TOPLEVEL/(bin/(lldb-dap|lldb-server)\.exe\$|lib/.*\.dll\$)" "$TAR_LIST" > "$MEMBERS_FILE" || true
    if [[ ! -s "$MEMBERS_FILE" ]]; then
      grep -E "^$TOPLEVEL/bin/(lldb-dap|lldb-server)\.exe\$" "$TAR_LIST" > "$MEMBERS_FILE"
      grep -E "^$TOPLEVEL/lib/.*\.dll\$" "$TAR_LIST" >> "$MEMBERS_FILE" || true
    fi
    ;;
  *) echo "Unsupported platform: $PLATFORM_ID" >&2; exit 1 ;;
esac

if [[ ! -s "$MEMBERS_FILE" ]]; then
  echo "No matching members found in archive. TOPLEVEL=$TOPLEVEL. First 20 entries:" >&2
  head -20 "$TAR_LIST"
  exit 1
fi

echo "Extracting $(wc -l < "$MEMBERS_FILE" | tr -d ' ') files to $TARGET ..."
tar -xJf "$DOWNLOAD_DIR/$ARCHIVE" -C "$TARGET" --strip-components=1 -T "$MEMBERS_FILE"

if [[ -x "$TARGET/bin/$EXE_NAME" ]] || [[ -f "$TARGET/bin/$EXE_NAME" ]]; then
  echo "Done. lldb-dap at: $TARGET/bin/$EXE_NAME"
else
  echo "Extraction did not produce $TARGET/bin/$EXE_NAME" >&2
  exit 1
fi

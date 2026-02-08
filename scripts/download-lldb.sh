#!/usr/bin/env bash
# Downloads the official LLVM release and extracts the full archive into
# prebuilts/lldb/<platform_id>/.
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

# --- Extract full archive (strip one path component so e.g. LLVM-21.1.8-Linux-X64/bin -> TARGET/bin)
echo "Extracting to $TARGET ... (may take 1–2 min)"
tar -xJf "$DOWNLOAD_DIR/$ARCHIVE" -C "$TARGET" --strip-components=1

if [[ -x "$TARGET/bin/$EXE_NAME" ]] || [[ -f "$TARGET/bin/$EXE_NAME" ]]; then
  echo "Done. lldb-dap at: $TARGET/bin/$EXE_NAME"
else
  echo "Extraction did not produce $TARGET/bin/$EXE_NAME" >&2
  exit 1
fi

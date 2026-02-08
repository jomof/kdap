#!/usr/bin/env bash
# Downloads the official LLVM release for the current platform and extracts only
# bin/lldb-dap, bin/lldb-server, and lib/ into prebuilts/lldb/<platform_id>/.
# Downloads go to .lldb-download/ (gitignored). Skips download if archive present.
# Usage: run from project root. Env: LLVM_VERSION (default 21.1.8), PLATFORM_ID (optional, auto-detected).

set -e

VERSION="${LLVM_VERSION:-21.1.8}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DOWNLOAD_DIR="$PROJECT_ROOT/.lldb-download"
PREBUILTS_DIR="$PROJECT_ROOT/prebuilts/lldb"

cd "$PROJECT_ROOT"

# --- Platform id (align with LldbDapHarness.platformId())
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

# --- Already present?
if [[ -x "$TARGET/bin/$EXE_NAME" ]] || [[ -f "$TARGET/bin/$EXE_NAME" ]]; then
  echo "Already present: $TARGET"
  exit 0
fi

mkdir -p "$DOWNLOAD_DIR" "$TARGET"

# --- Download if missing
if [[ ! -f "$DOWNLOAD_DIR/$ARCHIVE" ]]; then
  echo "Downloading $URL ... (connect timeout 60s, max 600s)"
  curl -fSL --connect-timeout 60 --max-time 600 -o "$DOWNLOAD_DIR/$ARCHIVE" "$URL"
fi

# --- Top-level dir inside the tarball
TOPLEVEL="$(tar -tf "$DOWNLOAD_DIR/$ARCHIVE" | head -1 | cut -d/ -f1)"
if [[ -z "$TOPLEVEL" ]]; then
  echo "Could not determine tarball top-level directory" >&2
  exit 1
fi

# --- Extract only bin/lldb-dap, bin/lldb-server, and lib/
echo "Extracting to $TARGET ... (may take 1–2 min for lib/)"
if [[ "$PLATFORM_ID" == win32-* ]]; then
  tar -xJf "$DOWNLOAD_DIR/$ARCHIVE" -C "$TARGET" --strip-components=1 \
    "$TOPLEVEL/bin/lldb-dap.exe" \
    "$TOPLEVEL/bin/lldb-server.exe" \
    "$TOPLEVEL/lib" 2>/dev/null || true
  if [[ ! -f "$TARGET/bin/lldb-dap.exe" ]]; then
    echo "Windows archive layout unexpected; listing first entries:" >&2
    tar -tf "$DOWNLOAD_DIR/$ARCHIVE" | head -20
    exit 1
  fi
else
  tar -xJf "$DOWNLOAD_DIR/$ARCHIVE" -C "$TARGET" --strip-components=1 \
    "$TOPLEVEL/bin/lldb-dap" \
    "$TOPLEVEL/bin/lldb-server" \
    "$TOPLEVEL/lib"
fi

if [[ -x "$TARGET/bin/$EXE_NAME" ]] || [[ -f "$TARGET/bin/$EXE_NAME" ]]; then
  echo "Done. lldb-dap at: $TARGET/bin/$EXE_NAME"
else
  echo "Extraction did not produce $TARGET/bin/$EXE_NAME" >&2
  exit 1
fi

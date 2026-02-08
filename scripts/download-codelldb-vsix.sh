#!/usr/bin/env bash
# Download the latest CodeLLDB VSIX for current Mac (darwin-arm64 or darwin-x64)
# and extract it so the DAP server (adapter + bin) is available locally.
#
# Usage: from project root, ./scripts/download-codelldb-vsix.sh
# Output: ./codelldb-vsix/ with extension/ (adapter, bin, etc.)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="$PROJECT_ROOT/codelldb-vsix"

cd "$PROJECT_ROOT"

ARCH=$(uname -m)
if [[ "$ARCH" == "arm64" ]]; then
  VSIX_NAME="codelldb-darwin-arm64.vsix"
elif [[ "$ARCH" == "x86_64" ]]; then
  VSIX_NAME="codelldb-darwin-x64.vsix"
else
  echo "Unsupported Mac arch: $ARCH" >&2
  exit 1
fi

# GitHub API can 403 without User-Agent; GITHUB_TOKEN (e.g. in CI) raises rate limits.
CURL_OPTS=(-sSfL -A "kdap-download-codelldb/1.0")
[[ -n "${GITHUB_TOKEN:-}" ]] && CURL_OPTS+=(-H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json")

LATEST_TAG=$(curl "${CURL_OPTS[@]}" "https://api.github.com/repos/vadimcn/codelldb/releases/latest" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p')
if [[ -z "$LATEST_TAG" ]]; then
  echo "Could not get latest release tag" >&2
  exit 1
fi

DOWNLOAD_URL="https://github.com/vadimcn/codelldb/releases/download/${LATEST_TAG}/${VSIX_NAME}"
echo "Downloading $VSIX_NAME ($LATEST_TAG) into $OUT_DIR ..."
mkdir -p "$OUT_DIR"
curl "${CURL_OPTS[@]}" -o "$OUT_DIR/codelldb.vsix" "$DOWNLOAD_URL"
echo "Extracting..."
unzip -o -q "$OUT_DIR/codelldb.vsix" -d "$OUT_DIR"
rm -f "$OUT_DIR/codelldb.vsix"
echo "Done. DAP server: $OUT_DIR/extension/adapter/ (binary: $OUT_DIR/extension/adapter/codelldb), launcher: $OUT_DIR/extension/bin/codelldb-launch"

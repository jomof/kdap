#!/usr/bin/env bash
# Run tests inside a Linux Docker container matching GitHub CI (Ubuntu + JDK 21).
# Uses --platform linux/amd64 so the container is x86_64 and finds prebuilts/lldb/linux-x64.
# On an ARM Mac, without --platform Docker would use arm64 and the harness would look
# for linux-arm64 (not present), so TCP_LLDB would fail.
#
# Uses Ubuntu 22.04 so we get Python 3.10 and system libs (libxml2, libncurses6) that
# the LLVM 21 lldb-dap binary needs at runtime. Then installs Temurin JDK 21.
#
# Usage: from project root, ./scripts/docker-test-linux.sh
# Requires: Docker, and prebuilts for linux-x64 (run ./scripts/download-lldb.sh --platform linux-x64 first if needed).

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"

docker run --rm \
  --platform linux/amd64 \
  -v "$PROJECT_ROOT:/workspace" \
  -w /workspace \
  -e PLATFORM_ID=linux-x64 \
  -e LLVM_VERSION="${LLVM_VERSION:-21.1.8}" \
  -e DEBIAN_FRONTEND=noninteractive \
  ubuntu:22.04 \
  bash -c '
    apt-get update -qq && apt-get install -qq -y \
      curl xz-utils \
      openjdk-21-jdk-headless \
      libxml2 libncurses6 libpython3.10 \
      > /dev/null
    ./scripts/download-lldb.sh
    test -d prebuilts/lldb/linux-x64/local/lib/python3.10/dist-packages/lldb || ./scripts/download-lldb.sh --force
    ./gradlew test
  '

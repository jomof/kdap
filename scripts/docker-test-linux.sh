#!/usr/bin/env bash
# Run tests inside a Linux Docker container matching GitHub CI (Ubuntu + JDK 21).
# Uses --platform linux/amd64 so the container is x86_64 and the build produces
# lldb-install/linux-x64/. On an ARM Mac, without --platform Docker would use arm64
# and the harness would look for linux-arm64 (not built), so STDIO_LLDB would fail.
#
# Uses Ubuntu 22.04 so we get system libs that the LLVM build and lldb-dap need.
# Installs build dependencies (cmake, ninja, python3-dev), builds LLDB from source
# with Python support, then runs tests.
#
# Usage: from project root, ./scripts/docker-test-linux.sh
# Requires: Docker.

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
      cmake ninja-build python3-dev swig \
      libxml2-dev \
      > /dev/null
    ./scripts/build-lldb.sh
    ./gradlew test
  '

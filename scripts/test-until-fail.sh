#!/usr/bin/env bash
# Runs ./gradlew test repeatedly until a failure occurs.
# Usage: ./scripts/test-until-fail.sh

set -euo pipefail
cd "$(dirname "$0")/.."

run=0
while true; do
    run=$((run + 1))
    echo "━━━ Run $run ━━━"
    if ! ./gradlew test --rerun-tasks "$@"; then
        echo ""
        echo "✗ Failed on run $run"
        exit 1
    fi
    echo ""
done

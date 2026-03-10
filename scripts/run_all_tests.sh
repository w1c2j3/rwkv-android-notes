#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR"
if [[ ! -f "$ROOT_DIR/local.properties" ]]; then
  echo "[0/3] Probing Android SDK"
  "$ROOT_DIR/scripts/setup_local_properties.sh"
fi

echo "[1/3] Kotlin unit tests"
if [[ -x "$ROOT_DIR/gradlew" ]]; then
  "$ROOT_DIR/gradlew" :app:testDebugUnitTest
elif command -v gradle >/dev/null 2>&1; then
  gradle -p "$ROOT_DIR" :app:testDebugUnitTest
else
  echo "ERROR: neither ./gradlew nor gradle is available." >&2
  echo "Install Gradle or add the Gradle wrapper to project root." >&2
  exit 1
fi

echo "[2/2] Native smoke note"
echo "Native inference has been consolidated under app/src/main/infer."
echo "Run Android instrumentation/device smoke for init/load/infer/cancel/destroy."
echo "All available host tests passed."

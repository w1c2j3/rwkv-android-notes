#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_PROPERTIES_PATH="$ROOT_DIR/local.properties"
REQUIRED_PLATFORM="android-35"
REQUIRED_CMAKE="3.22.1"

escape_property_value() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value// /\\ }"
  printf '%s' "$value"
}

append_candidate() {
  local candidate="$1"
  [[ -n "$candidate" ]] || return 0
  [[ -d "$candidate" ]] || return 0
  SDK_CANDIDATES+=("$candidate")
}

dedupe_candidates() {
  local seen=""
  local deduped=()
  local candidate
  for candidate in "${SDK_CANDIDATES[@]}"; do
    case ":$seen:" in
      *":$candidate:"*) ;;
      *)
        deduped+=("$candidate")
        seen="${seen}:$candidate"
        ;;
    esac
  done
  SDK_CANDIDATES=("${deduped[@]}")
}

SDK_CANDIDATES=()
append_candidate "${ANDROID_SDK_ROOT:-}"
append_candidate "${ANDROID_HOME:-}"
append_candidate "$HOME/Android/Sdk"

if compgen -G "/mnt/*/Users/*/AppData/Local/Android/Sdk" >/dev/null; then
  for candidate in /mnt/*/Users/*/AppData/Local/Android/Sdk; do
    append_candidate "$candidate"
  done
fi

if compgen -G "/mnt/*/Android/Sdk" >/dev/null; then
  for candidate in /mnt/*/Android/Sdk; do
    append_candidate "$candidate"
  done
fi

dedupe_candidates

if [[ ${#SDK_CANDIDATES[@]} -eq 0 ]]; then
  cat >&2 <<'EOF'
ERROR: Android SDK location not found.

Install Android Studio or the Android command line tools first, then re-run:
  bash scripts/setup_local_properties.sh

Supported discovery sources:
  - ANDROID_SDK_ROOT
  - ANDROID_HOME
  - $HOME/Android/Sdk
  - /mnt/<drive>/Users/<WindowsUser>/AppData/Local/Android/Sdk
  - /mnt/<drive>/Android/Sdk
EOF
  exit 1
fi

SDK_DIR="${SDK_CANDIDATES[0]}"
printf 'sdk.dir=%s\n' "$(escape_property_value "$SDK_DIR")" > "$LOCAL_PROPERTIES_PATH"

echo "Wrote $LOCAL_PROPERTIES_PATH"
echo "sdk.dir=$SDK_DIR"

if [[ ! -d "$SDK_DIR/platforms/$REQUIRED_PLATFORM" ]]; then
  echo "WARN: missing Android platform $REQUIRED_PLATFORM in $SDK_DIR/platforms" >&2
fi

if [[ ! -d "$SDK_DIR/cmake/$REQUIRED_CMAKE" ]]; then
  echo "WARN: missing CMake $REQUIRED_CMAKE in $SDK_DIR/cmake" >&2
fi

if ! compgen -G "$SDK_DIR/ndk/*" >/dev/null; then
  echo "WARN: missing Android NDK in $SDK_DIR/ndk" >&2
fi

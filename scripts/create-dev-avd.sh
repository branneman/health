#!/usr/bin/env bash
# Creates (or recreates) the development AVD and runs E2E tests locally.
# Matches CI exactly: Pixel 6a, API 34 AOSP, x86_64, animations disabled.
#
# Prerequisites:
#   export E2E_PASSWORD=<secret>          # same value as CI secret
#   export SERVER_BASE_URL=<server url>   # or set server.baseUrl in local.properties
#
# Usage:
#   ./scripts/create-dev-avd.sh           # create/recreate AVD, boot, run tests
#   ./scripts/create-dev-avd.sh --no-create  # skip AVD creation (AVD already exists)
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
TOOLS="$SDK/cmdline-tools/latest/bin"
EMULATOR="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"
IMAGE="system-images;android-34;default;x86_64"
AVD_NAME="health-dev"
SERVER_URL="${SERVER_BASE_URL:-$(grep 'server.baseUrl' "$(dirname "$0")/../local.properties" 2>/dev/null | cut -d= -f2)}"

if [[ -z "${E2E_PASSWORD:-}" ]]; then
    echo "Error: E2E_PASSWORD is not set." >&2
    exit 1
fi
if [[ -z "$SERVER_URL" ]]; then
    echo "Error: SERVER_BASE_URL not set and server.baseUrl not in local.properties." >&2
    exit 1
fi

# --- Create AVD ---
if [[ "${1:-}" != "--no-create" ]]; then
    IMAGE_DIR="$SDK/system-images/android-34/default/x86_64"
    if [[ ! -d "$IMAGE_DIR" ]]; then
        echo "==> Downloading system image: $IMAGE"
        "$TOOLS/sdkmanager" "$IMAGE"
    else
        echo "==> System image already installed, skipping download."
    fi

    echo "==> Creating AVD: $AVD_NAME"
    echo no | "$TOOLS/avdmanager" create avd \
        --name    "$AVD_NAME" \
        --package "$IMAGE" \
        --device  pixel_6a \
        --force
fi

# --- Boot emulator ---
echo "==> Starting emulator (this takes a few minutes)..."
"$EMULATOR" -avd "$AVD_NAME" -no-snapshot -no-window -gpu swiftshader_indirect \
    -noaudio -no-boot-anim -camera-back none &
EMULATOR_PID=$!
trap "kill $EMULATOR_PID 2>/dev/null || true" EXIT

echo "==> Waiting for emulator to boot..."
"$ADB" wait-for-device
until "$ADB" shell getprop sys.boot_completed 2>/dev/null | grep -q '1'; do
    sleep 2
done
echo "==> Emulator booted."

# --- Disable animations (matches CI disable-animations: true) ---
echo "==> Disabling animations..."
"$ADB" shell settings put global window_animation_scale 0
"$ADB" shell settings put global transition_animation_scale 0
"$ADB" shell settings put global animator_duration_scale 0

# --- Seed E2E account ---
echo "==> Seeding E2E account..."
curl -sf -X POST \
    -H "Authorization: Bearer $E2E_PASSWORD" \
    "$SERVER_URL/internal/e2e/reset"
echo " done."

# --- Write E2E credentials to local.properties (git-ignored) ---
PROPS_FILE="$(dirname "$0")/../local.properties"
grep -v '^e2eEmail\|^e2ePassword' "$PROPS_FILE" > "$PROPS_FILE.tmp" && mv "$PROPS_FILE.tmp" "$PROPS_FILE"
printf '\ne2eEmail=test+e2e@bran.name\ne2ePassword=%s\n' "$E2E_PASSWORD" >> "$PROPS_FILE"

# --- Run E2E tests ---
echo "==> Running E2E tests..."
cd "$(dirname "$0")/.."
SERVER_BASE_URL="$SERVER_URL" ./gradlew :app:connectedAndroidTest

# --- Clear rate limits ---
echo "==> Clearing rate limits..."
curl -sf -X POST \
    -H "Authorization: Bearer $E2E_PASSWORD" \
    -H "Content-Type: application/json" \
    -d '{"username":"test+e2e@bran.name"}' \
    "$SERVER_URL/internal/clear-rate-limits" || true

echo "==> Done."

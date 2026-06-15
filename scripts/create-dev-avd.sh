#!/usr/bin/env bash
# Creates the development AVD (Pixel 6a, API 34 AOSP) used for local E2E testing.
# Safe to run repeatedly — overwrites an existing AVD with the same name.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
TOOLS="$SDK/cmdline-tools/latest/bin"
IMAGE="system-images;android-34;default;x86_64"

echo "Downloading system image: $IMAGE"
"$TOOLS/sdkmanager" "$IMAGE"

echo "Creating AVD: health-dev"
echo no | "$TOOLS/avdmanager" create avd \
    --name    health-dev \
    --package "$IMAGE" \
    --device  pixel_6a \
    --force

echo ""
echo "Done. Start the emulator with:"
echo "  \$ANDROID_HOME/emulator/emulator -avd health-dev -no-snapshot"

#!/bin/bash
set -e

echo "=== Setting up Android SDK ==="

# Install dependencies
sudo apt-get update -q
sudo apt-get install -y -q wget unzip

# Android SDK command-line tools
ANDROID_SDK_ROOT="$HOME/android-sdk"
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"

CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-10406996_latest.zip"
wget -q "$CMDLINE_TOOLS_URL" -O /tmp/cmdline-tools.zip
unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extracted
mv /tmp/cmdline-tools-extracted/cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest"
rm /tmp/cmdline-tools.zip

# Export env vars
export ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

# Persist to shell profile
echo "export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT" >> ~/.bashrc
echo "export ANDROID_HOME=$ANDROID_SDK_ROOT" >> ~/.bashrc
echo "export PATH=\$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:\$ANDROID_SDK_ROOT/platform-tools:\$PATH" >> ~/.bashrc

# Accept licenses and install required SDK components
yes | sdkmanager --licenses > /dev/null 2>&1 || true
sdkmanager --install \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  > /dev/null 2>&1

# Make gradlew executable
chmod +x gradlew

echo ""
echo "=== Android SDK ready ==="
echo ""
echo "To build the APK, run:"
echo "  ./gradlew assembleDebug"
echo ""
echo "APK output: app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "NOTE: Before building, replace app/google-services.json"
echo "      with your real Firebase file (see instructions inside the placeholder)."
echo "      Google Sign-In will not work without it, but local account login will."

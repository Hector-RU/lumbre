#!/usr/bin/env bash
set -euo pipefail

mkdir -p test-artifacts/ci
capture_diagnostics() {
    adb logcat -d > test-artifacts/ci/logcat.txt 2>&1 || true
    adb shell df -h > test-artifacts/ci/storage.txt 2>&1 || true
}
trap capture_diagnostics EXIT

adb shell getprop ro.build.version.sdk
adb shell dumpsys webviewupdate
# Real installed voices require an explicit physical-device opt-in.
./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.notClass=com.hector.epubreader.DeviceSpeechTest \
    --console=plain
python3 scripts/verify_android_test_results.py

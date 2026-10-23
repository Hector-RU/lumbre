#!/usr/bin/env bash
set -euo pipefail

mkdir -p test-artifacts/ci
capture_diagnostics() {
    adb logcat -d > test-artifacts/ci/logcat.txt 2>&1 || true
    adb shell df -h > test-artifacts/ci/storage.txt 2>&1 || true
}
trap capture_diagnostics EXIT

# sys.boot_completed alone can be set while API 37 is still starting user storage.
# Require the package service and unlocked credential storage before installing tests.
deadline=$((SECONDS + 180))
ready_samples=0
while ((ready_samples < 3)); do
    if adb shell am get-started-user-state 0 2>/dev/null | grep -q RUNNING_UNLOCKED &&
        adb shell pm path android 2>/dev/null | grep -q '^package:'; then
        ready_samples=$((ready_samples + 1))
    else
        ready_samples=0
        adb shell input keyevent 82 >/dev/null 2>&1 || true
    fi
    if ((SECONDS >= deadline)); then
        echo 'Android user storage/package service did not become ready' >&2
        exit 1
    fi
    sleep 2
done

adb shell getprop ro.build.version.sdk
adb shell dumpsys webviewupdate
# Real installed voices require an explicit physical-device opt-in.
./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.notClass=com.hector.epubreader.DeviceSpeechTest \
    --console=plain
python3 scripts/verify_android_test_results.py

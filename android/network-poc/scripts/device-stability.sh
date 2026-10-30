#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
cd "$(dirname "$0")/.."
mkdir -p evidence
collect_diagnostics() {
  timeout 15 adb logcat -d -t 5000 > evidence/logcat.txt 2>&1 || true
  timeout 15 adb shell getprop ro.build.version.sdk > evidence/api.txt 2>&1 || true
  timeout 15 adb shell dumpsys webviewupdate > evidence/webview.txt 2>&1 || true
  cp "$RUNNER_TEMP/network-fixture.log" evidence/fixture.log 2>/dev/null || true
}
trap collect_diagnostics EXIT
find app/build/outputs/apk/ci/debug -name '*.apk' -print -quit | xargs adb install -r
find app/build/outputs/apk/androidTest/ci/debug -name '*.apk' -print -quit | xargs adb install -r
python3 -u scripts/run-stability.py --fixture secure-fixture/fixture.json --transport "${NETWORK_TEST_TRANSPORT:?}" --output evidence/stability

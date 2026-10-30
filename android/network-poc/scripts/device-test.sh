#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
cd "$(dirname "$0")/.."
mkdir -p evidence
trap 'timeout 15 adb logcat -d -t 5000 > evidence/logcat.txt 2>&1 || true; cp "$RUNNER_TEMP/network-fixture.log" evidence/fixture.log 2>/dev/null || true' EXIT
adb shell getprop ro.build.version.sdk > evidence/api.txt
adb shell dumpsys webviewupdate > evidence/webview.txt
find app/build/outputs/apk/ci/debug -name '*.apk' -print -quit | xargs adb install -r
find app/build/outputs/apk/androidTest/ci/debug -name '*.apk' -print -quit | xargs adb install -r
mode="${NETWORK_TEST_TRANSPORT:-raw}"
if [ "$mode" = raw ]; then
  python3 scripts/run-routing.py --fixture secure-fixture/fixture.json --transport raw --origin http --output evidence/routing
fi
python3 scripts/run-routing.py --fixture secure-fixture/fixture.json --transport "$mode" --origin https --output evidence/routing

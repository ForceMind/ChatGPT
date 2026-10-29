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
timeout 420 adb shell am instrument -w -r \
  -e nodeA 'vless://00000000-0000-4000-8000-000000000001@10.0.2.2:18443#Owned-A' \
  -e nodeB 'vless://00000000-0000-4000-8000-000000000001@10.0.2.2:18444#Owned-B' \
  -e fixtureUrl 'http://network-fixture.test:18080' \
  com.forcemind.chatgpt.networkpreview.ci.test/androidx.test.runner.AndroidJUnitRunner \
  | tee evidence/instrumentation.txt
python3 - <<'PY'
from pathlib import Path
import re
s = Path('evidence/instrumentation.txt').read_text()
if not re.search(r'OK \([1-9][0-9]* tests?\)', s) or re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=', s):
    raise SystemExit('Instrumented routing tests failed or did not run')
PY

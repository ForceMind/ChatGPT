#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
cd "$(dirname "$0")/.."
out="$RUNNER_TEMP/studio-ios-poc"
app="$out/ChatGPT Studio PoC.app"
mkdir -p "$app"
xcodebuild -version
xcrun --sdk iphonesimulator --show-sdk-version
xcrun swiftc -swift-version 6 -parse-as-library StudioPOC/Workspace.swift scripts/check-workspace.swift -o "$out/check-workspace"
"$out/check-workspace"
xcrun --sdk iphonesimulator swiftc -swift-version 6 -parse-as-library -target arm64-apple-ios26.0-simulator \
  -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" -module-name StudioPOC StudioPOC/*.swift -o "$app/StudioPOC"
xcrun --sdk iphoneos swiftc -swift-version 6 -parse-as-library -target arm64-apple-ios26.0 \
  -sdk "$(xcrun --sdk iphoneos --show-sdk-path)" -module-name StudioPOC StudioPOC/*.swift -o "$out/StudioPOC-device-unsigned"
python3 - <<'PY'
import json, os, plistlib, subprocess
from pathlib import Path
out = Path(os.environ['RUNNER_TEMP']) / 'studio-ios-poc'
app = out / 'ChatGPT Studio PoC.app'
bundle = {'CFBundleIdentifier': 'com.forcemind.chatgpt.studio.iospoc', 'CFBundleExecutable': 'StudioPOC',
          'CFBundleName': 'ChatGPT Studio PoC', 'CFBundleDisplayName': 'ChatGPT Studio PoC',
          'CFBundlePackageType': 'APPL', 'CFBundleVersion': '1', 'CFBundleShortVersionString': '0.1.0',
          'CFBundleSupportedPlatforms': ['iPhoneSimulator'], 'MinimumOSVersion': '26.0', 'UIDeviceFamily': [1, 2],
          'UILaunchScreen': {}}
(app / 'Info.plist').write_bytes(plistlib.dumps(bundle))
for binary, platform in ((app / 'StudioPOC', 'IOSSIMULATOR'), (out / 'StudioPOC-device-unsigned', 'IOS')):
    info = subprocess.check_output(['xcrun', 'vtool', '-show-build', str(binary)], text=True)
    assert f'platform {platform}' in info and 'minos 26.0' in info
receipt = {'sourceSHA': os.environ['GITHUB_SHA'], 'workspaceChecks': True, 'deviceLinked': True,
           'simulatorLinked': True, 'browserExecuted': False, 'deviceSigned': False}
(out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
PY
codesign --force --sign - "$app"
codesign --verify --strict "$app"
ditto -c -k --keepParent "$app" "$out/ChatGPT-Studio-iOS26-simulator.zip"

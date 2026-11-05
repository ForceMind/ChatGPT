#!/usr/bin/env bash
# Owned Actions emulator bootstrap. No input is sent before a focused window exists.
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
studio_evidence=android/app/build/environment-client-evidence
mkdir -p "$studio_evidence"
sdkmanager 'emulator' 'system-images;android-35;google_apis;x86_64'
studio_avd_home="$RUNNER_TEMP/studio-environment-avd"
mkdir -p "$studio_avd_home"
echo no | ANDROID_AVD_HOME="$studio_avd_home" avdmanager create avd --force -n studio-environment35 --path "$studio_avd_home/studio-environment35.avd" --package 'system-images;android-35;google_apis;x86_64' --device pixel_2
test -f "$studio_avd_home/studio-environment35.avd/config.ini"
printf '\nhw.cpu.ncore=2\n' >> "$studio_avd_home/studio-environment35.avd/config.ini"
ANDROID_AVD_HOME="$studio_avd_home" "$ANDROID_HOME/emulator/emulator" -avd studio-environment35 -port 5554 -memory 4096 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -feature -Vulkan -writable-system > "$studio_evidence/emulator.log" 2>&1 &
studio_emulator_pid=$!
studio_cleanup(){
  studio_exit=$?
  trap - EXIT
  set +e
  timeout 15 adb -s emulator-5554 logcat -d > "$studio_evidence/emulator-final-logcat.txt" 2>&1
  timeout 15 adb -s emulator-5554 emu kill > "$studio_evidence/emulator-stop.txt" 2>&1
  kill "$studio_emulator_pid" 2>/dev/null
  exit "$studio_exit"
}
trap studio_cleanup EXIT
timeout 600 adb -s emulator-5554 wait-for-device
studio_boot_deadline=$((SECONDS+600))
until [ "$(adb -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do
  test "$SECONDS" -lt "$studio_boot_deadline"
  kill -0 "$studio_emulator_pid"
  sleep 2
done
# Unlike a MENU key, this is handled by WindowManager and cannot queue a key
# against an activity that has not yet established its first focused window.
adb -s emulator-5554 shell wm dismiss-keyguard
while true; do
  timeout 15 adb -s emulator-5554 shell dumpsys window > "$studio_evidence/emulator-boot-window.txt"
  if rg -q 'mCurrentFocus=Window\{' "$studio_evidence/emulator-boot-window.txt"; then break; fi
  test "$SECONDS" -lt "$studio_boot_deadline"
  sleep 2
done
adb -s emulator-5554 shell settings put global window_animation_scale 0
adb -s emulator-5554 shell settings put global transition_animation_scale 0
adb -s emulator-5554 shell settings put global animator_duration_scale 0
adb -s emulator-5554 shell settings put system screen_off_timeout 2147483647
adb -s emulator-5554 logcat -d > "$studio_evidence/emulator-boot-logcat.txt"
if rg -q 'ANR in com.google.android.apps.nexuslauncher' "$studio_evidence/emulator-boot-logcat.txt"; then
  echo 'Launcher ANR occurred before the application test; preserving the invalid environment.' >&2
  exit 1
fi
adb push android/app/build/environment-ascii/input.jar /data/local/tmp/studio-poc-ascii-input.jar
python3 android/scripts/run-environment-client.py

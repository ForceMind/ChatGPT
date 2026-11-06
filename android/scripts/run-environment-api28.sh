#!/usr/bin/env bash
# The existing Android9 upgrade/recovery cases, with the actual emulator process retained for diagnosis.
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
studio_target="${1:-synthetic}"
case "$studio_target" in human|synthetic) ;; *) echo 'Only the approved human/synthetic upgrade targets may run.' >&2; exit 1 ;; esac
studio_evidence=android/app/build/environment-client-evidence
mkdir -p "$studio_evidence"
# The emulator executable links libpulse even with -no-audio. The previous runner installed it implicitly.
sudo apt-get update -qq
sudo apt-get install -y --no-install-recommends libpulse0
sdkmanager 'emulator' 'system-images;android-28;default;x86_64'
studio_avd_home="$RUNNER_TEMP/studio-environment28-avd"
mkdir -p "$studio_avd_home"
echo no | ANDROID_AVD_HOME="$studio_avd_home" avdmanager create avd --force -n studio-environment28 --path "$studio_avd_home/studio-environment28.avd" --package 'system-images;android-28;default;x86_64' --device pixel_2
printf '\nhw.cpu.ncore=2\n' >> "$studio_avd_home/studio-environment28.avd/config.ini"
"$ANDROID_HOME/emulator/emulator" -version > "$studio_evidence/emulator-version.txt" 2>&1
studio_emulator_log="$RUNNER_TEMP/studio-environment28-emulator.log"
# Keep API/device/CPU/memory fixed. The deprecated indirect renderer exited 139 on cold recovery.
# Validate the same cases on the supported software backend, retaining every process log and assertion.
ANDROID_AVD_HOME="$studio_avd_home" "$ANDROID_HOME/emulator/emulator" -avd studio-environment28 -port 5554 -memory 3072 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -feature -Vulkan -writable-system > "$studio_emulator_log" 2>&1 &
studio_emulator_pid=$!
studio_cleanup(){
  studio_exit=$?
  trap - EXIT
  set +e
  if kill -0 "$studio_emulator_pid" 2>/dev/null; then
    printf 'aliveAtDriverExit=true\ndriverExit=%s\n' "$studio_exit" > "$studio_evidence/emulator-process-exit.txt"
    timeout 15 adb -s emulator-5554 logcat -d > "$studio_evidence/emulator-final-logcat.txt" 2>&1
    timeout 15 adb -s emulator-5554 emu kill > "$studio_evidence/emulator-stop.txt" 2>&1
    kill "$studio_emulator_pid" 2>/dev/null
  else
    wait "$studio_emulator_pid"
    studio_process_exit=$?
    printf 'aliveAtDriverExit=false\ndriverExit=%s\nemulatorExit=%s\n' "$studio_exit" "$studio_process_exit" > "$studio_evidence/emulator-process-exit.txt"
  fi
  cp "$studio_emulator_log" "$studio_evidence/emulator.log"
  exit "$studio_exit"
}
trap studio_cleanup EXIT
studio_adb(){ timeout 15 adb -s emulator-5554 "$@"; }
timeout 600 adb -s emulator-5554 wait-for-device
studio_boot_deadline=$((SECONDS+600))
until [ "$(studio_adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do
  test "$SECONDS" -lt "$studio_boot_deadline"
  kill -0 "$studio_emulator_pid"
  sleep 2
done
studio_adb shell wm dismiss-keyguard
while true; do
  timeout 15 adb -s emulator-5554 shell dumpsys window > "$studio_evidence/emulator-boot-window.txt"
  if grep -Eq 'mCurrentFocus=Window\{' "$studio_evidence/emulator-boot-window.txt"; then break; fi
  test "$SECONDS" -lt "$studio_boot_deadline"
  kill -0 "$studio_emulator_pid"
  sleep 2
done
studio_adb shell settings put global window_animation_scale 0
studio_adb shell settings put global transition_animation_scale 0
studio_adb shell settings put global animator_duration_scale 0
studio_adb shell settings put system screen_off_timeout 2147483647
python3 android/network-poc/scripts/install-webview.py --provider android/app/build/environment-provider-input/provider --output "$studio_evidence/provider"
if [ "$studio_target" = synthetic ]; then
  studio_adb push android/app/build/environment-touch/input.jar /data/local/tmp/studio-poc-input.jar
  studio_adb push android/app/build/environment-ascii/input.jar /data/local/tmp/studio-poc-ascii-input.jar
  python3 android/scripts/run-environment-client.py
else
  python3 android/scripts/environment_upgrade.py --target human
fi

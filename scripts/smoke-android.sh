#!/usr/bin/env bash
set -euo pipefail

mkdir -p smoke-logs
apk="$(find smoke-apk -maxdepth 1 -name '*.apk' -type f -print -quit)"
if [ -z "$apk" ]; then
  echo "No APK downloaded from the build artifact" >&2
  exit 2
fi

package="com.oll.xy8.run${GITHUB_RUN_NUMBER}"
activity="com.oll.xy8.MainActivity"
echo "APK: $apk" | tee smoke-logs/summary.txt
echo "Package: $package" | tee -a smoke-logs/summary.txt
adb install -r "$apk"
adb logcat -c || true

set +e
adb shell am start -W -n "$package/$activity" >smoke-logs/am-start.txt 2>&1
launch_status=$?
set -e
cat smoke-logs/am-start.txt

sleep 12
adb logcat -d -b main,system,crash -v threadtime >smoke-logs/logcat.txt || true
adb shell dumpsys activity activities >smoke-logs/activities.txt || true
pid="$(adb shell pidof "$package" 2>/dev/null | tr -d '\r' || true)"
echo "am start exit: $launch_status" | tee -a smoke-logs/summary.txt
echo "App PID after 12 seconds: ${pid:-NOT RUNNING}" | tee -a smoke-logs/summary.txt

if [ "$launch_status" -ne 0 ] || [ -z "$pid" ]; then
  echo "FAIL: Application did not remain running after launch" | tee -a smoke-logs/summary.txt
  echo "Relevant Android crash log lines:"
  grep -E -A 20 -B 4 'FATAL EXCEPTION|Fatal signal|AndroidRuntime|UnsatisfiedLinkError|JNI DETECTED ERROR|tombstone|com.oll.xy8' smoke-logs/logcat.txt | tail -n 170 || true
  exit 1
fi

echo "PASS: App process remained alive through the initial launch window" | tee -a smoke-logs/summary.txt

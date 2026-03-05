#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESULTS_DIR="$ROOT_DIR/.ci/results"
UI_HELPER="$ROOT_DIR/.github/scripts/uiauto_helper.py"
PR_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
MASTER_APK="$ROOT_DIR/.ci/upstream-master/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE_NAME="com.red.alert"
PREFS_PATH="/data/data/$PACKAGE_NAME/shared_prefs/com.red.alert_preferences.xml"

mkdir -p "$RESULTS_DIR"

require_file() {
  local path="$1"
  if [[ ! -f "$path" ]]; then
    echo "Missing required file: $path" >&2
    exit 1
  fi
}

wait_for_boot() {
  adb wait-for-device
  local timeout=300
  local start
  start="$(date +%s)"
  while true; do
    local state
    state="$(adb get-state 2>/dev/null || true)"
    if [[ "$state" != "device" ]]; then
      adb reconnect offline >/dev/null 2>&1 || true
      adb wait-for-device >/dev/null 2>&1 || true
      sleep 2
      continue
    fi

    if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      adb shell input keyevent 82 >/dev/null 2>&1 || true
      break
    fi
    if (( "$(date +%s)" - start > timeout )); then
      echo "Emulator did not boot within ${timeout}s" >&2
      adb devices >&2 || true
      exit 1
    fi
    sleep 2
  done
}

tap_optional() {
  local args=("$@")
  if python3 "$UI_HELPER" tap "${args[@]}" --clickable --timeout 1.2 >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

tap_required() {
  local args=("$@")
  python3 "$UI_HELPER" tap "${args[@]}" --clickable --timeout 20
}

tap_text_variants() {
  local text
  for text in "$@"; do
    if tap_optional --text "$text"; then
      return 0
    fi
  done
  return 1
}

tap_preference_resilient() {
  local labels=("$@")
  local attempt
  for attempt in $(seq 1 8); do
    if tap_text_variants "${labels[@]}"; then
      return 0
    fi
    adb shell input swipe 540 1850 540 950 250
    sleep 0.6
  done
  echo "no match for labels: ${labels[*]}" >&2
  return 1
}

exists() {
  local args=("$@")
  python3 "$UI_HELPER" exists "${args[@]}"
}

dismiss_transient_dialogs() {
  local rounds="${1:-10}"
  for _ in $(seq 1 "$rounds"); do
    tap_optional --id com.android.permissioncontroller:id/permission_allow_button || true
    tap_optional --id android:id/button1 || true
    tap_optional --text "Allow" || true
    tap_optional --text "OK" || true
    sleep 0.4
  done
}

assert_logcat_clean() {
  local logcat_file="$RESULTS_DIR/logcat.txt"
  adb logcat -d >"$logcat_file"

  if grep -q "FATAL EXCEPTION" "$logcat_file"; then
    echo "Found fatal exception in logcat" >&2
    grep -n "FATAL EXCEPTION" "$logcat_file" | tail -n 20 >&2
    exit 1
  fi

  if grep -q "ClassCastException" "$logcat_file"; then
    echo "Found ClassCastException in logcat" >&2
    grep -n "ClassCastException" "$logcat_file" | tail -n 20 >&2
    exit 1
  fi

  if grep -q "IllegalStateException: ListPreference requires an entries array" "$logcat_file"; then
    echo "Found ListPreference entries IllegalStateException in logcat" >&2
    grep -n "IllegalStateException: ListPreference requires an entries array" "$logcat_file" | tail -n 20 >&2
    exit 1
  fi
}

assert_preferences() {
  adb shell run-as "$PACKAGE_NAME" cat "$PREFS_PATH" >"$RESULTS_DIR/final_prefs.xml"

  python3 - <<'PY'
import xml.etree.ElementTree as ET

root = ET.parse(".ci/results/final_prefs.xml").getroot()

def read_value(key):
    for node in root:
        if node.attrib.get("name") == key:
            if node.tag == "string":
                return node.text or ""
            if "value" in node.attrib:
                return node.attrib["value"]
    return None

expected = {
    "selected_cities": "all",
    "selected_zones": "none",
    "selected_secondary_cities": "all",
    "volume": "64",
    "secondary_volume": "33",
    "gps_frequency_v2": "21",
    "max_distance_v2": "44",
    "foreground_service": "true",
}

for key, want in expected.items():
    got = read_value(key)
    if got != want:
        raise SystemExit(f"Preference mismatch for {key}: expected '{want}', got '{got}'")

print("Preference assertions passed.")
PY
}

require_file "$PR_APK"
require_file "$MASTER_APK"

echo "Waiting for emulator boot..."
wait_for_boot

echo "Installing upstream master debug APK..."
adb uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true
adb install -r "$MASTER_APK"
adb shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
adb shell am force-stop "$PACKAGE_NAME"

echo "Seeding master-style preferences..."
cat >"$RESULTS_DIR/master_seed.xml" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="enabled" value="true" />
    <string name="selected_cities">all</string>
    <string name="selected_zones">none</string>
    <boolean name="secondary_alerts" value="true" />
    <string name="selected_secondary_cities">all</string>
    <boolean name="alert_popup_v2" value="false" />
    <boolean name="secondary_alert_popup_v2" value="true" />
    <boolean name="foreground_service" value="true" />
    <boolean name="location_alerts_v2" value="false" />
    <float name="gps_frequency_v2" value="0.21" />
    <float name="max_distance_v2" value="0.44" />
    <float name="volume" value="0.64" />
    <float name="secondary_volume" value="0.33" />
    <boolean name="vibrate" value="true" />
    <boolean name="secondary_vibrate" value="false" />
    <boolean name="secondary_override" value="false" />
    <boolean name="override" value="true" />
    <boolean name="wake_screen" value="true" />
    <long name="user_id" value="12345" />
    <string name="user_hash">abc123</string>
    <boolean name="is_subscribed_1_0_33" value="true" />
    <boolean name="tutorial_1_0_24" value="true" />
</map>
EOF

adb push "$RESULTS_DIR/master_seed.xml" /data/local/tmp/master_seed.xml >/dev/null
adb shell run-as "$PACKAGE_NAME" mkdir -p "/data/data/$PACKAGE_NAME/shared_prefs"
adb shell run-as "$PACKAGE_NAME" cp /data/local/tmp/master_seed.xml "$PREFS_PATH"
adb shell run-as "$PACKAGE_NAME" chmod 660 "$PREFS_PATH"
adb shell run-as "$PACKAGE_NAME" test -f "$PREFS_PATH"

echo "Upgrading to PR debug APK..."
adb install -r "$PR_APK"
adb logcat -c
adb shell am force-stop "$PACKAGE_NAME"
adb shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

echo "Dismissing startup dialogs..."
dismiss_transient_dialogs 20

echo "Opening Settings tab..."
tap_required --id com.red.alert:id/nav_settings
sleep 1

echo "Navigating to Advanced Settings..."
tap_preference_resilient "Advanced Settings" "הגדרות מתקדמות"
sleep 1

echo "Validating Advanced volume slider opens..."
tap_preference_resilient "Volume level" "עוצמת צליל"
if [[ "$(exists --id com.red.alert:id/slider_preference_seekbar)" != "1" ]]; then
  echo "Advanced volume slider did not appear." >&2
  exit 1
fi
tap_optional --id android:id/button2 || tap_optional --text "Cancel" || true
sleep 0.5

echo "Validating Secondary Alerts dialogs..."
tap_preference_resilient "Secondary Alerts" "התרעות משנה"
sleep 1
tap_preference_resilient "Secondary cities" "יישובי משנה"
if [[ "$(exists --id com.red.alert:id/searchListView)" != "1" ]]; then
  echo "Secondary cities dialog list did not appear." >&2
  exit 1
fi
tap_optional --id android:id/button2 || tap_optional --text "Cancel" || true
sleep 0.5
tap_preference_resilient "Volume level" "עוצמת צליל"
if [[ "$(exists --id com.red.alert:id/slider_preference_seekbar)" != "1" ]]; then
  echo "Secondary volume slider did not appear." >&2
  exit 1
fi
tap_optional --id android:id/button2 || tap_optional --text "Cancel" || true
sleep 0.5

echo "Validating Location alerts sliders..."
adb shell input keyevent 4
sleep 0.8
tap_preference_resilient "Location-based alerts" "התרעות לפי מיקום"
sleep 1
tap_preference_resilient "Update interval" "קצב דגימה"
if [[ "$(exists --id com.red.alert:id/slider_preference_seekbar)" != "1" ]]; then
  echo "Location update interval slider did not appear." >&2
  exit 1
fi
tap_optional --id android:id/button2 || tap_optional --text "Cancel" || true
sleep 0.5
tap_preference_resilient "Max distance" "מרחק מקסימלי"
if [[ "$(exists --id com.red.alert:id/slider_preference_seekbar)" != "1" ]]; then
  echo "Location max distance slider did not appear." >&2
  exit 1
fi
tap_optional --id android:id/button2 || tap_optional --text "Cancel" || true
sleep 0.5

echo "Asserting migrated preferences..."
assert_preferences

echo "Asserting logcat is clean for target crashes..."
assert_logcat_clean

echo "Cloud emulator validation completed successfully."

#!/usr/bin/env bash
# Runs the BioSense Log test suites from any terminal (macOS, Linux, Git Bash on Windows).
# Usage: tests/run-tests.sh [unit|integration|security|lint|local|ui|all|coverage]   (default: local)
#   unit, integration, security, lint, local : no device needed
#   ui, all                                  : an emulator or phone must be connected
set -u

suite="${1:-local}"
root="$(cd "$(dirname "$0")/.." && pwd)"
gradle="$root/gradlew"
[ -f "$root/gradlew.bat" ] && [ -n "${WINDIR:-}" ] && gradle="$root/gradlew.bat"

case "$suite" in
  unit|integration|security|lint|local|ui|all|coverage) ;;
  *) echo "Unknown suite '$suite'. Use: unit | integration | security | lint | local | ui | all | coverage"; exit 2 ;;
esac

debug_report="app/build/reports/tests/testDebugUnitTest/index.html"
release_report="app/build/reports/tests/testReleaseUnitTest/index.html"
summary=()
failed=0

step() { # name report gradle-args...
  local name="$1" report="$2"; shift 2
  echo; echo "=== $name ==="
  if (cd "$root" && "$gradle" "$@" --console=plain); then
    summary+=("PASS  $name|$report")
  else
    summary+=("FAIL  $name|$report"); failed=1
  fi
}

find_adb() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$sdk" ] && [ -f "$root/local.properties" ]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' "$root/local.properties" | sed 's/\\\\/\\/g; s/\\:/:/g')"
  fi
  [ -z "$sdk" ] && [ -n "${LOCALAPPDATA:-}" ] && sdk="$LOCALAPPDATA/Android/Sdk"
  for candidate in "$sdk/platform-tools/adb" "$sdk/platform-tools/adb.exe"; do
    [ -x "$candidate" ] && { echo "$candidate"; return; }
  done
  command -v adb 2>/dev/null
}

case "$suite" in
  unit)
    step "Unit tests" "$debug_report" testDebugUnitTest --tests 'com.example.sensor.*' --tests 'com.example.ExampleUnitTest' ;;
  integration)
    step "Integration tests (developer + store builds)" "$debug_report + $release_report" \
      testDebugUnitTest testReleaseUnitTest --continue --tests 'com.example.data.*' --tests 'com.example.ui.*' ;;
  security)
    step "Security tests (developer + store builds)" "$debug_report + $release_report" \
      testDebugUnitTest testReleaseUnitTest --continue --tests 'com.example.security.*' ;;
esac

if [ "$suite" = "local" ] || [ "$suite" = "all" ]; then
  step "Unit + integration + security tests (developer + store builds)" "$debug_report + $release_report" \
    testDebugUnitTest testReleaseUnitTest --continue
fi
if [ "$suite" = "lint" ] || [ "$suite" = "local" ] || [ "$suite" = "all" ]; then
  step "Lint (static analysis)" "app/build/reports/lint-results-debug.html" lintDebug
fi
if [ "$suite" = "ui" ] || [ "$suite" = "all" ]; then
  adb="$(find_adb)"
  count=0
  [ -n "$adb" ] && count="$("$adb" devices | tail -n +2 | grep -c $'\tdevice$')"
  if [ "$count" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
    # UI tests erase the app's data on the device: never pick one implicitly.
    echo; echo "=== UI tests ==="
    echo "Several devices are connected:"; "$adb" devices | tail -n +2 | grep $'\tdevice$' | cut -f1 | sed 's/^/  /'
    echo "UI tests ERASE BioSense Log's measurements on the device they run on. Choose one, e.g.:"
    echo "  ANDROID_SERIAL=emulator-5554 tests/run-tests.sh ui"
    summary+=("FAIL  UI tests (device)|not run: choose a device with ANDROID_SERIAL"); failed=1
  elif [ "$count" -ge 1 ]; then
    step "UI tests (device)" "app/build/reports/androidTests/connected/debug/index.html" connectedDebugAndroidTest
  else
    echo; echo "=== UI tests ==="
    echo "No emulator or phone connected. Start an emulator or plug in a phone with USB debugging, then run: tests/run-tests.sh ui"
    summary+=("FAIL  UI tests (device)|not run: no device"); failed=1
  fi
fi

if [ "$suite" = "coverage" ]; then
  step "Coverage (local tests)" "app/build/reports/coverage/test/debug/index.html" createDebugUnitTestCoverageReport
  xml="$root/app/build/reports/coverage/test/debug/report.xml"
  if [ -f "$xml" ]; then
    # Report-level counters are the last LINE / BRANCH counters in the file.
    for type in LINE BRANCH; do
      grep -o "<counter type=\"$type\" missed=\"[0-9]*\" covered=\"[0-9]*\"/>" "$xml" | tail -1 |
        sed -E 's/.*missed="([0-9]+)" covered="([0-9]+)".*/\1 \2/' |
        awk -v t="$type" '{ total = $1 + $2; if (total > 0) printf "Overall %s coverage: %.1f%% (%d/%d)\n", tolower(t), 100 * $2 / total, $2, total }'
    done
  fi
fi

echo; echo "=== Summary ==="
for line in "${summary[@]}"; do
  echo "${line%%|*}"
  echo "      report: ${line#*|}"
done
exit $failed

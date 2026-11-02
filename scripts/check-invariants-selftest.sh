#!/usr/bin/env bash
# check-invariants.sh가 실제로 위반을 잡는지 확인한다. 위반 파일을 하나씩 심고, 기대한 라벨로 실패하는지 본다.
# 심은 파일은 끝나면(실패해도) 지운다.
set -uo pipefail
cd "$(dirname "$0")/.."

CORE=proximity-core/src/commonMain/kotlin/dev/proximity/core
planted=""
cleanup() { [ -n "$planted" ] && rm -f "$planted"; }
trap cleanup EXIT

if ! ./scripts/check-invariants.sh >/dev/null; then
  echo "SELFTEST FAIL: 위반을 심기 전부터 check-invariants가 실패한다"; exit 1
fi

failures=0
# expect <라벨> <파일> <내용>
expect() {
  local label="$1" file="$2" content="$3" out
  planted="$file"
  mkdir -p "$(dirname "$file")"
  printf '%s\n' "$content" > "$file"
  if out=$(./scripts/check-invariants.sh 2>&1); then
    echo "  MISS $label: 위반을 잡지 못했다 ($file)"; failures=$((failures + 1))
  elif ! grep -q "FAIL $label" <<<"$out"; then
    echo "  MISS $label: 실패했지만 다른 라벨이다"; echo "$out" | grep FAIL | sed 's/^/    /'; failures=$((failures + 1))
  else
    echo "  ok   $label"
  fi
  rm -f "$file"; planted=""
}

echo "[selftest] check-invariants.sh"
expect "I-1 core imports" "$CORE/selftest/S1Violation.kt" $'package x\nimport dev.proximity.platform.PlatformModule'
expect "I-2 'deck'" "$CORE/selftest/S2Violation.kt" $'package x\nval deck = 1'
expect "I-3' core constructs implementations" "$CORE/selftest/S3Violation.kt" $'package x\nval c = FakeClock()'
expect "I-4' state assignment" "$CORE/selftest/S4Violation.kt" $'package x\nfun f(m: M) { m.state = 1 }'
expect "I-5 sample uses platform BLE/sensor API" "sample-android/src/main/kotlin/dev/proximity/sample/S5Violation.kt" $'package x\nimport android.bluetooth.BluetoothAdapter'
expect "I-5' platform import" "$CORE/selftest/S6Violation.kt" $'package x\nimport java.io.File'
expect "I-5' qualified JVM API" "proximity-testing/src/commonMain/kotlin/S7Violation.kt" $'package x\nval f = java.io.File("a")'
expect "I-9 core test imports platform" "proximity-core/src/jvmTest/kotlin/S0_Violation.kt" $'package x\nimport dev.proximity.platform.PlatformModule'
expect "I-16 core/api" "$CORE/api/S8Violation.kt" $'package x\nimport dev.proximity.core.interfaces.SessionTransport'
expect "I-7 device name advertised" "proximity-platform/src/main/kotlin/S9Violation.kt" $'package x\nfun f(b: B) = b.setIncludeDeviceName(true)'
expect "LOG interpolates sensitive value" "$CORE/selftest/S10Violation.kt" $'package x\nfun f(payload: ByteArray) = log("got ${payload.size} ${payload}")'
expect "R-1 test file without S<n>_ prefix" "proximity-core/src/jvmTest/kotlin/NoPrefixTest.kt" $'package x'
rmdir "$CORE/selftest" 2>/dev/null || true

if [ "$failures" -eq 0 ]; then echo "SELFTEST PASS"; else echo "SELFTEST FAIL ($failures)"; exit 1; fi

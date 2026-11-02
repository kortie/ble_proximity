#!/usr/bin/env bash
# 구조 불변 조건 검사 (원본 §4.1, 01-architecture §5). 실패 시 non-zero exit.
# 검사가 실패하면 검사를 완화하지 말고 코드를 고친다. 예외가 꼭 필요하면 ADR + 사유와 함께 화이트리스트.
# I-6·I-8·I-11~I-15는 단계별 테스트(S<n>_*)로, I-7은 S2 코덱 테스트 + 아래 정적 검사로 검사한다.
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0
check() { echo "[check] $1"; }
violation() { echo "  FAIL $1"; fail=1; }
# grep 결과가 있으면 위반으로 보고한다.
report() {
  local label="$1" hits="$2"
  if [ -n "$hits" ]; then violation "$label"; echo "$hits" | sed 's/^/    /'; fi
}

CORE_MAIN=$(ls -d proximity-core/src/*Main 2>/dev/null)
COMMON_MAIN="proximity-core/src/commonMain proximity-testing/src/commonMain"
API_DIR=proximity-core/src/commonMain/kotlin/dev/proximity/core/api
IFACE_DIR=proximity-core/src/commonMain/kotlin/dev/proximity/core/interfaces

check "I-1 dependency direction (core → platform/sample/testing 금지)"
report "I-1 core imports" "$(grep -rnE '^import dev\.proximity\.(platform|sample|testing)\b' $CORE_MAIN 2>/dev/null)"
report "I-1 core build deps" "$(grep -nE 'project\(":(proximity-platform|sample-android)"\)' proximity-core/build.gradle.kts)"

check "I-2 game-agnostic core (docs/thresholds.md GAME_TERMS)"
terms=$(grep -oE '^GAME_TERMS=\([^)]*\)' docs/thresholds.md | sed -E 's/^GAME_TERMS=\((.*)\)$/\1/')
if [ -z "$terms" ]; then violation "I-2 GAME_TERMS 목록을 docs/thresholds.md에서 찾지 못했다"; fi
for term in $terms; do
  report "I-2 '$term'" "$(grep -rniw --include='*.kt' --include='*.proto' -- "$term" $CORE_MAIN 2>/dev/null)"
done

check "I-3' interfaces exist and core only receives them"
for name in BumpSensor BleTransport SessionTransport RendezvousTransport GameModule Clock CryptoBox; do
  if ! grep -qE "^interface $name\b" "$IFACE_DIR/$name.kt" 2>/dev/null; then violation "I-3' interface $name 없음 ($IFACE_DIR/$name.kt)"; fi
done
report "I-3' core constructs implementations" \
  "$(grep -rnE '\b(Fake[A-Z][A-Za-z0-9]*|BouncyCastleCryptoBox|Android[A-Z][A-Za-z0-9]*)\(' $CORE_MAIN 2>/dev/null)"

check "I-4' single transition path (reducer 밖 state 대입 금지)"
report "I-4' state assignment" \
  "$(grep -rnE '(\.state[[:space:]]*=[^=]|^[[:space:]]*state[[:space:]]*=[^=])' --include='*.kt' $CORE_MAIN 2>/dev/null | grep -vE '/[A-Za-z]*(Transition|Reducer)[A-Za-z]*\.kt:')"

check "I-5 platform isolation (UI는 BLE·센서 API를 직접 호출하지 않는다)"
report "I-5 sample uses platform BLE/sensor API" \
  "$(grep -rnE 'android\.bluetooth|android\.hardware\.(Sensor|camera)|CoreBluetooth|UIKit' sample-android/src 2>/dev/null)"

check "I-5' commonMain has no platform or JVM API"
report "I-5' platform import" "$(grep -rnE '^import (android|java|javax|platform)\.' --include='*.kt' $COMMON_MAIN 2>/dev/null)"
report "I-5' qualified JVM API" "$(grep -rnE '\b(java|javax)\.(io|nio|util|net|lang|security)\.' --include='*.kt' $COMMON_MAIN 2>/dev/null | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(\*|//)')"

check "I-7 advertising carries no device name"
report "I-7 device name advertised" "$(grep -rnE 'setIncludeDeviceName\([[:space:]]*true' proximity-platform/src 2>/dev/null)"
for f in $(grep -rlE 'AdvertiseData\.Builder\(' proximity-platform/src/main 2>/dev/null); do
  grep -qE 'setIncludeDeviceName\([[:space:]]*false' "$f" || violation "I-7 $f: AdvertiseData에 setIncludeDeviceName(false)가 없다"
done

check "I-9 core tests use fakes only"
report "I-9 core test imports platform" "$(grep -rnE '^import dev\.proximity\.platform\b' proximity-core/src/*Test 2>/dev/null)"

check "I-16 game API exposes no transport types"
report "I-16 core/api" \
  "$(grep -rnE '\b(SessionTransport|BleTransport|ScanResult|LinkId|LinkEvent|InboundFrame|HostRef|CryptoBox)\b|dev\.proximity\.(platform|core\.interfaces)' $API_DIR 2>/dev/null)"

check "LOG policy (로그에 payload·본문·임시 ID·비밀 값 보간 금지, S10)"
report "LOG interpolates sensitive value" \
  "$(grep -rnE '\b(log|println|Log\.[dviwe])\(.*\$\{?[^}"]*\b(payload|data_?|bytes|plain|body|text|tempId|secret|resumeSecret|token|key|privateKey|displayName|nonce)\b' --include='*.kt' proximity-core/src/commonMain proximity-platform/src/main 2>/dev/null)"

check "R-1 regression naming (테스트 클래스 파일은 S<n>_ 접두사)"
report "R-1 test file without S<n>_ prefix" \
  "$(find . -path ./build -prune -o -path '*/build' -prune -o -path '*/src/*Test/*' -name '*.kt' -print 2>/dev/null | grep -vE '/S[0-9]+_[A-Za-z0-9_]+\.kt$')"

if [ "$fail" -eq 0 ]; then echo "ALL INVARIANTS PASS"; else echo "INVARIANTS FAILED"; exit 1; fi

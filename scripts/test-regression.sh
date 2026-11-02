#!/usr/bin/env bash
# 누적 회귀: S0 ~ SN 접두사 테스트 전부 + 검사 스크립트 자체 테스트 + Android 빌드. 사용: test-regression.sh --through SN
set -uo pipefail
cd "$(dirname "$0")/.."

if [ "${1:-}" != "--through" ] || ! [[ "${2:-}" =~ ^S([0-9]+)$ ]]; then
  echo "사용: $0 --through S<n>"; exit 2
fi
n="${BASH_REMATCH[1]}"

./scripts/check-invariants-selftest.sh >/dev/null || { echo "REGRESSION through S$n: FAIL (check-invariants selftest)"; exit 1; }

# 테스트 결과 디렉터리를 비워 이번 실행 결과만 집계한다 (필터가 바뀌면 이전 결과가 남지 않게).
find . -path ./.gradle -prune -o -path '*/build/test-results' -type d -print | xargs rm -rf
./gradlew --console=plain -q -PregressionThrough="$n" jvmTest :proximity-crypto:test :proximity-platform:testDebugUnitTest :sample-android:assembleDebug --rerun-tasks
status=$?
./scripts/test-summary.sh "REGRESSION through S$n" "$status"

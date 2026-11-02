#!/usr/bin/env bash
# 단위 테스트 전체 (Fake만 사용, 실기기 불필요 — I-9). 결과 요약은 마지막 줄.
set -uo pipefail
cd "$(dirname "$0")/.."

./gradlew --console=plain -q jvmTest :proximity-crypto:test :proximity-platform:testDebugUnitTest "$@"
status=$?
./scripts/test-summary.sh "UNIT" "$status"

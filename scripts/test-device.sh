#!/usr/bin/env bash
# 실기기 테스트 진입점. 실기기 검증은 S11에서 사용자가 Android 단말 2대로 수행한다 (D4·D13).
# 그 전까지 각 단계의 실기기 항목은 docs/spec/hw-verification-backlog.md에 쌓는다.
set -uo pipefail
cd "$(dirname "$0")/.."

echo "실기기 테스트는 S11에서 수행한다 (D4). 대상 항목: docs/spec/hw-verification-backlog.md"
echo "연결된 기기:"
adb devices 2>/dev/null | tail -n +2 | grep -v '^$' || echo "  (adb 없음 또는 기기 없음)"
exit 2

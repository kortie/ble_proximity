#!/usr/bin/env bash
# 단계 문서 "검증 기록" 초안을 출력한다 (ble-step 스킬 §4). 사용: gate-report.sh SN
# 명령 결과는 직접 실행해 채우고, 기준치 실측값·수정 내역·제한 사항은 사람이 채운다.
set -uo pipefail
cd "$(dirname "$0")/.."

if ! [[ "${1:-}" =~ ^S([0-9]+)$ ]]; then echo "사용: $0 S<n>"; exit 2; fi
n="${BASH_REMATCH[1]}"
step_doc=$(ls docs/spec/steps/S$(printf '%02d' "$n")-*.md 2>/dev/null | head -1)

commit=$(git rev-parse --short HEAD 2>/dev/null || echo "없음")
dirty=$([ -n "$(git status --porcelain 2>/dev/null)" ] && echo " + 커밋 안 된 변경" || true)
inv=$(./scripts/check-invariants.sh >/dev/null 2>&1 && echo PASS || echo FAIL)
reg=$(./scripts/test-regression.sh --through "S$n" 2>&1 | tail -1)

hw="해당 없음"
if [ -n "$step_doc" ]; then
  count=$(awk '/^### 실기기/{f=1;next} /^#/{f=0} f && /^- \[/{c++} END{print c+0}' "$step_doc")
  [ "$count" -gt 0 ] && hw="보류 → backlog HW-S$n (${count}항목)"
fi

cat <<EOF
### $(date +%F) (커밋 ${commit}${dirty})
- check-invariants: $inv
- ${reg#REGRESSION through S$n: }  ← test-regression --through S$n
- 실기기: $hw
- 기준치 대비: (자동으로 측정된 값만. 추정 금지)
- 실패 후 수정: (없으면 "없음")
- 스펙 변경: (무엇을 왜, ADR 번호)
- 알려진 제한:
EOF

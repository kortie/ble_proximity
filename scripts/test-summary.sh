#!/usr/bin/env bash
# Gradle 테스트 결과 XML을 합산해 한 줄로 보고한다. 사용: test-summary.sh <라벨> <gradle 종료 코드>
set -uo pipefail
cd "$(dirname "$0")/.."

label="$1"
gradle_status="$2"
total=0
bad=0
for f in $(find . -path ./.gradle -prune -o -path '*/build/test-results/*' -name 'TEST-*.xml' -print); do
  t=$(grep -oE '<testsuite [^>]*' "$f" | grep -oE ' tests="[0-9]+"' | grep -oE '[0-9]+')
  fl=$(grep -oE '<testsuite [^>]*' "$f" | grep -oE ' failures="[0-9]+"' | grep -oE '[0-9]+')
  er=$(grep -oE '<testsuite [^>]*' "$f" | grep -oE ' errors="[0-9]+"' | grep -oE '[0-9]+')
  total=$((total + ${t:-0}))
  bad=$((bad + ${fl:-0} + ${er:-0}))
done
passed=$((total - bad))
if [ "$gradle_status" -eq 0 ] && [ "$bad" -eq 0 ] && [ "$total" -gt 0 ]; then
  echo "$label: PASS ($passed/$total)"
else
  echo "$label: FAIL ($passed/$total, gradle exit $gradle_status)"
  exit 1
fi

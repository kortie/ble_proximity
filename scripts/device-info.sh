#!/usr/bin/env bash
# S11 "2대(Android)의 기종·OS 버전 기록": 연결된 기기의 모델·OS·SDK·BLE 기능을 검증 기록용 한 줄씩 출력한다.
# 계정·일련번호는 출력하지 않는다 (adb 일련번호도 끝 4자리만).
set -uo pipefail
if ! command -v adb >/dev/null; then echo "adb가 없다"; exit 2; fi
mapfile -t serials < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ "${#serials[@]}" -eq 0 ]; then echo "연결된 기기가 없다"; exit 2; fi
for s in "${serials[@]}"; do
  p() { adb -s "$s" shell getprop "$1" 2>/dev/null | tr -d '\r'; }
  le=$(adb -s "$s" shell pm list features 2>/dev/null | tr -d '\r' | grep -c 'android.hardware.bluetooth_le$')
  echo "- 기기 …${s: -4}: $(p ro.product.manufacturer) $(p ro.product.model), Android $(p ro.build.version.release) (SDK $(p ro.build.version.sdk)), BLE $([ "$le" -gt 0 ] && echo 있음 || echo 없음)"
done

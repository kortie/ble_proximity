# 가속도 트레이스 (S3)

형식: UTF-8 CSV. `#`로 시작하는 줄은 주석, 첫 데이터 줄은 머리글 `t_ms,x,y,z`.
- `t_ms`: 단조 증가 시각(ms, `Clock` 기준). 시작값은 아무 값이나 된다.
- `x,y,z`: 가속도(g). 중력 포함 (`TYPE_ACCELEROMETER` / 9.80665).

| 디렉터리 | 내용 |
|---|---|
| `synthetic/` | 재생 경로 시험용 합성 파일. `scripts/gen-accel-fixtures.py`가 시드 고정으로 만든다 (Kotlin 생성기와 독립) |
| `real/` | 실기기 기록 (HW-S3). Proximity Lab "센서 기록"으로 만들어 `adb pull`로 가져온다. 파일 이름: `<종류>_<기기>_<번호>.csv`, 종류 = bump·walk·run·pocket·tap·putdown |

합성 코퍼스(부딪힘 10 + 오탐 100)는 파일이 아니라 `proximity-testing`의 `SyntheticAccel`이 테스트 때 만든다.
`real/`에 파일이 생기면 `S3_BumpCorpusTest`가 종류별로 같은 기준(config/thresholds.yaml)으로 검사한다.

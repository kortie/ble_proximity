# 기준치 (잠정값 — S11 실기기 검증 후 확정)

> **운영 규칙 (D5, 원본 §6.3 개정)**
> 1. 아래 값은 **잠정값**이다. S0~S10의 자동 테스트(시뮬레이션)는 이 값으로 판정한다.
> 2. S11 실기기 검증에서 한 번만 조정할 수 있다. 조정하면 아래 "변경 기록"에 이전 값, 새 값, 근거(측정 결과·증거 경로)를 남긴다. 그 뒤 상태를 `확정`으로 바꾼다.
> 3. **단계 진행 중에 테스트를 통과시키려고 값을 바꾸지 않는다.** 잠정값이 비현실적이라고 판단되면 사용자에게 알리고 S11까지 기록만 해 둔다.
> 4. `확정` 이후의 변경은 ADR로만 한다.
> 5. `실측` 항목은 정해진 단계에서 처음 측정한 값을 기준으로 삼는다.

| 항목 | 단계 | 기준치 | 상태 |
|---|---|---|---|
| 부딪힘 감지 성공률 | S3 | 10회 중 9회 이상 | 잠정 |
| 일상 동작 오탐률 | S3 | 트레이스 100건 중 2건 이하 | 잠정 |
| 정답 쌍 첫 시도 선택률 (N=2/4/8, 가장 가까운 후보) | S4 | 각각 95% / 90% / 80% 이상 | 잠정 |
| 정답 쌍 최종 선택률 (재시도 `max_attempts` 포함) | S4 | 각각 99% / 97% / 95% 이상 | 잠정 |
| 오매칭 (틀린 쌍이 Matched) | S4·S5 | 0건 (시뮬레이션 전 시드) | 잠정 |
| 범프 후 상대 bumpSeq 관측 지연 p95 (`bump_flag_wait_ms` 근거) | S4 | 실측 | 실측 |
| 실기기 광고 수신 성공 | S2 | 교차 플랫폼(iOS↔Android) 양방향 1회 이상 | 잠정 |
| 호스트 동시 안정 링크 수 (`max_links`) | S6·S11 | 잠정 7 (8인 게임, D8). 단말 2대 환경에서는 실측 불가 → 잠정값 유지, S11 알려진 위험으로 기록 | 잠정 |
| 다운링크 전체 전달 지연 p95 (200B, 링크 7개) | S6 | ≤ 500ms | 잠정 |
| 다운링크 유실 후 resync 완료 | S6 | ≤ 2s | 잠정 |
| 관전자 social 메시지 속도 제한 | S7 | 분당 20건, 140바이트 | 잠정 |
| 게임 메시지 크기 한도 (action / event / view) | S8 | 512 / 1024 / 4096 바이트 (S6 실측으로 view 상한 재확인) | 잠정 |
| 게임 액션 속도 제한 | S8 | 참가자별 초당 5건 | 잠정 |
| 게임 액션 왕복 지연 p95 (sendAction → 전원 view 갱신, 8명) | S11 | ≤ 1000ms | 잠정 |
| 부딪힘→확인 화면 표시 지연 p95 (사람이 탭하는 시간 제외) | S11 | ≤ 2s | 잠정 |
| 양측 수락→WELCOME 지연 p95 (세션 중 신규 합류) | S11 | ≤ 5s | 잠정 |
| 배터리 소모 (1시간 세션, 포그라운드) | S11 | 호스트 ≤ 15%, 클라이언트 ≤ 10% | 잠정 |

## 기계용 값 (`config/thresholds.yaml`)

테스트는 위 표가 아니라 `config/thresholds.yaml`을 읽는다. 두 파일은 함께 고친다. `S0_ThresholdsSyncTest`가 yaml의 모든 키가 이 목록에 있는지 검사한다.

| 위 표의 항목 | yaml 키 |
|---|---|
| 부딪힘 감지 성공률 / 일상 동작 오탐률 | `bump.detect_success_min` / `bump.false_positive_max` |
| 정답 쌍 첫 시도 선택률 N=2/4/8 | `matching.first_try_rate_n2` / `matching.first_try_rate_n4` / `matching.first_try_rate_n8` |
| 정답 쌍 최종 선택률 N=2/4/8 | `matching.final_rate_n2` / `matching.final_rate_n4` / `matching.final_rate_n8` |
| 오매칭 | `matching.wrong_match_max` |
| 다운링크 전달 지연 p95 / resync 완료 | `session.downlink_p95_ms` / `session.resync_max_ms` |
| 게임 액션 왕복 지연 p95 | `e2e.action_roundtrip_p95_ms` |
| 부딪힘→확인 화면 / 양측 수락→WELCOME | `e2e.bump_to_confirm_p95_ms` / `e2e.accept_to_welcome_p95_ms` |
| 배터리 호스트 / 클라이언트 | `e2e.battery_host_pct_per_hour` / `e2e.battery_client_pct_per_hour` |

`max_links`, 메시지 크기·속도 한도처럼 동작을 정하는 값은 기준치가 아니라 설정이므로 `config/default.yaml`에 있다.

## 금지어 목록 (I-2, `GAME_TERMS`)

`check-invariants.sh`가 `core/`에서 **단어 경계 기준**(`grep -rniw`)으로 검사한다. 이 초안으로 진행한다(D5). 오탐·누락이 나오면 사유와 함께 고친다.
일반 단어와 겹치는 `go`·`board`·`card`·`score` 같은 짧은 단어는 오탐이 많아 초안에서 뺐다. 필요하면 식별자 형태(`goBoard`, `cardDeck`)로 추가한다.

```
GAME_TERMS=(chess baduk janggi omok yutnori monopoly catan uno poker deck dice turn_order)
```

## 변경 기록

| 날짜 | 항목 | 이전 → 새 값 | 근거 |
|---|---|---|---|
| 2026-11-02 | 범프 후 상대 bumpSeq 관측 지연 p95 (`실측` 첫 측정, 값 변경 아님) | 실측 → Android 2대 p95 351ms / 286ms (각 10회) | `docs/gates/evidence/S11/README.md` §2. `bump_flag_wait_ms` 800은 그대로 둔다 — 확정은 사용자 판단(운영 규칙 2: S11에서 한 번) |

# BLE 근거리 매칭 보드게임 참여 모듈 — CLI 구현 명세

> **개정 안내 (2026-10-28)**: N명 세션(호스트 중심 스타)과 관전자 역할이 추가되어 단계가 S0~S11로 바뀌었습니다.
> 진행 상태와 단계 문서는 `docs/spec/README.md`, 이 문서와 다른 부분은 `docs/spec/01-architecture.md`가 우선합니다.
> 게이트 기록은 `docs/gates/SX-done.md` 대신 각 단계 문서의 "검증 기록"에 남깁니다.
> §6.3·§10의 "기준치 사후 조정 금지"는 "잠정값으로 진행하고 S11 실측 후 한 번 기록과 함께 확정"으로 바뀌었습니다(`docs/thresholds.md`).

> 이 문서는 CLI 기반 코딩 에이전트(예: Claude Code)가 단계별로 읽고 실행할 수 있도록 작성되었습니다.
> 작업 전 반드시 §0 → §3 → 현재 단계 섹션 순서로 읽으세요.

---

## 0. 에이전트 시작 절차 (Bootstrap)

모든 작업 세션은 아래 순서로 시작합니다.

```bash
# 1) 현재 진행 단계 확인: 마지막으로 통과한 게이트를 찾는다
ls docs/gates/ 2>/dev/null | sort -V

# 2) 직전 게이트 문서 존재 확인 (S0은 예외)
test -f docs/gates/S(N-1)-done.md && echo "OK" || echo "BLOCKED"

# 3) 구조 불변 조건 검사 (항상 먼저 실행)
./scripts/check-invariants.sh

# 4) 누적 회귀 테스트 (S0 ~ 직전 단계)
./scripts/test-regression.sh --through S(N-1)
```

**판정 규칙**
- 직전 게이트 문서가 없으면 **작업 중지**. 다음 단계 코드를 작성하지 않는다.
- 3번 또는 4번이 실패하면 **현재 단계 작업 전에 실패를 먼저 해결**한다.
- 게이트 문서가 있어도 4번 회귀 테스트가 실패하면 그 게이트는 무효로 간주한다.

> 주의: `scripts/` 아래 스크립트는 S0 단계에서 생성한다. S0 이전에는 이 스크립트가 없으므로 §3과 §6에 정의된 검사를 직접 수행하고 그 결과를 `docs/gates/S0-done.md`에 기록한다.

---

## 1. 목적과 범위

이 모듈의 책임은 다음 4가지로 한정한다.

1. 두 기기의 부딪힘(Bump) 이벤트를 감지한다.
2. BLE로 근거리 후보를 찾고, 시간창과 신호 세기(RSSI)로 짝을 특정한다.
3. 양측 확인을 거쳐 세션에 참가시킨다.
4. 세션 이후의 게임 진행은 외부 `GameModule`에 위임한다.

**범위 밖**: 보드게임 규칙, 게임 상태 관리, 점수, 게임 UI 세부 구현.

---

## 2. 설계 원칙

| 원칙 | 설명 |
|---|---|
| 독립성 | 코어 모듈은 어떤 보드게임도 알지 못한다. 게임은 `GameModule` 인터페이스로만 연결한다. |
| 계층 분리 | 센서, BLE, 매칭, 세션, 게임 연결을 각각 독립 계층으로 둔다. |
| 플랫폼 격리 | iOS/Android 고유 API는 어댑터 뒤에만 존재한다. |
| 결정적 상태 전이 | 상태 변경은 명시된 전이표(reducer)를 통해서만 일어난다. |
| 테스트 가능성 | 하드웨어 의존 계층은 모두 Fake로 교체 가능해야 한다. |
| 오인 매칭 방지 | 자동 매칭은 항상 양측 확인을 거친다. |

---

## 3. 목표 아키텍처와 디렉터리 구조

### 3.1 계층 구조

```
Presentation (UI)
   │  상태 구독 / 이벤트 전달
   ▼
Application Core  (순수 로직, 플랫폼 무관)
   - MatchStateMachine   (전이표 + reducer)
   - PeerMatcher         (시간창 + RSSI 선택)
   - SessionManager      (참가자, 생명주기)
   - GameHost            (GameModule 호출 중계)
   │                 │
   ▼ 인터페이스       ▼ 인터페이스
Sensing Adapter    Transport Adapter (BLE)
 BumpDetector        Advertiser / Scanner
                     IdRotator / Codec

GameModule (외부, 교체 가능): onJoin / onEvent / onLeave / onSessionEnd
```

**의존 방향**: `Presentation → Core → Interface ← Adapter`. Core는 Adapter 구현을 import하지 않는다.

### 3.2 디렉터리 구조 (S0에서 생성)

```
repo/
├── core/                      # 순수 로직. 플랫폼/게임 import 금지
│   ├── state/                 # MatchStateMachine, 전이표
│   ├── matching/              # PeerMatcher
│   ├── session/               # SessionManager
│   ├── host/                  # GameHost
│   └── interfaces/            # BumpSensor, BleTransport, GameModule, Clock
├── adapters/
│   ├── sensing/               # BumpDetector 실구현 (iOS/Android)
│   └── ble/                   # Advertiser, Scanner, IdRotator, Codec (플랫폼별)
├── game-host/                 # GameModule 로딩/격리 레이어
├── ui/                        # 화면, 상태 구독
├── fakes/                     # FakeBumpSensor, FakeBleTransport(가상 링크), FakeGameModule
├── tests/
│   ├── unit/
│   ├── regression/            # 단계별 누적 테스트 (S0_, S1_, ... 접두사)
│   └── fixtures/              # 센서 트레이스, 페이로드 샘플
├── scripts/
│   ├── check-invariants.sh    # §4 구조 검사
│   ├── test-regression.sh     # 누적 회귀 실행
│   └── gate-report.sh         # 게이트 문서 초안 생성
├── docs/
│   ├── adr/                   # ADR-NNN-*.md
│   ├── gates/                 # SX-done.md
│   └── thresholds.md          # 사전 정의 기준치 (§6.3)
└── config/
    └── default.yaml           # 타임아웃, 시간창, 임계치 등 설정값
```

---

## 4. 구조 불변 조건 (Structural Invariants)

아래 조건은 **모든 단계에서 계속 참이어야 한다.** 하나라도 깨지면 해당 단계는 완료가 아니다.

| ID | 조건 | 자동 검사 방법 |
|---|---|---|
| I-1 | Core는 Adapter, UI, game-host를 import하지 않는다 | `check-invariants.sh`: import 그래프 검사 |
| I-2 | Core에 특정 게임명, 게임 전용 타입, 게임 규칙 상수가 없다 | 금지어 grep (`docs/thresholds.md`의 금지어 목록) |
| I-3 | 센서/BLE/게임은 `BumpSensor`, `BleTransport`, `GameModule`로만 접근한다 | 인터페이스 정의 존재 확인 + Core가 구현체 생성 금지 검사 |
| I-4 | 상태는 `MatchStateMachine.transition()`으로만 변경된다 | 상태 필드 직접 대입 grep 0건 |
| I-5 | 플랫폼 API(`CoreBluetooth`, `android.*`, `UIKit` 등)는 `adapters/`에만 있다 | 플랫폼 네임스페이스 grep (Core, UI) |
| I-6 | `Confirming → Matched` 외에 `Matched`로 가는 전이가 없다 | 전이표 전수 테스트 |
| I-7 | BLE 송출 식별자는 `IdRotator`가 만든 임시 값이다 | S2 바이트 검사 테스트 |
| I-8 | 모든 BLE 페이로드에 프로토콜 버전이 있고, 불일치 시 거부된다 | Codec 테스트 |
| I-9 | `Fake*` 구현이 존재하며 Core 테스트는 Fake만으로 실행된다 | CI에서 실기기 없이 `core` 테스트 실행 |
| I-10 | I-1~I-9 또는 인터페이스 시그니처 변경은 ADR 승인 후에만 한다 | `docs/adr/`에 대응 ADR 존재 확인 (PR 규칙) |

### 4.1 `check-invariants.sh` 필수 구현 항목

```bash
#!/usr/bin/env bash
# 실패 시 non-zero exit. 각 검사는 개별 라벨로 출력한다.
set -euo pipefail

fail=0
check() { echo "[check] $1"; }

# I-1: Core의 금지 import
check "I-1 dependency direction"
if grep -rE "from ['\"](\.\./)*(adapters|ui|game-host)" core/ 2>/dev/null; then
  echo "FAIL I-1"; fail=1
fi

# I-2: 게임 식별자 (docs/thresholds.md의 GAME_TERMS 목록 사용)
check "I-2 game-agnostic core"
# ... 금지어 grep 구현

# I-4: 상태 직접 대입 금지
check "I-4 single transition path"
# ... `state\s*=` 패턴을 reducer 외부에서 검색

# I-5: 플랫폼 API 격리
check "I-5 platform isolation"
# ... CoreBluetooth|UIKit|android\. 를 core/, ui/ 에서 검색

[ "$fail" -eq 0 ] && echo "ALL INVARIANTS PASS" || { echo "INVARIANTS FAILED"; exit 1; }
```

> 에이전트 지침: 위 스크립트의 `# ...` 부분은 S0에서 실제 명령으로 채운다. 검사가 실패하면 **검사를 완화하지 말고 코드를 고친다.** 예외가 꼭 필요하면 ADR을 작성하고 화이트리스트에 사유와 함께 추가한다.

---

## 5. 단계 운영 규칙

### 5.1 게이트 원칙

1. 각 단계는 **진입 조건 → 구현 → 검증 → 게이트 통과** 순서로 진행한다.
2. 검증은 **현재 단계 항목 + 직전 모든 단계의 회귀 항목**을 포함한다.
3. 하나라도 실패하면 다음 단계 작업을 시작하지 않는다.
4. 통과 시 `docs/gates/SX-done.md`를 작성한다(형식은 §5.3).
5. 이전 단계 테스트를 삭제하거나 비활성화하면 게이트 실패로 간주한다.

### 5.2 단계 목록

| 단계 | 주제 | 핵심 산출물 |
|---|---|---|
| S0 | 전체 구조 수립 | 디렉터리, 인터페이스, Fake, 구조 검사 스크립트 |
| S1 | 도메인 모델과 상태 기계 | 상태/이벤트, 전이표, reducer |
| S2 | BLE 전송 계층 | Advertiser/Scanner, IdRotator, Codec |
| S3 | 부딪힘 감지 | BumpDetector, 타임스탬프, 디바운스 |
| S4 | 근거리 매칭 | PeerMatcher, 시간창, RSSI 선택 |
| S5 | 양측 확인과 세션 합류 | 확인 프로토콜, 타임아웃, SessionManager |
| S6 | 게임 연결 | GameModule, GameHost, FakeGameModule |
| S7 | 폴백과 권한/UX | 세션 코드, QR, 수동 선택, 권한 처리 |
| S8 | 보안, 프라이버시, 안정화 | nonce, 속도 제한, 로그 정책, 고아 세션 정리 |
| S9 | 크로스플랫폼 E2E와 성능 | 기기 매트릭스, 성능 기준 충족 |

### 5.3 게이트 문서 형식 (`docs/gates/SX-done.md`)

```markdown
# SX 게이트 결과

- 날짜: YYYY-MM-DD
- 커밋: <git rev-parse --short HEAD>
- 실행 명령:
  - ./scripts/check-invariants.sh → PASS
  - ./scripts/test-regression.sh --through SX → PASS (N/N)
- 테스트 기기: (실기기 단계만) 모델, OS 버전
- 기준치 대비 결과: (thresholds.md 항목별 실측값)
- 실패 후 수정 내역: (없으면 "없음")
- 알려진 제한 사항:
```

`scripts/gate-report.sh SX`는 위 골격을 생성하고, 명령 실행 결과를 자동으로 채운다. 실측값은 사람이 확인한 뒤 채운다.

---

## 6. 공통 준비 (S0 이전에 확정)

### 6.1 ADR 규칙

- 파일명: `docs/adr/ADR-NNN-<slug>.md`
- 필수 항목: 상태(Proposed/Accepted/Superseded), 맥락, 결정, 대안, 영향 범위
- 구조 불변 조건 변경, 인터페이스 시그니처 변경, 플랫폼 프레임워크 선택은 반드시 ADR이 필요하다.

### 6.2 설정 분리 원칙

타임아웃, 시간창, RSSI 임계치, 회전 주기, 속도 제한 값은 코드에 하드코딩하지 않고 `config/default.yaml`에 둔다.

```yaml
# config/default.yaml (예시, 실제 값은 thresholds.md 기준으로 확정)
matching:
  window_ms: 300
  rssi_min_dbm: -70
  weight_time: 0.6
  weight_rssi: 0.4
timeouts:
  candidate_ms: 5000
  confirming_ms: 8000
ble:
  id_rotate_sec: 300
  protocol_version: 1
bump:
  threshold_g: 2.5
  debounce_ms: 500
security:
  nonce_ttl_sec: 30
  max_connect_per_min: 10
```

### 6.3 기준치 사전 정의 (`docs/thresholds.md`)

구현 시작 전에 아래 항목의 기준치를 확정하고 커밋한다. **구현 후 결과를 보고 값을 바꾸지 않는다.** 바꿔야 하면 ADR을 쓴다.

| 항목 | 단계 | 기준치 (예시, 확정 필요) |
|---|---|---|
| 부딪힘 감지 성공률 | S3 | 10회 중 9회 이상 |
| 일상 동작 오탐률 | S3 | 트레이스 100건 중 2건 이하 |
| 정답 쌍 선택률 (N=2/4/8) | S4 | 각각 95% / 90% / 80% 이상 |
| 실기기 광고 수신 성공 | S2 | 교차 플랫폼 최소 1회 이상 |
| 부딪힘→매칭 완료 지연 | S9 | p95 기준 이하 (값 확정 필요) |
| 배터리 소모 | S9 | 장시간 실행 기준 이하 (값 확정 필요) |
| 금지어 목록 (I-2) | S0 | 게임명, 게임 전용 상수 목록 |

---

## 7. 단계별 명세와 검증 항목

각 단계는 **진입 조건 / 구현 항목 / 검증 항목**으로 구성한다.
`[R]`은 이전 단계에서 가져온 회귀 항목이다. 모든 단계는 §4의 구조 불변 조건을 기본으로 포함한다.

---

### S0. 전체 구조 수립

**진입 조건**: 요구사항 문서와 이 명세 승인. §6 준비 완료.

**구현 항목**
- §3.2 디렉터리 구조 생성
- 인터페이스 정의: `BumpSensor`, `BleTransport`, `GameModule`, `Clock`
- Core 골격: `MatchStateMachine`, `PeerMatcher`, `SessionManager`, `GameHost` (빈 구현 또는 최소 스텁)
- Fake 구현: `FakeBumpSensor`, `FakeBleTransport`(메모리 가상 링크 포함), `FakeGameModule`
- `scripts/check-invariants.sh`, `scripts/test-regression.sh`, `scripts/gate-report.sh` 작성
- 크로스플랫폼 프레임워크 확정 및 ADR-001 작성 (Flutter / React Native / 네이티브 2종 + 공유 로직 중 택1)
- `docs/thresholds.md` 초안 작성

**검증 항목**
- [ ] 빌드: iOS, Android 대상 빈 앱이 빌드되고 실행된다.
- [ ] I-1: 의존 방향 검사 통과 (위반 0건).
- [ ] I-2: Core에 금지어 0건.
- [ ] I-3: 세 인터페이스가 정의되고, Core는 구현체 대신 인터페이스를 주입받는다.
- [ ] I-9: `FakeBleTransport`로 가상 기기 두 대가 메시지를 주고받는 단위 테스트 통과.
- [ ] I-10: ADR-001 작성 및 Accepted 상태.
- [ ] `docs/thresholds.md` 존재.
- [ ] `docs/gates/S0-done.md` 존재.

---

### S1. 도메인 모델과 상태 기계

**진입 조건**: S0 게이트 통과.

**구현 항목**
- 상태: `Idle`, `Advertising`, `Candidate`, `Confirming`, `Matched`, `Joined`, `Failed`
- 이벤트: `BumpDetected`, `CandidateFound`, `ConfirmSent`, `ConfirmReceived`, `Timeout`, `Cancel`, `SessionJoined`, `Error`
- 전이표를 데이터(테이블)로 정의하고 `transition(state, event) -> (newState, effects)`를 순수 함수로 구현
- 타임아웃 값은 설정에서 주입
- 상태 변경 로그 구조 (디버깅용, 개인정보 미포함)

**검증 항목**
- [ ] S0 검증 전체 [R]
- [ ] I-4: 상태 필드 직접 대입 0건.
- [ ] I-6: 전이표에서 `Matched`로 들어가는 전이는 `Confirming → Matched` 하나뿐이다.
- [ ] 전이표 전수 테스트: 모든 (상태, 이벤트) 조합의 기대 결과가 검증된다. 미정의 조합은 `Error` 또는 무시로 처리되고 테스트된다.
- [ ] 타임아웃: `Candidate`, `Confirming` 각각 Clock Fake로 시간 경과 시 `Idle` 또는 `Failed`로 복귀한다.
- [ ] 멱등성: 같은 이벤트를 두 번 넣어도 상태가 두 번 진행하지 않는다.

---

### S2. BLE 전송 계층

**진입 조건**: S1 게이트 통과.

**구현 항목**
- `Advertiser`, `Scanner`를 플랫폼별로 구현 (I-5 준수)
- `IdRotator`: 주기적으로 바뀌는 임시 ID 생성 (I-7)
- `Codec`: 버전 필드를 포함한 인코딩/디코딩 (I-8)
- 광고 페이로드 크기를 플랫폼 용량 한도 내로 설계
- 스캔 결과를 `PeerSeen(tempId, rssi, timestamp)` 이벤트로 Core에 전달

**검증 항목**
- [ ] S0~S1 검증 전체 [R]
- [ ] I-7: 송출 페이로드 바이트 검사에서 계정 ID, MAC, UDID 등이 0건.
- [ ] I-8: 버전이 다른 페이로드는 디코딩에서 거부되고 이벤트가 발생하지 않는다.
- [ ] Codec 라운드트립: 인코딩 후 디코딩 결과가 원본과 같다.
- [ ] 페이로드 크기가 플랫폼 광고 용량 이하이다.
- [ ] IdRotator: 회전 경계에서 새 ID가 생성되고, 주기 내에서는 유지된다.
- [ ] 실기기 2대 이상에서 광고 수신이 1회 이상 성공한다 (iOS-Android 교차 포함). 결과를 게이트 문서에 기록.
- [ ] 스캔 중단/재개 시 리소스 누수가 없다.

---

### S3. 부딪힘 감지

**진입 조건**: S2 게이트 통과.

**구현 항목**
- `BumpDetector`: 가속도계 임계치 초과 스파이크 감지
- 단조 증가 타임스탬프 (`Clock` 사용)
- 디바운스로 중복 감지 방지
- 센서 트레이스 기록 및 재생(replay) 기능

**검증 항목**
- [ ] S0~S2 검증 전체 [R]
- [ ] 재생 테스트: `tests/fixtures/`의 실제 부딪힘 트레이스 재생 시 기대 시점에 `BumpDetected`가 1회 발생한다.
- [ ] 오탐 코퍼스: 걷기, 뛰기, 주머니에서 꺼내기, 테이블 가볍게 두드리기 트레이스에서 `BumpDetected` 0건 (허용 오탐률은 thresholds.md 기준).
- [ ] 타임스탬프 역행 0건.
- [ ] 디바운스: 설정 간격 내 연속 스파이크는 1회로 처리된다.
- [ ] 실기기 10회 부딪힘 시험의 감지 성공률이 thresholds.md 기준을 충족한다.

---

### S4. 근거리 매칭

**진입 조건**: S3 게이트 통과.

**구현 항목**
- `PeerMatcher`: `BumpDetected` 시점 ±window 안의 `PeerSeen` 중 후보 선택
- 기준: 시간 근접도 + RSSI 가중합. 가중치는 설정으로 분리
- 후보 0명 → `Failed`, 1명 → `Candidate`, 2명 이상 또는 동률 → 수동 선택 경로
- 같은 임시 ID의 중복 관측은 하나로 병합

**검증 항목**
- [ ] S0~S3 검증 전체 [R]
- [ ] 시뮬레이션: 참가자 N=2, 4, 8 환경에서 정답 쌍 선택률이 thresholds.md 기준 이상이다.
- [ ] 시간창 밖의 후보는 선택되지 않는다 (경계값 테스트 포함).
- [ ] RSSI가 임계치 미만인 후보만 있으면 자동 매칭하지 않는다.
- [ ] 동률/복수 후보에서는 `Candidate` 확정이 아니라 수동 선택 경로로 간다.
- [ ] 중복 관측 병합: 후보 수가 부풀지 않는다.
- [ ] 실기기 3대 이상에서 정답 쌍 선택 성공 여부를 기록한다.

---

### S5. 양측 확인과 세션 합류

**진입 조건**: S4 게이트 통과.

**구현 항목**
- 확인 프로토콜: A가 B 선택 → 확인 요청 → B 수락 → 양측 `Matched` → 세션 합류
- 한쪽만 확인된 상태는 타임아웃으로 정리
- `SessionManager`: 세션 생성, 참가자 추가/제거, 세션 코드 발급
- 연결 끊김 후 재시도 정책 정의

**검증 항목**
- [ ] S0~S4 검증 전체 [R]
- [ ] I-6: 한쪽 확인만으로 `Matched`가 되는 테스트 경로가 존재하지 않는다.
- [ ] 한쪽 확인 후 타임아웃: 양쪽 모두 `Idle` 또는 `Failed`, 세션 리소스 잔존 0.
- [ ] 양측 확인 성공 시 두 기기의 세션 ID가 일치한다.
- [ ] 참가자 추가/제거 후 양쪽 목록이 일치한다.
- [ ] 확인 메시지 중복 수신 시 참가자가 중복 추가되지 않는다.
- [ ] 실기기 2대에서 확인 전 과정이 성공하고, 끊김 후 재시도가 정책대로 동작한다.

---

### S6. 게임 연결

**진입 조건**: S5 게이트 통과.

**구현 항목**
- `GameModule` 인터페이스: `onJoin(session)`, `onLeave(player)`, `onEvent(event)`, `onSessionEnd()`
- `GameHost`: 세션 이벤트를 게임에 전달하고, 게임 이벤트를 세션에 반영
- 매칭된 상대에게 게임 참여 초대를 보내고, 수락 시 `onJoin` 호출
- `FakeGameModule`로 전체 흐름 검증. 실제 보드게임 모듈은 별도 저장소에서 개발

**검증 항목**
- [ ] S0~S5 검증 전체 [R]
- [ ] I-2: GameModule 구현 교체 시 `core/` 변경 0줄 (`git diff --stat core/` 확인).
- [ ] 계약 테스트: 어떤 GameModule이든 `onJoin → onEvent* → onSessionEnd` 순서를 지키면 GameHost가 오류 없이 동작한다.
- [ ] 게임 모듈 예외가 발생해도 세션 코어가 중단되지 않는다.
- [ ] 초대 수락 전에는 `onJoin`이 호출되지 않는다.
- [ ] 세션 종료 시 `onSessionEnd`가 정확히 1회 호출된다.

---

### S7. 폴백, 권한, UX

**진입 조건**: S6 게이트 통과.

**구현 항목**
- 폴백: 세션 코드 입력, QR 생성/스캔, 근처 기기 목록 수동 선택
- 블루투스 꺼짐, 권한 거부, 백그라운드 제한의 상태 안내 UI
- `Failed` 진입 시 다음 행동을 안내하는 UI 상태
- 권한 요청 시점과 사유 문구 정의

**검증 항목**
- [ ] S0~S6 검증 전체 [R]
- [ ] 블루투스 OFF에서 크래시 없이 안내 화면이 표시된다.
- [ ] 권한 거부 후 재요청 경로가 동작하고, 거부 상태에서도 세션 코드 참여가 가능하다.
- [ ] 세션 코드/QR 합류 기기가 S5의 세션 정책을 동일하게 따른다.
- [ ] 수동 선택 경로에서도 I-6(양측 확인)이 유지된다.
- [ ] iOS 백그라운드 전환 시 스캔이 중단되고, 복귀 시 상태가 복원된다.

---

### S8. 보안, 프라이버시, 안정화

**진입 조건**: S7 게이트 통과.

**구현 항목**
- 재생 공격 방지: 확인 메시지에 nonce와 만료 시각 포함
- 연결 요청 속도 제한
- 로그 정책: 임시 ID 마스킹, 위치 미기록, 보관 기간 정의
- 앱 재시작 시 진행 중 세션 정리

**검증 항목**
- [ ] S0~S7 검증 전체 [R]
- [ ] 재생 테스트: 캡처한 확인 메시지를 재전송해도 참가하지 않는다.
- [ ] 만료된 nonce가 거부된다.
- [ ] 속도 제한 초과 요청이 차단되고, 해제 후 정상 동작한다.
- [ ] 로그 검사: 임시 ID 원본, 위치 값, 계정 정보 0건.
- [ ] 강제 종료 후 재실행 시 고아 세션이 남지 않는다.
- [ ] 퍼징: 잘못된 형식의 페이로드 입력에서 크래시 0건.

---

### S9. 크로스플랫폼 E2E와 성능

**진입 조건**: S8 게이트 통과.

**구현 항목**
- 기기 매트릭스 정의 (iOS 2종 이상, Android 3종 이상 권장)
- E2E 시나리오: 세션 생성 → 부딪힘 → 매칭 → 확인 → 게임 합류 → 세션 종료
- 성능 측정: 매칭 지연, 배터리 소모, 동시 참가자 수
- 릴리스 체크리스트 작성

**검증 항목**
- [ ] S0~S8 검증 전체 [R]
- [ ] E2E가 기기 매트릭스 전 조합에서 기준 성공률을 충족한다.
- [ ] 부딪힘→매칭 지연이 thresholds.md의 p95 기준 이하이다.
- [ ] 동시 N명에서 정답 쌍 선택률이 S4 기준 이상을 유지한다.
- [ ] 장시간 백그라운드 배터리 소모가 기준 이하이다.
- [ ] `check-invariants.sh`(I-1~I-10)가 릴리스 빌드에서도 통과한다.
- [ ] 릴리스 체크리스트 전 항목 완료.

---

## 8. 테스트 실행 규약 (CLI 공통)

| 목적 | 명령 (프레임워크에 맞게 구현) | 설명 |
|---|---|---|
| 구조 검사 | `./scripts/check-invariants.sh` | 항상 가장 먼저 실행 |
| 단위 테스트 | `./scripts/test-unit.sh` | Fake만 사용, 실기기 불필요 |
| 회귀 테스트 | `./scripts/test-regression.sh --through SN` | S0~SN 누적 실행 |
| 실기기 테스트 | `./scripts/test-device.sh --stage SN` | 연결된 기기 필요, CI 외부 실행 |
| 게이트 초안 | `./scripts/gate-report.sh SN` | `docs/gates/SN-done.md` 초안 생성 |

**규약**
- 회귀 테스트는 `tests/regression/`의 `S{n}_*` 파일을 모두 포함한다. 파일명 접두사로 단계를 식별한다.
- 실기기 테스트 결과는 로그 파일을 `docs/gates/evidence/SN/`에 저장하고 게이트 문서에서 링크한다.
- 테스트 중 임시 ID 원본은 로그에 남기지 않는다.

---

## 9. 단계 진행 절차 (에이전트 작업 지침)

각 단계를 다음 순서로 수행한다.

1. §0 Bootstrap 실행. 직전 게이트 문서 확인.
2. 이번 단계 검증 항목을 **테스트 코드로 먼저 작성**한다. 실패 상태를 확인한다.
3. 구현한다.
4. `./scripts/check-invariants.sh` 실행.
5. `./scripts/test-regression.sh --through SN` 실행.
6. 실패 항목이 있으면 원인과 수정 내역을 기록하고 재실행한다. **테스트를 약화하거나 삭제해서 통과시키지 않는다.**
7. 실기기 항목이 있으면 `./scripts/test-device.sh --stage SN`을 실행하고 증거를 저장한다.
8. 모든 항목 통과 시 `./scripts/gate-report.sh SN`으로 초안을 만들고, 실측값과 제한 사항을 채운 뒤 커밋한다.
9. 다음 단계로 넘어간다.

**커밋 규칙 (권장)**
- 단계당 최소 1개 커밋: `feat(SN): <요약>`, 게이트는 `gate(SN): done`
- 구조 변경은 ADR 커밋을 먼저 한다: `docs(adr): ADR-NNN <요약>`

---

## 10. 금지 사항 (Anti-patterns)

- 게임 로직을 Core나 BLE 계층에 넣지 않는다.
- 실기기 테스트 없이 단위 테스트만으로 BLE 단계를 완료 처리하지 않는다.
- 자동 매칭을 한쪽 확인만으로 확정하지 않는다.
- 플랫폼 API를 UI 코드에서 직접 호출하지 않는다.
- 이전 단계 테스트를 수정해서 현재 단계를 통과시키지 않는다.
- 기준치를 결과를 보고 사후에 조정하지 않는다.
- 임시 ID 대신 기기 고유 식별자나 계정 정보를 BLE로 송출하지 않는다.
- 정책 값(타임아웃, 임계치, 허용 오탐률)을 코드에 하드코딩하지 않는다. `config/`로 분리한다.
- 검사 스크립트가 실패할 때 스크립트를 고쳐서 통과시키지 않는다. 코드를 고치거나 ADR을 쓴다.

---

## 11. 완료 정의 (Definition of Done)

다음 조건을 모두 만족하면 이 모듈은 완료로 본다.

- `docs/gates/S0-done.md` ~ `S9-done.md`가 모두 존재하고 전 항목이 통과 상태다.
- I-1 ~ I-10이 최종 빌드에서 모두 유지된다.
- 새 `GameModule`을 붙여도 `core/` 변경이 없다 (계약 테스트 통과).
- 플랫폼 매트릭스 E2E가 기준 성공률을 충족한다.
- `docs/adr/`이 최신 상태이며 모든 구조 변경에 대응하는 ADR이 있다.

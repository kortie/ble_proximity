# BLE Proximity

휴대폰끼리 **가볍게 부딪혀(범프)** 근처 상대를 찾고, 양쪽 화면에서 확인한 뒤 보드게임 세션에 **플레이어 또는 관전자**로 합류시키는 Android 라이브러리입니다.
BLE에 관한 모든 것(광고·스캔·연결·암호화·재연결·게임 데이터 전송)을 이 라이브러리가 맡습니다. 게임은 BLE를 모른 채 `GameModule`(규칙)과 `GameClient`(화면)만 구현하면 됩니다.

- 대상: Android 12 이상 (minSdk 31), Kotlin, BLE 광고(주변 기기 역할)를 지원하는 기기
- 버전: 0.1.0 (v1은 Android만 지원)
- 한 세션: 호스트 포함 최대 8명 + 관전자 (기기의 동시 연결 수에 따라 줄어듦)

---

## 1. 패키지 구성

| 파일 | 내용 | 앱에 필요 |
|---|---|---|
| `proximity-core-<ver>.aar` | 본체: 매칭, 양측 확인, 세션, 게임 데이터 API, 앱 진입점 `ProximityNode` | 필수 |
| `proximity-platform-<ver>.aar` | Android 구현: BLE 광고·스캔·GATT, 가속도 센서, 무선 상태 확인, 권한 선언 | 필수 |
| `proximity-crypto-<ver>.jar` | 암호 구현: X25519, HKDF, ChaCha20-Poly1305 (BouncyCastle) | 필수 |
| `proximity-testing-<ver>.aar` | 가짜 BLE 망·시계·게임, **GameModule 계약 테스트 키트** | 게임 테스트용 |
| `proximity-default.yaml` | 기본 정책 값 (시간 초과·인원·크기 한도 등). 앱 assets에 넣는다 | 필수 |
| `DEPENDENCIES.md` | Gradle 설정과 외부 의존 버전 | - |
| `SHA256SUMS` | 파일 체크섬 (`sha256sum -c SHA256SUMS`) | - |

---

## 2. 구조

### 2.1 계층

```
 ┌──────────────── 앱 (게임 저장소) ────────────────┐
 │  화면(UI) ── UiState를 그린다 / GameClient로만 게임 데이터 │
 │  GameModule ── 게임 규칙 (호스트 기기에서만 실행)          │
 └───────▲───────────────────────▲────────────────────┘
         │ ui, gameClient        │ GameHostContext
 ┌───────┴───────────────────────┴──── proximity-core ─┐
 │ ProximityNode (파사드)                               │
 │  ├ 매칭: 광고·스캔 → 범프 감지 → 후보 선택(F1~F8)      │
 │  ├ 확인: 임시 링크에서 X25519 키 합의 + 확인 코드(SAS)  │
 │  ├ 세션: 호스트 중심 스타, 메시지 암호화·순서·재전송     │
 │  └ 게임: GameHost(호스트) / GameClient(모든 기기)       │
 └───────▲──────────────────────────▲──────────────────┘
         │ BleTransport·SessionTransport…  │ CryptoBox
 ┌───────┴──────── proximity-platform ┐ ┌──┴── proximity-crypto ┐
 │ AndroidRadio, AndroidBumpSensor,    │ │ BouncyCastleCryptoBox │
 │ AndroidClock, RadioCheck            │ └───────────────────────┘
 └─────────────────────────────────────┘
```

core는 Android API를 직접 부르지 않고 인터페이스만 씁니다. 그래서 core 전체를 실기기 없이 JVM에서 시험할 수 있습니다.

### 2.2 세션 모양 (호스트 중심 스타)

```
          플레이어 B ──┐
          플레이어 C ──┼── 호스트 H (GameModule 실행, 순서 결정)
          관전자  S ──┘        └─ 호스트 자신의 화면도 GameClient(loopback)
```

- 모든 메시지는 호스트를 거칩니다. 호스트가 하나의 순서를 정하므로 **모든 기기가 같은 순서**로 받습니다.
- 수신자는 GameModule이 정합니다. 비공개 정보(손패 등)는 그 플레이어에게만 갑니다.
- 관전자는 게임 액션을 보낼 수 없고(기기와 호스트 양쪽에서 차단), social 메시지는 보낼 수 있습니다.

### 2.3 범프로 합류하는 흐름

```
 기기 X(새로 옴)                        기기 H(방을 연 호스트)
   │ 범프 ─────── 서로의 광고에서 bumpSeq 변화를 봄 ─────── 범프 │
   │ 후보 선택(신호 세기·범프 시각)              후보 선택       │
   │──────── 임시 링크(GATT) 연결, 키 합의 ──────────────────────>│
   │  확인 화면: 상대 닉네임 + 확인 코드 "42"      확인 화면 "42" │
   │  [수락] ─────────── 양쪽 모두 수락해야 진행 ─────────── [수락] │
   │<──────── 합류 정보(1회용 token) ────────────────────────────│
   │──────── 세션 링크 연결 · HELLO ─────────────────────────────>│
   │<──────── WELCOME · 현재 게임 상태(스냅샷) ──────────────────│
```

- 진행 중인 세션의 **멤버와 부딪혀도** 그 멤버가 호스트에게서 token을 받아 대신 소개합니다.
- 실기기 측정(Android 2대): 부딪힘→확인 화면 p95 1.7초, 양측 수락→합류 p95 1.5초, 게임 액션 왕복 p95 156ms.

---

## 3. 설치

1. 패키지 파일을 앱의 `libs/`에, `proximity-default.yaml`을 `src/main/assets/`에 넣습니다.
2. `build.gradle.kts`:

```kotlin
android {
    defaultConfig { minSdk = 31 }
}

dependencies {
    implementation(files("libs/proximity-core-0.1.0.aar"))
    implementation(files("libs/proximity-platform-0.1.0.aar"))
    implementation(files("libs/proximity-crypto-0.1.0.jar"))
    testImplementation(files("libs/proximity-testing-0.1.0.aar"))

    // 라이브러리 파일 배포라 전이 의존을 직접 추가한다 (버전은 DEPENDENCIES.md)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.wire:wire-runtime:5.5.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
}
```

3. 권한(`BLUETOOTH_SCAN`(neverForLocation)·`BLUETOOTH_ADVERTISE`·`BLUETOOTH_CONNECT`)은 platform AAR의 매니페스트가 앱에 합쳐 줍니다. **실행 중 권한 요청은 앱이** 합니다 (`RadioCheck.permissions`). 위치 권한은 필요 없습니다.

---

## 4. 빠른 시작

```kotlin
class GameActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main) // 한 스레드에서 쓴다
    private val clock = AndroidClock()
    private lateinit var node: ProximityNode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = ProximityConfig.fromYaml(assets.open("proximity-default.yaml").bufferedReader().readText())

        // 플랫폼 구현을 만들어 core에 넘긴다
        val radio = AndroidRadio(this, clock, scope, config.matching.attemptTimeoutMs)
        val platform = NodePlatform(
            ble = radio.ble,
            rendezvous = radio.rendezvous,
            sessionHost = radio.sessionHost,
            newSessionClient = radio::newSessionClient,
            bumpSensor = AndroidBumpSensor(this, config.bump.samplePeriodUs), // 센서가 없으면 null
        )
        node = ProximityNode(config, platform, BouncyCastleCryptoBox(), clock, scope, game = MyGame())

        node.setDisplayName("지영")                                       // 확인 화면·참가자 목록에만 쓰임
        scope.launch { RadioCheck.observe(this@GameActivity).collect { node.setRadio(it.toStatus()) } }
        scope.launch { node.ui.collect(::render) }                         // 화면 = UiState
        scope.launch { node.gameClient.collect { it?.let(::startGameUi) } } // 세션에 들어가면 GameClient
    }

    override fun onResume() {
        super.onResume()
        node.setRadio(RadioCheck.current(this).toStatus()) // 설정에서 권한을 켜고 돌아온 경우
    }

    override fun onDestroy() {
        node.close() // 호스트면 세션 종료(END), 멤버면 나가기, 광고·스캔 정지
        scope.cancel()
        super.onDestroy()
    }

    private fun RadioState.toStatus() = when (this) {
        RadioState.Ready, is RadioState.Failed -> RadioStatus.Ready
        RadioState.BluetoothOff -> RadioStatus.BluetoothOff
        RadioState.Unsupported -> RadioStatus.Unsupported
        is RadioState.PermissionMissing -> RadioStatus.PermissionNeeded // 영구 거부면 PermissionDeniedForever
    }
}
```

`ProximityNode`의 모든 호출은 같은 스레드(위 예의 메인)에서 합니다. 전체 예시는 저장소의 `sample-android/.../SessionLabActivity.kt`(Proximity Session 앱)에 있습니다.

### 4.1 `ProximityNode` API

| 호출 | 하는 일 |
|---|---|
| `ui: StateFlow<UiState>` | 지금 보여 줄 화면과 누를 수 있는 동작 (§5) |
| `gameClient: StateFlow<GameClient?>` | 세션에 들어가면 값이 생긴다. 게임 UI에는 이것만 넘긴다 |
| `setDisplayName(name): Boolean` | 닉네임 (최대 32바이트). 광고에는 싣지 않는다 |
| `setRadio(status)` | 블루투스·권한 상태. `Ready`가 되면 광고·스캔 시작 |
| `selectRole(Role.Player / Role.Spectator)` | 합류 전 역할 |
| `createRoom()` | "방 만들기": 이 기기가 호스트가 된다 (플레이어만) |
| `onBump(bump)` | 센서 대신 직접 범프를 알린다 (버튼 등). 센서를 넘겼으면 자동 |
| `accept()` / `decline()` | 확인 화면에서 수락·거절. **양쪽 모두 수락해야** 합류 |
| `pick(peer)` | 자동으로 못 고른 경우 후보 목록에서 선택 |
| `joinWithCode(code)` | 호스트 화면의 6자리 세션 코드로 합류 (호스트 승인 필요) |
| `joinWithQr(text): Boolean` | 호스트 화면 QR 문자열(`BP1.…`)로 합류 |
| `answerCodeJoin(id, accept)` | 호스트: 코드 합류 요청 승인·거절 |
| `refreshQr()` | 호스트: QR을 새로 만든다 (자동으로도 바뀐다) |
| `cancel()` / `retry()` | 진행 취소 / 실패 화면에서 다시 |
| `leave()` / `endSession()` | 나가기 / 호스트가 세션 끝내기 |
| `close()` | 앱 종료 시 정리 |
| `perf`, `ping()`, `timedAction()` | 지연 측정 (선택) |

---

## 5. 화면 상태 (`UiState`)

화면은 `node.ui`를 **그리기만** 합니다. `UiState.screen`이 지금 화면, `UiState.actions`가 보여 줄 버튼, `UiState.notice`가 안내 문구(거절 사유 등)입니다. 안내 문구는 `UxCopy`에 한국어로 들어 있습니다.

| `Screen` | 언제 | 주요 `UiAction` |
|---|---|---|
| `NeedsName` | 첫 실행 | `SetName` |
| `RadioProblem(status, message)` | 블루투스 꺼짐·권한 없음·미지원 | `TurnOnBluetooth`(`RadioCheck.enableBluetoothIntent()`) / `RequestPermission` / `OpenSettings` |
| `Home(role, canCreateRoom)` | 대기 (범프를 기다림) | `ChooseRole`, `CreateRoom`, `EnterCode`, `ScanQr` |
| `Searching` | 범프 직후 상대를 찾는 중 | `Cancel` |
| `ManualPick(candidates)` | 자동으로 못 고름 → 가까운 순 목록 (`Near`/`Medium`/`Far`) | `PickCandidate` |
| `Confirm(peerName, sas, remainingMs, waitingForPeer)` | **확인 화면**: 상대 닉네임과 확인 코드를 크게. 코드가 같을 때만 수락하라고 안내 | `Accept`, `Decline` |
| `CodeWaiting(sas)` | 코드 합류 중, 호스트 승인 대기 | `Cancel` |
| `Joining` | 세션 연결 중 | `Cancel` |
| `InSession(role, isHost, participants, paused, host)` | 세션 중. `paused`면 재연결 카운트다운. 호스트는 `host`에 세션 코드·QR·코드 합류 요청 | `SendAction`(플레이어만), `SendSocial`, `Leave`, 호스트는 `EndSession`, `ApproveCodeJoin` |
| `Failed(reason, message, next)` | 실패 + 다음에 할 일 | `Retry`, `EnterCode`, `ScanQr`, `ChooseRole` |

관전자의 `InSession`에는 `SendAction`이 **없습니다**. 버튼을 그리지 않으면 됩니다.

---

## 6. 합류 방법 4가지

| 방법 | 쓰는 때 | 확인 방식 |
|---|---|---|
| 범프 | 기본. 휴대폰끼리 가볍게 부딪힘 | 양쪽 확인 화면의 확인 코드 비교 + 양쪽 수락 |
| 방 만들기 → 범프 | 호스트를 미리 정할 때 | 같음 |
| 세션 코드 (6자리) | 부딪히기 어려울 때 | 호스트 화면과 새 기기 화면의 확인 코드 비교 + 호스트 승인 |
| QR (`BP1.…` 문자열) | 화면을 찍을 수 있을 때 | 호스트 화면을 직접 찍음(1회용, 역할은 찍은 기기가 선택) |

QR 이미지는 앱이 그립니다(예: ZXing으로 `InSession.host.qr` 문자열을 QR로). 라이브러리는 문자열만 만듭니다.

---

## 7. 게임 구현

게임 쪽 코드는 `dev.proximity.core.api`와 `GameModule`만 씁니다. BLE·연결·암호는 보이지 않습니다.

### 7.1 호스트 쪽: `GameModule` (규칙)

```kotlin
import dev.proximity.core.api.*
import dev.proximity.core.api.Target // kotlin.annotation.Target과 이름이 겹친다: 명시적으로 가져온다
import dev.proximity.core.interfaces.GameModule

class MyGame : GameModule {
    private var counter = 0
    private val hands = mutableMapOf<ParticipantId, Int>()

    override fun descriptor() = GameDescriptor(
        gameId = "com.example.mygame", gameVersion = 1, minCompatibleVersion = 1,
        minPlayers = 2, maxPlayers = 4, allowSpectators = true,
    )

    override fun onJoinRequest(role: Role, displayName: String) = JoinDecision.Admit // 진행 중 합류 거절 등은 Refuse(code)

    override fun onJoin(ctx: GameHostContext, participant: Participant) {
        if (participant.role == Role.Player) hands[participant.id] = (1..9).random()
        ctx.publishViews()
    }

    override fun onAction(ctx: GameHostContext, sender: Participant, action: ByteArray): ActionResult {
        if (action.size != 1) return ActionResult.Rejected(code = 1) // 차례·규칙 검사는 게임이 한다
        counter += action[0]
        ctx.sendEvent(Target.All, "${sender.displayName} +${action[0]}".encodeToByteArray()) // 연출용
        ctx.publishViews()                                                                  // 상태 갱신
        return ActionResult.Accepted
    }

    // 이 참가자가 화면을 "완전히" 그릴 수 있는 view. 비공개 정보는 여기서 걸러낸다
    override fun viewFor(participant: Participant): ByteArray {
        val hand = hands[participant.id]
        return (if (hand == null) "counter=$counter" else "counter=$counter;hand=$hand").encodeToByteArray()
    }

    override fun onTimer(ctx: GameHostContext, token: String) = Unit
    override fun onLeave(ctx: GameHostContext, participant: Participant, reason: LeaveReason) { hands.remove(participant.id) }
    override fun onSessionEnd() = Unit
}
```

`GameHostContext`(콜백 안에서 받음):

| 호출 | 하는 일 |
|---|---|
| `participants()` | 지금 참가자 (호스트가 첫 번째) |
| `publishViews()` | 상태가 바뀜 → 참가자마다 `viewFor()`를 불러 보냄 |
| `sendEvent(to, bytes)` | `Target.One(id)` / `AllPlayers` / `AllSpectators` / `All` |
| `schedule(delayMs, token)` / `cancel(token)` | 턴 타이머 등 → `onTimer` |
| `kick(participant, code)` | 내보내기 (호스트 자신 제외) |
| `endSession(result)` | 모두에게 결과를 보내고 종료 |
| `now()` | 현재 시각 |

### 7.2 모든 기기: `GameClient` (화면)

```kotlin
fun startGameUi(game: GameClient) {
    scope.launch { game.views.collect { v -> draw(v.data) } }            // 항상 최신 전체 view
    scope.launch { game.events.collect { e -> animate(e.data) } }        // 연출용 (건너뛸 수 있음)
    scope.launch { game.resyncs.collect { showToast("다시 맞췄습니다") } }
    scope.launch { game.participants.collect(::drawPlayers) }
    scope.launch { game.socials.collect { s -> chat(s.from.displayName, s.data) } }
    scope.launch { game.state.collect { if (it is ClientState.Ended) showResult(it.result) } }

    plusButton.setOnClickListener {
        scope.launch {
            when (val r = game.sendAction(byteArrayOf(1))) {
                ActionOutcome.Accepted -> Unit
                is ActionOutcome.Rejected -> toast("규칙 위반 ${r.code}")
                ActionOutcome.NotAllowed -> toast("관전자는 할 수 없습니다")
                ActionOutcome.TooLarge, ActionOutcome.RateLimited, ActionOutcome.NotLive -> toast("잠시 후 다시")
                ActionOutcome.Timeout -> Unit // 적용 여부는 다음 view로 판단
            }
        }
    }
}
```

`ClientState`: `Syncing` → `Live` ⇄ `Paused`(재연결 중) → `Ended(result)`.

### 7.3 지켜야 할 계약

- **view만으로 화면을 그릴 수 있어야** 합니다. 재연결·중간 합류·유실 복구는 view 스냅샷으로 맞춥니다. event는 연출용입니다.
- 호스트 기기의 화면도 `GameModule`을 직접 보지 말고 `GameClient`를 씁니다(같은 경로, 같은 순서).
- 한 `onAction`에서 나온 결과·이벤트·view는 한 묶음으로 전달되어, 중간 상태가 보이지 않습니다.
- 같은 액션은 많아야 1번 적용됩니다(재연결 후 재전송돼도 중복 제거).
- `GameModule`이 예외를 던지면 그 액션만 `Rejected(ActionResult.INTERNAL)`이 되고 세션은 계속됩니다.
- 한도(기본값): action 512B, event 1KB, view 4KB(수신자마다 따로 전송), 액션 초당 5건, social 140B·분당 20건.

---

## 8. 설정 (`proximity-default.yaml`)

모든 키가 필요합니다(빠지면 로드 실패). 자주 보는 값:

| 키 | 기본 | 뜻 |
|---|---|---|
| `session.max_players` / `max_spectators` / `max_links` | 8 / 4 / 7 | 인원 상한 (`GameDescriptor.maxPlayers`와 작은 값). 관전자는 플레이어 자리를 넘어 쓰지 않음 |
| `session.max_display_name_bytes` | 32 | 닉네임 길이 |
| `timeouts.confirming_ms` | 20000 | 확인 화면에서 양쪽이 수락할 시간 |
| `session.join_token_ttl_sec` | 30 | 합류 token 유효 시간 (화면 QR은 자동 갱신) |
| `session.host_grace_ms` / `client_reconnect_ms` | 15000 / 10000 | 호스트 끊김 유예 / 클라이언트 재연결 유예 |
| `transport.keepalive_ms` / `link_timeout_ms` | 2000 / 6000 | 말없이 사라진 상대(앱 강제 종료) 감지 |
| `matching.window_ms` / `bump_flag_wait_ms` | 300 / 800 | 두 범프를 같은 순간으로 보는 범위 / 상대 범프 신호를 기다리는 시간 |
| `bump.threshold_g` | - | 부딪힘 판정 세기 |
| `game.*`, `social.*` | §7.3 | 크기·속도 한도 |

값은 실기기 측정 전 **잠정값**입니다. 바꿀 때는 yaml만 고칩니다(코드에 기본값 없음).

---

## 9. 보안·프라이버시

- 광고에는 **기기 이름·계정·MAC·고정 ID가 없습니다.** 5분마다 바뀌는 임시 번호와 상태 플래그만 싣고, 광고 주소도 임의 주소입니다.
- 확인 단계는 X25519 키 합의 + 양쪽 화면의 확인 코드(2자리)로 중간자를 막습니다. 사용자는 **코드가 같을 때만 수락**해야 합니다.
- 세션 메시지는 링크마다 ChaCha20-Poly1305로 암호화되고, 변조·재생된 프레임은 링크 고장으로 처리합니다.
- 합류 token은 1회용·만료·기기 바인딩. 틀린 세션 코드가 쌓이면 코드를 새로 만듭니다. 연결 남용에는 속도 제한이 있습니다.
- 라이브러리 로그에는 메시지 본문·이름·키·임시 번호 원본을 남기지 않습니다.

---

## 10. 테스트 (`proximity-testing`)

실기기 없이 JVM 테스트에서 여러 기기를 돌릴 수 있습니다: `FakeAirspace`(광고), `FakeRendezvousNetwork`·`FakeSessionNetwork`(연결), `FakeClock`, `FakeGameModule`.

**계약 테스트 키트** — 내 `GameModule`이 라이브러리 계약을 지키는지 확인합니다(호출 순서, 관전자 액션 0, 예외, view 크기·결정성, view만으로 상태 일치):

```kotlin
@Test
fun myGameKeepsTheContract() = runTest {
    val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
    val config = ProximityConfig.fromYaml(File("src/main/assets/proximity-default.yaml").readText())
    val kit = GameContractKit(MyGame(), config, BouncyCastleCryptoBox(), scope)
    val violations = kit.run(steps = 30) { sender, step -> byteArrayOf(1) }
    assertEquals(emptyList(), violations)
}
```

비공개 정보 필터링(관전자에게 패를 주지 않는지)은 게임마다 형식이 달라 키트가 검사하지 않습니다. 게임 저장소에서 따로 확인하세요.

---

## 11. 샘플 앱

저장소의 `sample-android`에 두 앱이 있습니다 (배포 파일에는 들어 있지 않음).

| 앱 | 용도 |
|---|---|
| **Proximity Session** (`SessionLabActivity`) | 이 README의 전체 사용 예: 닉네임·권한 안내, 방 만들기, 범프(센서/버튼), 확인 화면, 세션 코드·QR(ZXing), 관전자 화면, 데모 게임(`DemoGame`: 공개 카운터 + 비공개 "패"), 지연 측정 내보내기 |
| Proximity Lab (`ProximityLabActivity`) | 저수준 도구: 광고·스캔 표, 범프 신호, 가속도 트레이스 기록 |

```bash
./gradlew :sample-android:assembleDebug
adb install -r sample-android/build/outputs/apk/debug/sample-android-debug.apk
```

---

## 12. 제한과 알려진 점

- v1은 **Android만** 지원합니다 (iOS는 계획 단계).
- 호스트의 동시 연결 수(`max_links` 7)는 기기마다 다를 수 있으나 아직 실측하지 못했습니다(단말 2대로 시험). 연결이 부족하면 최대 인원이 줄어든 것으로 호스트 화면에 표시됩니다.
- 앱이 백그라운드로 가면 스캔·연결이 제한될 수 있습니다. 세션 중에는 화면을 켜 두도록 안내하세요.
- 반복 시험에서 드물게(약 1/12) 양쪽이 서로를 골랐는데 확인 단계로 넘어가지 못한 경우가 있었습니다. 다시 부딪히면 됩니다.
- 기준치(지연·성공률)는 잠정값이며 실기기 측정으로 확정 중입니다.

# GameModule 계약 테스트 키트 (S8)

게임 저장소에서 자기 `GameModule`이 이 모듈의 계약(03-game-data-api.md)을 지키는지 확인한다.

- 코드: `proximity-testing` 모듈의 `dev.proximity.testing.contract.GameContractKit`
- 이 저장소의 사용 예: `proximity-core/src/jvmTest/kotlin/dev/proximity/core/host/S8_ContractKitTest.kt` (FakeGameModule)

```kotlin
@Test
fun myGameKeepsTheContract() = runTest {
    val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
    val kit = GameContractKit(MyGameModule(), ProximityConfig.fromYaml(yaml), BouncyCastleCryptoBox(), scope)
    val violations = kit.run(steps = 30) { sender, step -> myRandomAction(sender, step) }
    assertEquals(emptyList(), violations)
}
```

검사 항목은 `GameContractKit`의 KDoc에 있다. 비공개 정보 필터링(관전자에게 플레이어 패를 주지 않음)은 게임마다 형식이 달라 키트가 검사하지 않는다 — 게임 저장소에서 따로 확인한다 (03 §8 체크리스트).

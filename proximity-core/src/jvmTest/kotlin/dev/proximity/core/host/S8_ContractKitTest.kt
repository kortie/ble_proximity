package dev.proximity.core.host

import dev.proximity.core.api.ActionResult
import dev.proximity.core.api.GameDescriptor
import dev.proximity.core.api.GameHostContext
import dev.proximity.core.api.JoinDecision
import dev.proximity.core.api.LeaveReason
import dev.proximity.core.api.Participant
import dev.proximity.core.api.Role
import dev.proximity.core.api.Target
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.interfaces.GameModule
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.testing.FakeGameModule
import dev.proximity.testing.contract.GameContractKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 계약 테스트 키트 (S8, tests/contract/). I-2: 다른 GameModule로 바꿔도 core 변경 없이 돈다. */
@OptIn(ExperimentalCoroutinesApi::class)
class S8_ContractKitTest {
    private val config = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())

    private fun TestScope.kit(module: GameModule) =
        GameContractKit(module, config, BouncyCastleCryptoBox(), CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun fakeGameModuleKeepsTheContract() = runTest {
        val module = FakeGameModule(GameDescriptor("fake.game", 1, 1, 1, 4, true), turnBased = true)
        val violations = kit(module).run(steps = 24) { _, step -> byteArrayOf((step % 3 + 1).toByte()) }
        assertEquals(emptyList(), violations)
        assertTrue(module.counter > 0)
    }

    /** 규칙이 전혀 다른 두 번째 게임. core 파일을 하나도 고치지 않고 같은 경로로 돈다 (I-2). */
    private class LastWordGame : GameModule {
        private var last = ""
        var ended = 0

        override fun descriptor() = GameDescriptor("other.game", 2, 1, 2, 3, false)

        override fun onJoinRequest(role: Role, displayName: String) = JoinDecision.Admit

        override fun onJoin(ctx: GameHostContext, participant: Participant) = Unit

        override fun onAction(ctx: GameHostContext, sender: Participant, action: ByteArray): ActionResult {
            last = "${sender.id.value}:${action.size}"
            ctx.sendEvent(Target.AllPlayers, action)
            ctx.publishViews()
            return ActionResult.Accepted
        }

        override fun viewFor(participant: Participant) = last.encodeToByteArray()

        override fun onTimer(ctx: GameHostContext, token: String) = Unit

        override fun onLeave(ctx: GameHostContext, participant: Participant, reason: LeaveReason) = Unit

        override fun onSessionEnd() {
            ended++
        }
    }

    @Test
    fun aDifferentGameRunsWithoutCoreChanges() = runTest {
        val game = LastWordGame()
        assertEquals(emptyList(), kit(game).run(steps = 10) { _, step -> ByteArray(step + 1) })
        assertEquals(1, game.ended)
    }

    @Test
    fun theKitCatchesABrokenModule() = runTest {
        // viewFor가 호출마다 바뀌는(비결정적) 모듈은 걸린다
        val broken = object : GameModule by FakeGameModule() {
            private var n = 0

            override fun viewFor(participant: Participant) = byteArrayOf((n++).toByte())
        }
        assertTrue(kit(broken).run(steps = 2) { _, _ -> byteArrayOf(1) }.any { "결정적" in it })
    }
}

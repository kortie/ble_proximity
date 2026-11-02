package dev.proximity.core.api

import kotlinx.coroutines.flow.Flow

/**
 * 모든 기기(호스트 포함)의 게임 UI가 쓰는 API (03-game-data-api §4). core가 구현한다 (S8).
 * 호스트 기기의 UI도 이것만 쓴다 (I-15).
 */
interface GameClient {
    fun me(): Participant

    val state: Flow<ClientState>
    val participants: Flow<List<Participant>>

    /** 항상 최신 전체 view. view만으로 화면을 그릴 수 있어야 한다. */
    val views: Flow<View>
    val events: Flow<GameEvent>

    /** 이벤트를 건너뛰었을 수 있음을 알린다 (재전송 창 밖 유실, 느린 링크). */
    val resyncs: Flow<Resynced>
    val socials: Flow<Social>

    suspend fun sendAction(action: ByteArray): ActionOutcome

    suspend fun sendSocial(message: ByteArray): SocialOutcome
}

sealed interface ClientState {
    data object Syncing : ClientState

    data object Live : ClientState

    data object Paused : ClientState

    class Ended(val result: ByteArray?) : ClientState
}

class View(val revision: Long, val data: ByteArray)

class GameEvent(val seq: Long, val data: ByteArray)

data class Resynced(val fromRevision: Long, val toRevision: Long)

class Social(val from: Participant, val data: ByteArray)

sealed interface ActionOutcome {
    data object Accepted : ActionOutcome

    class Rejected(val code: Int, val detail: ByteArray?) : ActionOutcome

    /** 관전자는 액션을 보낼 수 없다 (I-12). 전송하지 않고 바로 돌려준다. */
    data object NotAllowed : ActionOutcome

    data object TooLarge : ActionOutcome

    data object RateLimited : ActionOutcome

    data object NotLive : ActionOutcome

    /** 결과를 모른다. 적용 여부는 다음 view로 확인한다. */
    data object Timeout : ActionOutcome
}

sealed interface SocialOutcome {
    data object Delivered : SocialOutcome

    data object TooLarge : SocialOutcome

    data object RateLimited : SocialOutcome

    data object NotLive : SocialOutcome
}

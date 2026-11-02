package dev.proximity.core.router

import dev.proximity.core.api.LeaveReason
import dev.proximity.core.api.Participant
import dev.proximity.core.api.ParticipantId
import dev.proximity.core.api.Role

/** 다운링크 수신자 (I-13: 상위 계층이 지정한 수신자에게만 보낸다). */
sealed interface Recipients {
    data object All : Recipients

    data class One(val id: ParticipantId) : Recipients

    data class Many(val ids: Set<ParticipantId>) : Recipients
}

/** 라우터가 보낼 논리 메시지 하나. 한 입력의 출력은 하나의 배치로 연속 seq를 받는다. */
class Outgoing(val to: Recipients, val channel: SessionChannel, val kind: Int, val payload: ByteArray)

/**
 * 세션 라우터의 상위 계층 (S6 경계). S8 GameHost가 game 채널과 스냅샷을 맡는다.
 * 모든 호출은 HostInbox 루프에서 하나씩 일어난다. 돌려준 목록이 그 입력의 배치다.
 */
interface HostApplication {
    fun onJoined(participant: Participant): List<Outgoing> = emptyList()

    fun onLeft(participant: Participant, reason: LeaveReason): List<Outgoing> = emptyList()

    /** game 채널 업링크. control은 라우터가, social은 라우터가 fanout한다. */
    fun onGameUplink(from: Participant, kind: Int, payload: ByteArray): List<Outgoing> = emptyList()

    /** 재전송 창 밖이거나 느린 링크가 따라잡을 때 보낼 현재 상태 (RESYNC 표시로 나간다). */
    fun snapshotFor(participant: Participant): List<Outgoing> = emptyList()

    object None : HostApplication
}

/** 상위 계층(GameHost)이 라우터에 부탁하는 일. 모두 HostInbox 순서로 처리된다. */
interface HostControls {
    fun participants(): List<Participant>

    /** 입력 밖(타이머 등)에서 낸 출력. 그 자체로 하나의 배치. */
    fun post(outputs: () -> List<Outgoing>)

    /** 지금 배치를 다 보낸 뒤 내보낸다 → onLeft(Kicked). */
    fun kick(id: ParticipantId, code: Int)

    /** 대기 중인 프레임을 보낸 뒤 모든 링크를 정리한다. */
    fun end()
}

/** 세션 코드 합류 승인 요청 (04 §5.2.2). [sas]가 신규 기기 화면의 확인 코드와 같아야 수락한다. */
data class CodeJoinRequest(val id: Int, val displayName: String, val role: Role, val sas: String)

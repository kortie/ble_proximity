package dev.proximity.core.api

/** 세션 내 임시 ID. 계정·기기 ID가 아니다. */
data class ParticipantId(val value: Int)

enum class Role { Player, Spectator }

/** [displayName]은 사용자가 정한 닉네임. 연결 위로만 보내고 광고에는 싣지 않는다. */
data class Participant(val id: ParticipantId, val role: Role, val displayName: String)

/** 게임 식별·호환성 (03-game-data-api §2). [gameId]는 런타임 데이터다 (core에 리터럴 금지, I-2). */
data class GameDescriptor(
    val gameId: String,
    val gameVersion: Int,
    val minCompatibleVersion: Int,
    val minPlayers: Int,
    val maxPlayers: Int,
    val allowSpectators: Boolean,
)

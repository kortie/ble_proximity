package dev.proximity.core.session

import dev.proximity.core.api.Role

/** 참가자 수 (호스트 포함). 클라이언트 = 호스트를 뺀 나머지. */
data class Occupancy(val players: Int, val spectators: Int) {
    val clients: Int get() = players + spectators - 1
}

/** 남은 자리. [accepting] = 어느 한쪽이라도 남음 (광고 flag). */
data class Slots(val players: Int, val spectators: Int) {
    val accepting: Boolean get() = players > 0 || spectators > 0
}

/**
 * 04-session-flows §5.3 자리 계산 (S7, I-14).
 * B = max_links, P = min(max_players, 게임 maxPlayers, max_links + 1), S = min(max_spectators, max(0, B - (P - 1))).
 * 관전자는 플레이어 예약(P - 1)을 넘어 쓰지 못한다.
 */
class CapacityLimits private constructor(
    val maxLinks: Int,
    val players: Int,
    val spectators: Int,
    /** 링크 수 때문에 P가 줄었다 (호스트 화면에 "이 기기는 최대 N명"). */
    val reducedByLinks: Boolean,
) {
    fun check(role: Role, occ: Occupancy): TokenRejection? = when {
        occ.clients >= maxLinks -> TokenRejection.SessionFull
        role == Role.Player && occ.players >= players -> TokenRejection.PlayerSlotsFull
        role == Role.Spectator && occ.spectators >= spectators -> TokenRejection.SpectatorSlotsFull
        else -> null
    }

    fun slots(occ: Occupancy): Slots {
        val links = (maxLinks - occ.clients).coerceAtLeast(0)
        return Slots(
            players = (players - occ.players).coerceIn(0, links),
            spectators = (spectators - occ.spectators).coerceIn(0, links),
        )
    }

    companion object {
        fun of(maxLinks: Int, maxPlayers: Int, maxSpectators: Int, gameMaxPlayers: Int): CapacityLimits {
            val wanted = minOf(maxPlayers, gameMaxPlayers)
            val p = minOf(wanted, maxLinks + 1)
            val s = minOf(maxSpectators, (maxLinks - (p - 1)).coerceAtLeast(0))
            return CapacityLimits(maxLinks, p, s, reducedByLinks = p < wanted)
        }
    }
}

package dev.proximity.core.game

/** game 채널 메시지 종류 (ADR-004). 본문 형식은 proximity.proto의 Game* 메시지, EVENT는 게임 bytes 그대로. */
object GameKind {
    const val ACTION = 1
    const val ACTION_RESULT = 2
    const val EVENT = 3
    const val VIEW = 4
    const val SNAPSHOT = 5
    const val END = 6
}

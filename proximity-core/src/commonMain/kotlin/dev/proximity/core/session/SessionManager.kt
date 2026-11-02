package dev.proximity.core.session

import dev.proximity.core.api.Participant
import dev.proximity.core.api.ParticipantId
import dev.proximity.core.api.Role
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.interfaces.Clock
import dev.proximity.core.interfaces.CryptoBox
import dev.proximity.core.interfaces.X25519KeyPair

/** 호스트가 연 세션. [hostKeys]는 세션마다 새로 만드는 X25519 키쌍 (ADR-006 4). */
class HostedSession(val sessionTag: Int, val hostKeys: X25519KeyPair)

/** 신규 기기에 건네는 합류 표 (rdv/JOIN_INFO, ctl/TOKEN). */
class JoinTicket(val sessionTag: Int, val token: ByteArray, val hostSessionPublicKey: ByteArray, val expiresAtMs: Long)

sealed interface Admission {
    data class Admitted(val participant: Participant, val resumeSecret: ByteArray) : Admission

    data class Rejected(val reason: TokenRejection, val gameCode: Int = 0) : Admission
}

enum class TokenRejection {
    NoSession, UnknownToken, Expired, AlreadyUsed, NonceMismatch, SessionFull, PlayerSlotsFull, SpectatorSlotsFull,
    GameMismatch, SpectatorsNotAllowed, GameRefused, DisplayNameTooLong,
}

/** 게임 쪽 합류 검사 (S8 GameHost가 넣는다). [gameId]·[gameVersion]이 null이면 그 항목은 아직 모른다 (TOKEN_REQ에는 gameId가 없다). */
fun interface GameAdmission {
    fun check(role: Role, displayName: String, gameId: String?, gameVersion: Int?): Admission.Rejected?
}

/** 링크로 보내는 거절 코드 (TOKEN_REJECT·REJECT·JOIN_REJECT 공통). */
fun TokenRejection.code(): Int = when (this) {
    TokenRejection.NoSession -> TokenResult.NO_SESSION
    TokenRejection.SessionFull -> TokenResult.SESSION_FULL
    TokenRejection.PlayerSlotsFull -> TokenResult.PLAYER_SLOTS_FULL
    TokenRejection.SpectatorSlotsFull -> TokenResult.SPECTATOR_SLOTS_FULL
    TokenRejection.GameMismatch -> TokenResult.GAME_MISMATCH
    TokenRejection.SpectatorsNotAllowed -> TokenResult.SPECTATORS_NOT_ALLOWED
    TokenRejection.GameRefused -> TokenResult.GAME_REFUSED
    TokenRejection.DisplayNameTooLong -> TokenResult.DISPLAY_NAME_TOO_LONG
    TokenRejection.UnknownToken, TokenRejection.Expired, TokenRejection.AlreadyUsed, TokenRejection.NonceMismatch -> TokenResult.BAD_TOKEN
}

/**
 * 호스트 쪽 세션 관리 (S5): 세션 생성, join token 발급·검증, 참가자 목록, 세션 코드.
 * token은 1회용이고 [ProximityConfig.Session.joinTokenTtlSec] 뒤 만료되며 joinNonce에 묶인다 (04-session-flows §5).
 * 같은 joinNonce로 다시 들어오면(중복 HELLO) 새 참가자를 만들지 않고 처음 결과를 돌려준다.
 * 자리(S7, 04 §5.3)는 token을 줄 때([requestToken])와 HELLO 때([redeem]) 두 번 검사한다.
 * 한 루프에서만 부른다.
 */
class SessionManager(
    private val config: ProximityConfig.Session,
    private val clock: Clock,
    private val crypto: CryptoBox,
) {
    /** [role]이 null이면 열린 token(QR): HELLO에서 기기가 고른 역할로 들어온다. 같은 token을 다시 쓸 수 없다. */
    private class Grant(val joinNonce: ByteArray, val role: Role?, val expiresAtMs: Long, var redeemedBy: ParticipantId? = null) {
        /** 처음 성공한 HELLO의 join_secret. 다시 온 HELLO는 이것과 같아야 한다. */
        var joinSecret: ByteArray = ByteArray(0)
    }

    private var hosted: HostedSession? = null
    private val grants = mutableMapOf<String, Grant>()
    private val members = linkedMapOf<ParticipantId, Participant>()
    private val secrets = mutableMapOf<ParticipantId, ByteArray>()
    private var nextId = 1
    private var code: String? = null
    private var codeFailures = 0

    val session: HostedSession? get() = hosted

    /** SESSION_INFO·QR에 싣는 게임 ID (S8 GameHost가 넣는다). */
    var gameId: String = ""

    /** 게임이 받는 최대 인원 (GameDescriptor.maxPlayers, S8이 넣는다). */
    var gameMaxPlayers: Int = config.maxPlayers

    /** 자리 검사 다음에 부르는 게임 쪽 검사 (S8). */
    var gameAdmission: GameAdmission = GameAdmission { _, _, _, _ -> null }

    val limits: CapacityLimits
        get() = CapacityLimits.of(config.maxLinks, config.maxPlayers, config.maxSpectators, gameMaxPlayers)

    fun occupancy(): Occupancy = Occupancy(
        players = members.values.count { it.role == Role.Player },
        spectators = members.values.count { it.role == Role.Spectator },
    )

    fun slots(): Slots = limits.slots(occupancy())

    /** 자리를 검사하고 token을 준다 (TOKEN_REQ, 호스트의 JOIN_INFO). */
    fun requestToken(joinNonce: ByteArray, role: Role, displayName: String = "", gameVersion: Int? = null): TokenResult {
        if (hosted == null) return TokenResult.Refused(TokenResult.NO_SESSION)
        precheck(role, displayName, gameId = null, gameVersion = gameVersion)?.let { return TokenResult.Refused(it.reason.code(), it.gameCode) }
        return TokenResult.Granted(issueToken(joinNonce, role)!!)
    }

    /** 이름 길이 → 자리 → 게임 순 (04 §5.3, 03 §2). */
    private fun precheck(role: Role, displayName: String, gameId: String?, gameVersion: Int?): Admission.Rejected? {
        if (displayName.encodeToByteArray().size > config.maxDisplayNameBytes) return Admission.Rejected(TokenRejection.DisplayNameTooLong)
        limits.check(role, occupancy())?.let { return Admission.Rejected(it) }
        return gameAdmission.check(role, displayName, gameId, gameVersion)
    }

    fun participants(): List<Participant> = members.values.toList()

    /** 세션을 만들고 호스트 자신을 첫 참가자로 넣는다. 이미 있으면 그대로 돌려준다. */
    fun create(hostRole: Role, hostName: String): HostedSession {
        hosted?.let { return it }
        val tag = randomTag()
        val session = HostedSession(tag, crypto.x25519KeyPair())
        hosted = session
        add(hostRole, hostName)
        return session
    }

    /** QR용 열린 token (역할 미정, 1회용, 04 §5.2.1). */
    fun issueOpenToken(joinNonce: ByteArray): JoinTicket? {
        val session = hosted ?: return null
        val token = crypto.randomBytes(TOKEN_BYTES)
        val expires = clock.now() + config.joinTokenTtlSec * MILLIS
        grants[token.toKey()] = Grant(joinNonce.copyOf(), null, expires)
        return JoinTicket(session.sessionTag, token, session.hostKeys.publicKey, expires)
    }

    /** 호스트 화면에 띄울 QR 문자열. 부를 때마다 새 1회용 token. */
    fun qrFor(): String? {
        val nonce = crypto.randomBytes(TOKEN_BYTES)
        val ticket = issueOpenToken(nonce) ?: return null
        return QrCodec.encode(QrJoin(ticket, nonce, gameId))
    }

    /** 세션 코드 검사. 틀린 코드가 max_auth_failures번 쌓이면 코드를 새로 만든다 (04 §5.2.2). */
    fun checkCode(candidate: String, maxFailures: Int): Boolean {
        val current = sessionCode() ?: return false
        var diff = current.length xor candidate.length
        for (i in current.indices) diff = diff or (current[i].code xor (candidate.getOrNull(i)?.code ?: 0))
        if (diff == 0) return true
        if (++codeFailures >= maxFailures) {
            code = null
            codeFailures = 0
        }
        return false
    }

    /** 코드 합류의 사전 검사 (호스트에게 묻기 전). */
    fun precheckDirect(role: Role, displayName: String, gameId: String?, gameVersion: Int?): Admission.Rejected? =
        if (hosted == null) Admission.Rejected(TokenRejection.NoSession) else precheck(role, displayName, gameId, gameVersion)

    /** 호스트가 승인한 코드 합류: 다시 검사한 뒤 참가자로 넣는다. */
    fun admitDirect(role: Role, displayName: String, gameId: String?, gameVersion: Int?): Admission {
        precheckDirect(role, displayName, gameId, gameVersion)?.let { return it }
        val participant = add(role, displayName)
        return Admission.Admitted(participant, secrets.getValue(participant.id))
    }

    /** 자리 검사 없이 token을 만든다. 보통은 [requestToken]을 쓴다. */
    fun issueToken(joinNonce: ByteArray, role: Role): JoinTicket? {
        val session = hosted ?: return null
        val token = crypto.randomBytes(TOKEN_BYTES)
        val expires = clock.now() + config.joinTokenTtlSec * MILLIS
        grants[token.toKey()] = Grant(joinNonce.copyOf(), role, expires)
        return JoinTicket(session.sessionTag, token, session.hostKeys.publicKey, expires)
    }

    /** HELLO의 token을 검증하고 참가자로 넣는다. */
    fun redeem(
        token: ByteArray,
        joinNonce: ByteArray,
        displayName: String,
        gameId: String? = null,
        gameVersion: Int? = null,
        requestedRole: Role? = null,
        joinSecret: ByteArray = ByteArray(0),
    ): Admission {
        if (hosted == null) return Admission.Rejected(TokenRejection.NoSession)
        val grant = grants[token.toKey()] ?: return Admission.Rejected(TokenRejection.UnknownToken)
        if (!grant.joinNonce.contentEquals(joinNonce)) return Admission.Rejected(TokenRejection.NonceMismatch)
        grant.redeemedBy?.let { id ->
            // 열린 token(QR)은 화면을 본 누구나 같은 nonce를 가진다: 다른 기기가 같은 참가자가 되지 않게 다시 쓰지 못한다
            if (grant.role == null) return Admission.Rejected(TokenRejection.AlreadyUsed)
            // 같은 기기가 HELLO를 다시 보냈다 (같은 token + 같은 joinNonce + 같은 join_secret): 참가자를 늘리지 않는다.
            // joinNonce·token은 중개 멤버도 알므로 그것만으로는 같은 기기라고 보지 않는다 (ADR-006 개정)
            if (grant.joinSecret.isEmpty() || !constantTimeEquals(grant.joinSecret, joinSecret)) return Admission.Rejected(TokenRejection.AlreadyUsed)
            val existing = members[id] ?: return Admission.Rejected(TokenRejection.AlreadyUsed)
            return Admission.Admitted(existing, secrets.getValue(id))
        }
        if (clock.now() >= grant.expiresAtMs) return Admission.Rejected(TokenRejection.Expired)
        val role = grant.role ?: requestedRole ?: Role.Player
        // token을 준 뒤 자리가 찼을 수 있다
        precheck(role, displayName, gameId, gameVersion)?.let { return it }
        val participant = add(role, displayName)
        grant.redeemedBy = participant.id
        grant.joinSecret = joinSecret.copyOf()
        return Admission.Admitted(participant, secrets.getValue(participant.id))
    }

    fun remove(id: ParticipantId) {
        members.remove(id)
        secrets.remove(id)
    }

    /** 세션 코드 합류용 숫자 6자리 (S9). 세션마다 하나. */
    fun sessionCode(): String? {
        if (hosted == null) return null
        return code ?: crypto.randomBytes(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
            .mod(1_000_000L).toString().padStart(6, '0').also { code = it }
    }

    fun end() {
        hosted = null
        grants.clear()
        members.clear()
        secrets.clear()
        code = null
        codeFailures = 0
        nextId = 1
    }

    private fun add(role: Role, name: String): Participant {
        val participant = Participant(ParticipantId(nextId++), role, name)
        members[participant.id] = participant
        secrets[participant.id] = crypto.randomBytes(SECRET_BYTES)
        return participant
    }

    private fun randomTag(): Int {
        while (true) {
            val b = crypto.randomBytes(4)
            val tag = b.fold(0) { acc, x -> (acc shl 8) or (x.toInt() and 0xff) }
            if (tag != 0) return tag
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        var diff = a.size xor b.size
        for (i in a.indices) diff = diff or (a[i].toInt() xor (b.getOrNull(i)?.toInt() ?: 0))
        return diff == 0
    }

    private fun ByteArray.toKey() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val TOKEN_BYTES = 16
        const val SECRET_BYTES = 16
        const val MILLIS = 1000L
    }
}

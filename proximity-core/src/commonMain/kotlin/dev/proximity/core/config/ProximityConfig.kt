package dev.proximity.core.config

/**
 * config/default.yaml의 모델. 필드에 기본값을 두지 않는다 — 정책 값은 파일에만 있다 (원본 §6.2).
 * 키가 빠지거나 모르는 키가 있으면 ConfigException.
 */
data class ProximityConfig(
    val matching: Matching,
    val timeouts: Timeouts,
    val ble: Ble,
    val bump: Bump,
    val security: Security,
    val session: Session,
    val transport: Transport,
    val game: Game,
    val social: Social,
) {
    data class Matching(
        val windowMs: Long,
        val rssiMinDbm: Int,
        val rssiStrongDbm: Int,
        val weightTime: Double,
        val weightRssi: Double,
        val bumpFlagWaitMs: Long,
        val maxAttempts: Int,
        val attemptTimeoutMs: Long,
        val retryCooldownMs: Long,
        val rdvBackoffMs: Long,
    )

    data class Timeouts(val candidateMs: Long, val confirmingMs: Long, val joiningMs: Long)

    data class Ble(val idRotateSec: Long, val protocolVersion: Int, val peerTtlMs: Long)

    data class Bump(
        val thresholdG: Double,
        val debounceMs: Long,
        val spikeMaxMs: Long,
        val preQuietMs: Long,
        val quietG: Double,
        val gravityAlpha: Double,
        val samplePeriodUs: Int,
    )

    data class Security(
        val nonceTtlSec: Long,
        val maxConnectPerMin: Int,
        val sasDigits: Int,
        val aead: String,
        val maxAuthFailures: Int,
    )

    data class Session(
        val maxLinks: Int,
        val maxPlayers: Int,
        val maxSpectators: Int,
        val maxDisplayNameBytes: Int,
        val hostGraceMs: Long,
        val clientReconnectMs: Long,
        val joinTokenTtlSec: Long,
        val hostBidRetries: Int,
        val joinConnectRetries: Int,
        val joinRetryBackoffMs: Long,
    )

    data class Transport(
        val frameHeaderBytes: Int,
        val resendWindow: Int,
        val helloTimeoutMs: Long,
        val ackIntervalMs: Long,
        val ackEveryN: Int,
        val linkQueueMax: Int,
        val keepaliveMs: Long,
        val linkTimeoutMs: Long,
    )

    data class Game(
        val maxActionBytes: Int,
        val maxEventBytes: Int,
        val maxViewBytes: Int,
        val maxActionsPerSec: Int,
        val actionTimeoutMs: Long,
        val dedupWindow: Int,
    )

    data class Social(val maxMsgBytes: Int, val maxMsgPerMin: Int)

    companion object {
        fun fromYaml(text: String): ProximityConfig {
            val reader = Reader(SimpleYaml.parse(text))
            val config = ProximityConfig(
                matching = reader.section("matching") {
                    Matching(
                        windowMs = long("window_ms"),
                        rssiMinDbm = int("rssi_min_dbm"),
                        rssiStrongDbm = int("rssi_strong_dbm"),
                        weightTime = double("weight_time"),
                        weightRssi = double("weight_rssi"),
                        bumpFlagWaitMs = long("bump_flag_wait_ms"),
                        maxAttempts = int("max_attempts"),
                        attemptTimeoutMs = long("attempt_timeout_ms"),
                        retryCooldownMs = long("retry_cooldown_ms"),
                        rdvBackoffMs = long("rdv_backoff_ms"),
                    )
                },
                timeouts = reader.section("timeouts") {
                    Timeouts(long("candidate_ms"), long("confirming_ms"), long("joining_ms"))
                },
                ble = reader.section("ble") { Ble(long("id_rotate_sec"), int("protocol_version"), long("peer_ttl_ms")) },
                bump = reader.section("bump") {
                    Bump(
                        thresholdG = double("threshold_g"),
                        debounceMs = long("debounce_ms"),
                        spikeMaxMs = long("spike_max_ms"),
                        preQuietMs = long("pre_quiet_ms"),
                        quietG = double("quiet_g"),
                        gravityAlpha = double("gravity_alpha"),
                        samplePeriodUs = int("sample_period_us"),
                    )
                },
                security = reader.section("security") {
                    Security(
                        nonceTtlSec = long("nonce_ttl_sec"),
                        maxConnectPerMin = int("max_connect_per_min"),
                        sasDigits = int("sas_digits"),
                        aead = string("aead"),
                        maxAuthFailures = int("max_auth_failures"),
                    )
                },
                session = reader.section("session") {
                    Session(
                        maxLinks = int("max_links"),
                        maxPlayers = int("max_players"),
                        maxSpectators = int("max_spectators"),
                        maxDisplayNameBytes = int("max_display_name_bytes"),
                        hostGraceMs = long("host_grace_ms"),
                        clientReconnectMs = long("client_reconnect_ms"),
                        joinTokenTtlSec = long("join_token_ttl_sec"),
                        hostBidRetries = int("host_bid_retries"),
                        joinConnectRetries = int("join_connect_retries"),
                        joinRetryBackoffMs = long("join_retry_backoff_ms"),
                    )
                },
                transport = reader.section("transport") {
                    Transport(
                        frameHeaderBytes = int("frame_header_bytes"),
                        resendWindow = int("resend_window"),
                        helloTimeoutMs = long("hello_timeout_ms"),
                        ackIntervalMs = long("ack_interval_ms"),
                        ackEveryN = int("ack_every_n"),
                        linkQueueMax = int("link_queue_max"),
                        keepaliveMs = long("keepalive_ms"),
                        linkTimeoutMs = long("link_timeout_ms"),
                    )
                },
                game = reader.section("game") {
                    Game(
                        maxActionBytes = int("max_action_bytes"),
                        maxEventBytes = int("max_event_bytes"),
                        maxViewBytes = int("max_view_bytes"),
                        maxActionsPerSec = int("max_actions_per_sec"),
                        actionTimeoutMs = long("action_timeout_ms"),
                        dedupWindow = int("dedup_window"),
                    )
                },
                social = reader.section("social") { Social(int("max_msg_bytes"), int("max_msg_per_min")) },
            )
            reader.rejectUnknown()
            return config
        }
    }

    /** 읽은 키를 기록해 두었다가 남은(모르는) 키를 오류로 만든다. */
    private class Reader(private val doc: Map<String, Map<String, String>>) {
        private val used = mutableMapOf<String, MutableSet<String>>()

        fun <T> section(name: String, build: SectionReader.() -> T): T {
            val values = doc[name] ?: throw ConfigException("섹션이 없다: $name")
            return SectionReader(name, values, used.getOrPut(name) { mutableSetOf() }).build()
        }

        fun rejectUnknown() {
            val unknown = doc.flatMap { (section, values) ->
                val seen = used[section] ?: return@flatMap listOf(section)
                values.keys.filterNot { it in seen }.map { "$section.$it" }
            }
            if (unknown.isNotEmpty()) throw ConfigException("모르는 키: ${unknown.joinToString()}")
        }
    }

    private class SectionReader(
        private val name: String,
        private val values: Map<String, String>,
        private val used: MutableSet<String>,
    ) {
        fun string(key: String): String {
            used += key
            return values[key] ?: throw ConfigException("키가 없다: $name.$key")
        }

        fun int(key: String): Int = string(key).toIntOrNull() ?: typeError(key, "정수")

        fun long(key: String): Long = string(key).toLongOrNull() ?: typeError(key, "정수")

        fun double(key: String): Double = string(key).toDoubleOrNull() ?: typeError(key, "숫자")

        private fun typeError(key: String, type: String): Nothing =
            throw ConfigException("$name.$key: $type 가 아니다 (${values[key]})")
    }
}

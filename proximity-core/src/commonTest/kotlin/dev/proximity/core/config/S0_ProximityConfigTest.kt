package dev.proximity.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class S0_ProximityConfigTest {
    @Test
    fun loadsCompleteDocument() {
        val config = ProximityConfig.fromYaml(TestYaml.complete())
        assertEquals(300L, config.matching.windowMs)
        assertEquals(-70, config.matching.rssiMinDbm)
        assertEquals(0.4, config.matching.weightRssi)
        assertEquals(8, config.session.maxPlayers)
        assertEquals("chacha20-poly1305", config.security.aead)
    }

    @Test
    fun missingKeyIsAnError() {
        val text = TestYaml.complete().lines().filterNot { it.trim().startsWith("max_links:") }.joinToString("\n")
        val e = assertFailsWith<ConfigException> { ProximityConfig.fromYaml(text) }
        assertTrue("session.max_links" in e.message.orEmpty(), e.message)
    }

    @Test
    fun missingSectionIsAnError() {
        val text = TestYaml.complete().replace("social:\n  max_msg_bytes: 140\n  max_msg_per_min: 20", "")
        val e = assertFailsWith<ConfigException> { ProximityConfig.fromYaml(text) }
        assertTrue("social" in e.message.orEmpty(), e.message)
    }

    @Test
    fun unknownKeyIsAnError() {
        val text = TestYaml.complete().replace("  max_links: 7", "  max_links: 7\n  max_linkz: 7")
        val e = assertFailsWith<ConfigException> { ProximityConfig.fromYaml(text) }
        assertTrue("session.max_linkz" in e.message.orEmpty(), e.message)
    }

    @Test
    fun unknownSectionIsAnError() {
        val e = assertFailsWith<ConfigException> { ProximityConfig.fromYaml(TestYaml.complete() + "\nextra:\n  a: 1\n") }
        assertTrue("extra" in e.message.orEmpty(), e.message)
    }

    @Test
    fun wrongTypeIsAnError() {
        val text = TestYaml.complete().replace("  max_links: 7", "  max_links: seven")
        val e = assertFailsWith<ConfigException> { ProximityConfig.fromYaml(text) }
        assertTrue("session.max_links" in e.message.orEmpty(), e.message)
    }
}

/** config/default.yaml과 같은 키 집합 (값은 테스트용). 파일 자체는 jvmTest S0_DefaultConfigFileTest가 읽는다. */
internal object TestYaml {
    fun complete(): String =
        """
        matching:
          window_ms: 300
          rssi_min_dbm: -70
          rssi_strong_dbm: -30
          weight_time: 0.6
          weight_rssi: 0.4
          bump_flag_wait_ms: 800
          max_attempts: 2
          attempt_timeout_ms: 3000
          retry_cooldown_ms: 10000
          rdv_backoff_ms: 300
        timeouts:
          candidate_ms: 5000
          confirming_ms: 20000
          joining_ms: 10000
        ble:
          id_rotate_sec: 300
          protocol_version: 1
          peer_ttl_ms: 5000
        bump:
          threshold_g: 2.5
          debounce_ms: 500
          spike_max_ms: 80
          pre_quiet_ms: 300
          quiet_g: 0.6
          gravity_alpha: 0.05
          sample_period_us: 10000
        security:
          nonce_ttl_sec: 30
          max_connect_per_min: 10
          sas_digits: 2
          aead: chacha20-poly1305
          max_auth_failures: 3
        session:
          max_links: 7
          max_players: 8
          max_spectators: 4
          max_display_name_bytes: 32
          host_grace_ms: 15000
          client_reconnect_ms: 10000
          join_token_ttl_sec: 30
          host_bid_retries: 3
          join_connect_retries: 2
          join_retry_backoff_ms: 1000
        transport:
          frame_header_bytes: 10
          resend_window: 64
          hello_timeout_ms: 5000
          ack_interval_ms: 1000
          ack_every_n: 16
          link_queue_max: 128
          keepalive_ms: 2000
          link_timeout_ms: 6000
        game:
          max_action_bytes: 512
          max_event_bytes: 1024
          max_view_bytes: 4096
          max_actions_per_sec: 5
          action_timeout_ms: 5000
          dedup_window: 256
        social:
          max_msg_bytes: 140
          max_msg_per_min: 20
        """.trimIndent()
}

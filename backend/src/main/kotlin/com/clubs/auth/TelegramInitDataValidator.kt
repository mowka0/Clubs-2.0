package com.clubs.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class TelegramInitDataValidator(
    @Value("\${telegram.bot-token}") private val botToken: String,
    private val environment: Environment
) {

    private val logger = LoggerFactory.getLogger(TelegramInitDataValidator::class.java)

    fun validate(initData: String): Boolean {
        if (environment.activeProfiles.contains("dev")) {
            logger.debug("Dev profile: skipping HMAC validation")
            return true
        }
        return verifyHmac(initData)
    }

    private fun verifyHmac(initData: String): Boolean {
        val params = initData.split("&")
            .map { it.split("=", limit = 2) }
            .filter { it.size == 2 }
            .associate { it[0] to URLDecoder.decode(it[1], StandardCharsets.UTF_8) }

        val hash = params["hash"] ?: run {
            logger.warn("HMAC validation: 'hash' field missing from initData, keys={}", params.keys)
            return false
        }

        // Reject stale initData before spending CPU on HMAC — Telegram requires auth_date
        // within AUTH_DATA_MAX_AGE_SECONDS of current time to prevent replay attacks
        // (intercepted initData could otherwise be reused indefinitely).
        val authDate = params["auth_date"]?.toLongOrNull() ?: run {
            logger.warn("HMAC validation: 'auth_date' field missing or not a number, keys={}", params.keys)
            return false
        }
        val nowEpochSec = System.currentTimeMillis() / 1000
        val ageSec = nowEpochSec - authDate
        if (ageSec > AUTH_DATA_MAX_AGE_SECONDS) {
            logger.warn(
                "HMAC validation: auth_date too old. now={} auth_date={} age_sec={} max_age_sec={}",
                nowEpochSec, authDate, ageSec, AUTH_DATA_MAX_AGE_SECONDS
            )
            return false
        }
        if (ageSec < -CLOCK_SKEW_TOLERANCE_SECONDS) {
            logger.warn(
                "HMAC validation: auth_date in future beyond clock skew tolerance. now={} auth_date={} age_sec={}",
                nowEpochSec, authDate, ageSec
            )
            return false
        }

        val dataCheckString = params
            .filter { it.key != "hash" }
            .entries
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}=${it.value}" }

        val trimmedToken = botToken.trim()
        if (trimmedToken.length != botToken.length) {
            logger.warn("HMAC validation: bot token has leading/trailing whitespace! raw_len={} trimmed_len={}", botToken.length, trimmedToken.length)
        }

        val secretKey = hmacSha256("WebAppData".toByteArray(), trimmedToken)
        val computedHash = hmacSha256(secretKey, dataCheckString).toHexString()

        val valid = computedHash == hash
        if (!valid) {
            val userFieldPrefix = params["user"]?.take(10) ?: "missing"
            logger.warn(
                "HMAC validation failed — token_len={} params_keys={} user_prefix='{}' computed_prefix={} received_prefix={}",
                trimmedToken.length, params.keys, userFieldPrefix, computedHash.take(8), hash.take(8)
            )
        }
        return valid
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray())
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }

    companion object {
        // Telegram Mini Apps security guideline: reject initData older than 5 minutes
        // to prevent replay attacks with intercepted/logged initData payloads.
        private const val AUTH_DATA_MAX_AGE_SECONDS = 300L
        // Small tolerance for client/server clock skew (e.g. client ~30s fast).
        private const val CLOCK_SKEW_TOLERANCE_SECONDS = 60L
    }
}

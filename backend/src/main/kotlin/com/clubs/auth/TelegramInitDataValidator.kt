package com.clubs.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
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
            .associate { it[0] to it[1] }

        val hash = params["hash"] ?: return false

        val dataCheckString = params
            .filter { it.key != "hash" }
            .entries
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}=${it.value}" }

        val secretKey = hmacSha256("WebAppData".toByteArray(), botToken)
        val computedHash = hmacSha256(secretKey, dataCheckString).toHexString()

        val valid = computedHash == hash
        if (!valid) logger.warn("Telegram HMAC validation failed — hash mismatch")
        return valid
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray())
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}

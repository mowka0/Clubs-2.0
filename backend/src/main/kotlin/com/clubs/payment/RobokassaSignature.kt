package com.clubs.payment

import java.security.MessageDigest

/**
 * Подписи Robokassa: hex-хэш строки «поля через двоеточие» алгоритмом из настроек магазина.
 * Алгоритм у провайдера и у нас обязан совпадать (`billing.robokassa.hash`), иначе все подписи
 * невалидны — чекаут отдаёт ошибку у провайдера, а ResultURL получает 403.
 */
class RobokassaSignature(algorithmName: String) {

    private val jcaAlgorithm: String = when (algorithmName.uppercase().replace("-", "")) {
        "MD5" -> "MD5"
        "SHA1" -> "SHA-1"
        "SHA256" -> "SHA-256"
        "SHA384" -> "SHA-384"
        "SHA512" -> "SHA-512"
        else -> throw IllegalArgumentException("Unsupported Robokassa hash algorithm: $algorithmName")
    }

    /** Хэш строки в нижнем hex. Robokassa сравнивает без учёта регистра. */
    fun hash(value: String): String =
        MessageDigest.getInstance(jcaAlgorithm)
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Сравнение подписей за постоянное время, регистр hex не важен. */
    fun matches(expected: String, actual: String?): Boolean {
        if (actual.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            expected.lowercase().toByteArray(Charsets.US_ASCII),
            actual.trim().lowercase().toByteArray(Charsets.US_ASCII),
        )
    }
}

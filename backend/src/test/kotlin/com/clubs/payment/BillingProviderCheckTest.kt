package com.clubs.payment

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

/**
 * Настройка провайдера платежей: незаданный BILLING_PROVIDER должен падать с понятной
 * причиной, а не «no qualifying bean of type PaymentProvider» (staging 2026-09-15).
 */
class BillingProviderCheckTest {

    @Test
    fun `empty or unknown provider fails with the variable name in the message`() {
        listOf("", "  ", "yookassa", "STUB").forEach { value ->
            val error = assertThrows<IllegalArgumentException> { BillingProviderCheck(value) }
            assertTrue(
                error.message!!.contains("BILLING_PROVIDER"),
                "сообщение должно называть переменную, было: ${error.message}",
            )
        }
    }

    @Test
    fun `supported providers pass`() {
        BillingProviderCheck("stub")
        BillingProviderCheck("robokassa")
    }
}

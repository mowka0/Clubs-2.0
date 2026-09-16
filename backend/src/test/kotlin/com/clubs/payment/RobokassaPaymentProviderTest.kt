package com.clubs.payment

import com.clubs.common.exception.ForbiddenException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.net.URLDecoder
import java.util.UUID

/**
 * Адаптер Robokassa без сети: подписи по формулам документации (порядок полей — самое хрупкое),
 * разбор ResultURL, allowlist и XML OpStateExt.
 */
class RobokassaPaymentProviderTest {

    private val clubId: UUID = UUID.fromString("7c2e1d2a-0000-4000-8000-000000000001")
    private val md5 = RobokassaSignature("MD5")
    private val provider = RobokassaPaymentProvider(
        merchantLogin = "demo",
        password1 = "pass-one",
        password2 = "pass-two",
        testMode = true,
        hashAlgorithm = "MD5",
        allowedIps = "185.59.216.65, 185.59.217.65",
        baseUrl = "https://rk.example",
    )

    private fun query(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate { pair ->
            val (k, v) = pair.split('=', limit = 2)
            k to URLDecoder.decode(v, Charsets.UTF_8)
        }

    @Test
    fun `checkout url carries the payment fields and a signature with redirect modifiers and Shp_club`() {
        val url = provider.createCheckout(
            CheckoutRequest(
                invId = 100001, amountKopecks = 19900, description = "Clubs: подписка за чат «Бег» на 30 дней",
                recurring = true, clubId = clubId,
                successUrl = "https://app.example/pay/return?club=$clubId", failUrl = "https://app.example/pay/fail?club=$clubId",
            ),
        ).value

        assertTrue(url.startsWith("https://rk.example/Merchant/Index.aspx?"))
        val q = query(url)
        assertEquals("demo", q["MerchantLogin"])
        assertEquals("199.00", q["OutSum"])
        assertEquals("100001", q["InvId"])
        assertEquals("true", q["Recurring"])
        assertEquals("1", q["IsTest"])
        assertEquals("GET", q["SuccessUrl2Method"])
        assertEquals(clubId.toString(), q["Shp_club"])
        // Кавычки-«ёлочки» — спецсимволы для провайдера, вычищены.
        assertEquals("Clubs: подписка за чат Бег на 30 дней", q["Description"])
        // MerchantLogin:OutSum:InvId:SuccessUrl2:SuccessUrl2Method:FailUrl2:FailUrl2Method:Пароль#1:Shp_club=…
        val expected = md5.hash(
            "demo:199.00:100001:https://app.example/pay/return?club=$clubId:GET:https://app.example/pay/fail?club=$clubId:GET:pass-one:Shp_club=$clubId",
        )
        assertEquals(expected, q["SignatureValue"])
    }

    @Test
    fun `result notification is accepted with a valid Password#2 signature in any hex case`() {
        val signature = md5.hash("199.000000:100001:pass-two:Shp_club=$clubId").uppercase()
        val notification = provider.parseResultNotification(
            mapOf(
                "OutSum" to "199.000000", "InvId" to "100001", "SignatureValue" to signature,
                "PaymentMethod" to "BankCard", "Fee" to "6.77", "Shp_club" to clubId.toString(),
            ),
        )

        assertEquals(100001L, notification.invId)
        assertEquals(19900, notification.amountKopecks)
        assertEquals("BankCard", notification.paymentMethod)
        assertEquals(BigDecimal("6.77"), notification.fee)
    }

    @Test
    fun `result notification with a wrong signature is rejected`() {
        assertThrows<ForbiddenException> {
            provider.parseResultNotification(
                mapOf("OutSum" to "199.00", "InvId" to "100001", "SignatureValue" to "deadbeef", "Shp_club" to clubId.toString()),
            )
        }
    }

    @Test
    fun `only Robokassa addresses are trusted for ResultURL`() {
        assertTrue(provider.trustsResultSource("185.59.216.65"))
        assertTrue(provider.trustsResultSource("185.59.217.65"))
        assertFalse(provider.trustsResultSource("10.0.0.1"))
    }

    @Test
    fun `OpStateExt codes map to payment states`() {
        fun xml(resultCode: Int, stateCode: Int, method: String? = "BankCard") = """
            <?xml version="1.0" encoding="utf-8"?>
            <OperationStateResponse xmlns="http://merchant.roboxchange.com/WebService/">
              <Result><Code>$resultCode</Code></Result>
              <State><Code>$stateCode</Code><RequestDate>2026-09-07T10:00:00+03:00</RequestDate><StateDate>2026-09-07T10:00:00+03:00</StateDate></State>
              <Info><IncCurrLabel>BankCard</IncCurrLabel><OutSum>199.00</OutSum>${method?.let { "<PaymentMethod>$it</PaymentMethod>" } ?: ""}</Info>
            </OperationStateResponse>
        """.trimIndent()

        assertEquals(PaymentStateResult(PaymentState.SUCCEEDED, "BankCard"), provider.parseOpState(1, xml(0, 100)))
        assertEquals(PaymentState.FAILED, provider.parseOpState(1, xml(0, 10)).state)
        assertEquals(PaymentState.FAILED, provider.parseOpState(1, xml(0, 60)).state)
        assertEquals(PaymentState.PENDING, provider.parseOpState(1, xml(0, 5)).state)
        assertEquals(PaymentState.PENDING, provider.parseOpState(1, xml(0, 50)).state)
        // Операция не найдена (Result.Code ≠ 0) — ещё ждём.
        assertEquals(PaymentState.PENDING, provider.parseOpState(1, xml(3, 100)).state)
        assertNull(provider.parseOpState(1, xml(0, 100, method = null)).paymentMethod)
        assertEquals(PaymentState.PENDING, provider.parseOpState(1, "<broken").state)
    }

    @Test
    fun `amount helpers follow the provider formats`() {
        assertEquals("199.00", RobokassaPaymentProvider.formatOutSum(19900))
        assertEquals("0.05", RobokassaPaymentProvider.formatOutSum(5))
        assertEquals(19900, RobokassaPaymentProvider.parseKopecks("199.000000"))
        assertEquals(19900, RobokassaPaymentProvider.parseKopecks("199"))
        assertEquals(100, RobokassaPaymentProvider.sanitizeDescription("x".repeat(140)).length)
    }

    @Test
    fun `signature compare is case-insensitive and rejects blanks`() {
        val sig = RobokassaSignature("SHA256")
        val hash = sig.hash("a:b:c")
        assertTrue(sig.matches(hash, hash.uppercase()))
        assertFalse(sig.matches(hash, null))
        assertFalse(sig.matches(hash, ""))
        assertThrows<IllegalArgumentException> { RobokassaSignature("RIPEMD160") }
    }
}

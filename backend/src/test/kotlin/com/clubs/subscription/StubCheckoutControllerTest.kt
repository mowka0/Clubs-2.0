package com.clubs.subscription

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Страница заглушки для staging-прогона: без выбора способа ничего не подтверждает, картой и
 * СБП подтверждает по-разному (от способа зависит автопродление), отказ уводит на /pay/fail.
 */
class StubCheckoutControllerTest {

    private val billingService = mockk<BillingService>()
    private val controller = StubCheckoutController(
        billingService,
        successUrl = "https://app.example/pay/return",
        failUrl = "https://app.example/pay/fail",
    )

    @Test
    fun `without a method shows the choice page and settles nothing`() {
        val response = controller.pay(invId = 100500, method = null, outcome = null)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.contains("Оплатить по СБП"), "на странице должен быть выбор способа")
        assertTrue(response.body!!.contains("100500"), "номер счёта виден человеку")
        verify(exactly = 0) { billingService.settleStubPayment(any(), any()) }
    }

    @Test
    fun `chosen method settles the invoice and returns to the success page`() {
        val clubId = UUID.randomUUID()
        every { billingService.settleStubPayment(100500, "SBP") } returns clubId

        val response = controller.pay(invId = 100500, method = "SBP", outcome = null)

        assertEquals(HttpStatus.FOUND, response.statusCode)
        assertEquals("https://app.example/pay/return?club=$clubId", response.headers.location.toString())
    }

    @Test
    fun `refusal leaves the invoice alone and returns to the fail page`() {
        val response = controller.pay(invId = 100500, method = null, outcome = "fail")

        assertEquals(HttpStatus.FOUND, response.statusCode)
        assertEquals("https://app.example/pay/fail", response.headers.location.toString())
        verify(exactly = 0) { billingService.settleStubPayment(any(), any()) }
    }
}

package com.clubs.common.security

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * Ключ бакета — то, из-за чего лимит однажды стал общим на всё окружение: за Traefik и nginx
 * все запросы приходят с одного внутреннего адреса, и по нему складывались все пользователи
 * сразу (баг прода 2026-08-19).
 */
class RateLimitFilterTest {

    private val filter = RateLimitFilter()

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        filter.resetBuckets()
    }

    private fun request(forwardedFor: String?, remoteAddr: String = "10.0.1.8"): MockHttpServletRequest =
        MockHttpServletRequest("GET", "/api/clubs").apply {
            servletPath = "/api/clubs"
            this.remoteAddr = remoteAddr
            forwardedFor?.let { addHeader("X-Forwarded-For", it) }
        }

    private fun spend(times: Int, req: () -> MockHttpServletRequest): Int {
        var rejected = 0
        repeat(times) {
            val response = MockHttpServletResponse()
            filter.doFilter(req(), response, FilterChain { _, _ -> })
            if (response.status == 429) rejected++
        }
        return rejected
    }

    @Test
    fun `разные пользователи с одного адреса прокси не делят лимит`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        authenticate(first)
        val firstRejected = spend(121) { request(forwardedFor = "203.0.113.7, 10.0.1.8") }
        authenticate(second)
        val secondRejected = spend(1) { request(forwardedFor = "203.0.113.7, 10.0.1.8") }

        // Первый выбрал свой лимит и получил один отказ, второй начинает с полного бакета.
        assertEquals(1, firstRejected)
        assertEquals(0, secondRejected)
    }

    @Test
    fun `неавторизованные считаются по адресу клиента, а не прокси`() {
        val fromFirstClient = spend(120) { request(forwardedFor = "203.0.113.7, 10.0.1.8") }
        val fromSecondClient = spend(1) { request(forwardedFor = "198.51.100.4, 10.0.1.8") }

        // Адрес Traefik в хвосте цепочки одинаков — если ключом станет он, второй клиент
        // получит 429 на первом же запросе.
        assertEquals(0, fromFirstClient)
        assertEquals(0, fromSecondClient)
    }

    @Test
    fun `подставленный клиентом X-Forwarded-For не даёт свежий бакет`() {
        // Клиент может прислать свой заголовок, но Traefik допишет реальный адрес следом —
        // ключом становится именно он, и подделка лимит не обходит.
        val rejected = spend(121) { request(forwardedFor = "${UUID.randomUUID()}, 203.0.113.7, 10.0.1.8") }

        assertEquals(1, rejected)
    }

    private fun authenticate(userId: UUID) {
        val principal = AuthenticatedUser(userId = userId, telegramId = 1L)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }
}

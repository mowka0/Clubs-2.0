package com.clubs.analytics

import com.clubs.analytics.dto.TrackEventRequest
import com.clubs.analytics.dto.TrackRequest
import com.clubs.common.exception.ValidationException
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals

/** Приём пачки от Mini App (product-analytics.md § 4, AC-2, AC-3): whitelist видов и ключей, размеры, fail-closed. */
class TrackServiceTest {

    private val repository = mockk<ProductEventRepository>(relaxed = true)
    private val service = TrackService(repository)
    private val userId = UUID.randomUUID()

    private fun request(vararg events: TrackEventRequest) = TrackRequest(events.toList())

    @Test
    fun `валидная пачка пишется одним вызовом - автор из токена, источник app, props как прислано`() {
        val clubId = UUID.randomUUID()

        service.track(
            userId,
            request(
                TrackEventRequest("screen_opened", clubId, mapOf("screen" to "club")),
                TrackEventRequest("app_opened", null, mapOf("entry" to "none", "platform" to "ios")),
            ),
        )

        val batch = slot<List<NewProductEvent>>()
        verify(exactly = 1) { repository.recordAll(capture(batch)) }
        assertEquals(
            listOf(
                NewProductEvent(ProductEventKind.SCREEN_OPENED, userId, clubId, mapOf("screen" to "club")),
                NewProductEvent(ProductEventKind.APP_OPENED, userId, null, mapOf("entry" to "none", "platform" to "ios")),
            ),
            batch.captured,
        )
        assertEquals(EventSource.APP, batch.captured[0].source)
    }

    @Test
    fun `серверный, ботовый и неизвестный вид - ошибка на всю пачку, ничего не записано`() {
        for (kind in listOf("payment_succeeded", "bot_started", "nope", "")) {
            assertThrows<ValidationException>(kind) {
                service.track(
                    userId,
                    request(TrackEventRequest("screen_opened", null, mapOf("screen" to "home")), TrackEventRequest(kind)),
                )
            }
        }
        verify(exactly = 0) { repository.recordAll(any()) }
    }

    @Test
    fun `props - больше десяти ключей, ключ не по маске, чужой ключ, длинная строка, вложенность`() {
        val bad: List<Map<String, Any?>> = listOf(
            (1..11).associate { "k$it" to "v" },
            mapOf("scr-een" to "home"),
            mapOf("entry" to "none"),
            mapOf("screen" to "x".repeat(101)),
            mapOf("screen" to mapOf("nested" to 1)),
            mapOf("screen" to listOf("a")),
        )
        bad.forEach { props ->
            assertThrows<ValidationException>(props.toString()) {
                service.track(userId, request(TrackEventRequest("screen_opened", null, props)))
            }
        }
        verify(exactly = 0) { repository.recordAll(any()) }
    }

    @Test
    fun `число, boolean, null и строка в 100 знаков допустимы, пустые props дают null`() {
        service.track(
            userId,
            request(
                TrackEventRequest("onboarding_step", null, mapOf("step" to 2)),
                TrackEventRequest("pay_sheet_opened", null, mapOf("reason" to "x".repeat(100), "mode" to null)),
                TrackEventRequest("onboarding_completed"),
            ),
        )

        verify(exactly = 1) {
            repository.recordAll(
                match {
                    it.size == 3 && it[0].props == mapOf("step" to 2) &&
                        it[1].props == mapOf("reason" to "x".repeat(100), "mode" to null) && it[2].props == null
                },
            )
        }
    }
}

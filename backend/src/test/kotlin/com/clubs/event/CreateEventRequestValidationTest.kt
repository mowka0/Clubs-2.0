package com.clubs.event

import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Bean Validation гео-полей CreateEventRequest (фича event-geo, правила PO 2026-07-11):
 * место опционально (V58), но хоть какое-то указание места обязательно — либо гео-точка
 * с карты, либо текстовое уточнение; координаты валидны только парой и в диапазонах WGS-84.
 */
class CreateEventRequestValidationTest {

    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator

    private fun request(
        locationText: String? = "ул. Покровка, 47/24с1, Москва",
        locationLat: Double? = 55.761216,
        locationLon: Double? = 37.646488,
        locationHint: String? = null,
        participantLimit: Int? = 20,
        isOpenEvent: Boolean = false,
        isUrgentEvent: Boolean = false,
        stage2LeadMinutes: Int? = null
    ) = CreateEventRequest(
        title = "Test event",
        description = null,
        locationText = locationText,
        locationLat = locationLat,
        locationLon = locationLon,
        locationHint = locationHint,
        eventDatetime = OffsetDateTime.now().plusDays(7),
        participantLimit = participantLimit,
        isOpenEvent = isOpenEvent,
        isUrgentEvent = isUrgentEvent,
        votingOpensDaysBefore = 14,
        stage2LeadMinutes = stage2LeadMinutes
    )

    private fun violatedProperties(request: CreateEventRequest): Set<String> =
        validator.validate(request).map { it.propertyPath.toString() }.toSet()

    @Test
    fun `request with map point passes`() {
        val violations = validator.validate(request(locationHint = "Вход со двора, домофон 12"))
        assertTrue(violations.isEmpty(), "Expected no violations, got: $violations")
    }

    @Test
    fun `request with hint only (no point) passes`() {
        val violations = validator.validate(
            request(locationText = null, locationLat = null, locationLon = null, locationHint = "Встречаемся в зуме")
        )
        assertTrue(violations.isEmpty(), "Expected no violations, got: ${violations.map { "${it.propertyPath}: ${it.message}" }}")
    }

    @Test
    fun `request without point AND without hint is rejected`() {
        val violated = violatedProperties(request(locationText = null, locationLat = null, locationLon = null))
        assertTrue("someLocationProvided" in violated)
    }

    // --- Интервал Этапа 2 (V67/V68): границы 1080..7200 и несовместимость с open/urgent ---

    @Test
    fun `stage2 lead within bounds passes`() {
        assertTrue(validator.validate(request(stage2LeadMinutes = 1080)).isEmpty())
        assertTrue(validator.validate(request(stage2LeadMinutes = 7200)).isEmpty())
    }

    @Test
    fun `stage2 lead below 18 hours is rejected`() {
        assertTrue("stage2LeadMinutes" in violatedProperties(request(stage2LeadMinutes = 1079)))
    }

    @Test
    fun `stage2 lead above 5 days is rejected`() {
        assertTrue("stage2LeadMinutes" in violatedProperties(request(stage2LeadMinutes = 7201)))
    }

    @Test
    fun `stage2 lead on an open event is rejected`() {
        val violated = violatedProperties(
            request(participantLimit = null, isOpenEvent = true, stage2LeadMinutes = 2160)
        )
        assertTrue("stage2LeadConsistent" in violated)
    }

    @Test
    fun `urgent event with a limit passes`() {
        assertTrue(validator.validate(request(isUrgentEvent = true)).isEmpty())
    }

    @Test
    fun `urgent event cannot be open`() {
        val violated = violatedProperties(
            request(participantLimit = null, isOpenEvent = true, isUrgentEvent = true)
        )
        assertTrue("urgentConsistent" in violated)
    }

    @Test
    fun `urgent event cannot carry a custom stage2 lead`() {
        val violated = violatedProperties(request(isUrgentEvent = true, stage2LeadMinutes = 2160))
        assertTrue("urgentConsistent" in violated)
    }

    @Test
    fun `blank hint does not count as location`() {
        val violated = violatedProperties(
            request(locationText = null, locationLat = null, locationLon = null, locationHint = "   ")
        )
        assertTrue("someLocationProvided" in violated)
    }

    @Test
    fun `half a coordinate pair is rejected`() {
        val violatedLonNull = violatedProperties(request(locationLon = null, locationHint = "х"))
        assertTrue("locationPairConsistent" in violatedLonNull, "got: $violatedLonNull")
        val violatedLatNull = violatedProperties(request(locationLat = null, locationHint = "х"))
        assertTrue("locationPairConsistent" in violatedLatNull, "got: $violatedLatNull")
    }

    @Test
    fun `latitude outside range is rejected`() {
        assertTrue("locationLat" in violatedProperties(request(locationLat = 90.0001)))
        assertTrue("locationLat" in violatedProperties(request(locationLat = -90.0001)))
    }

    @Test
    fun `longitude outside range is rejected`() {
        assertTrue("locationLon" in violatedProperties(request(locationLon = 180.0001)))
        assertTrue("locationLon" in violatedProperties(request(locationLon = -180.0001)))
    }

    @Test
    fun `boundary coordinates are accepted`() {
        assertTrue(validator.validate(request(locationLat = 90.0, locationLon = 180.0)).isEmpty())
        assertTrue(validator.validate(request(locationLat = -90.0, locationLon = -180.0)).isEmpty())
    }

    @Test
    fun `hint longer than 200 characters is rejected`() {
        assertTrue("locationHint" in violatedProperties(request(locationHint = "х".repeat(201))))
    }

    @Test
    fun `hint of exactly 200 characters is accepted`() {
        val violations = validator.validate(request(locationHint = "х".repeat(200)))
        assertTrue(violations.isEmpty(), "got: ${violations.map { "${it.propertyPath}: ${it.message}" }}")
    }

    // Открытая встреча (V62): формат заявляется ЯВНЫМ флагом + отсутствием лимита.
    @Test
    fun `open event - explicit flag with null limit passes`() {
        val violations = validator.validate(request(participantLimit = null, isOpenEvent = true))
        assertTrue(violations.isEmpty(), "got: ${violations.map { "${it.propertyPath}: ${it.message}" }}")
    }

    // Пропущенный лимит БЕЗ флага — это ошибка ввода (как до V62), а не молчаливая смена формата.
    @Test
    fun `missing limit without the open flag is rejected`() {
        assertTrue("isParticipantLimitConsistent" in violatedProperties(request(participantLimit = null)))
    }

    // Противоречивый ввод: флаг открытой встречи вместе с лимитом.
    @Test
    fun `open flag combined with a limit is rejected`() {
        assertTrue("isParticipantLimitConsistent" in violatedProperties(request(participantLimit = 20, isOpenEvent = true)))
    }

    // @Positive продолжает отсекать бессмысленные ненулевые значения.
    @Test
    fun `zero or negative participant limit is still rejected`() {
        assertTrue("participantLimit" in violatedProperties(request(participantLimit = 0)))
        assertTrue("participantLimit" in violatedProperties(request(participantLimit = -5)))
    }
}

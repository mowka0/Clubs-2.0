package com.clubs.event

import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
        minParticipants: Int? = null,
        format: EventFormatInput = EventFormatInput.NORMAL,
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
        minParticipants = minParticipants,
        format = format,
        votingOpensDaysBefore = 14,
        stage2LeadMinutes = stage2LeadMinutes
    )

    // ВНИМАНИЕ: путь свойства идёт БЕЗ префикса `is` — Bean Validation выводит имя по JavaBeans,
    // и геттер `isFooConsistent()` даёт свойство `fooConsistent`.
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
    fun `stage2 lead below 6 hours is rejected`() {
        // Нижняя граница опущена с 18 ч до 6 ч (V83): 6 ч — самый короткий пресет набора состава.
        assertTrue("stage2LeadMinutes" in violatedProperties(request(stage2LeadMinutes = 359)))
    }

    @Test
    fun `stage2 lead of 6 hours is accepted`() {
        assertTrue("stage2LeadMinutes" !in violatedProperties(request(stage2LeadMinutes = 360)))
    }

    @Test
    fun `stage2 lead above 5 days is rejected`() {
        assertTrue("stage2LeadMinutes" in violatedProperties(request(stage2LeadMinutes = 7201)))
    }

    @Test
    fun `stage2 lead on a format without a roster is rejected`() {
        val violated = violatedProperties(
            request(participantLimit = null, format = EventFormatInput.OPEN, stage2LeadMinutes = 2160)
        )
        assertTrue("stage2LeadConsistent" in violated)
    }

    @Test
    fun `minimum with a limit and a custom lead passes`() {
        assertTrue(validator.validate(request(minParticipants = 4, stage2LeadMinutes = 2160)).isEmpty())
    }

    @Test
    fun `minimum equal to the limit passes, above the limit is rejected`() {
        assertTrue(validator.validate(request(participantLimit = 4, minParticipants = 4)).isEmpty())
        assertTrue("minParticipantsConsistent" in violatedProperties(request(participantLimit = 4, minParticipants = 5)))
        assertTrue("minParticipants" in violatedProperties(request(minParticipants = 0)))
    }

    @Test
    fun `minimum on an open format is rejected`() {
        val violated = violatedProperties(request(participantLimit = null, format = EventFormatInput.OPEN, minParticipants = 4))
        assertTrue("minParticipantsConsistent" in violated)
    }

    // AC-17: старые литералы V85 принимаются до следующего релиза; `min` подставляет минимум = лимит.
    @Test
    fun `legacy literals map to the two formats and min implies a minimum`() {
        assertEquals(EventFormatInput.LEGACY_MIN, EventFormatInput.fromWire("min"))
        assertEquals(EventFormat.NORMAL, EventFormatInput.fromWire("max").format)
        assertEquals(EventFormat.OPEN, EventFormatInput.fromWire("any").format)
        assertEquals(20, request(format = EventFormatInput.LEGACY_MIN).effectiveMinParticipants)
        assertEquals(null, request(format = EventFormatInput.LEGACY_MAX).effectiveMinParticipants)
        assertEquals(4, request(format = EventFormatInput.LEGACY_MIN, minParticipants = 4).effectiveMinParticipants)
        assertTrue(validator.validate(request(format = EventFormatInput.LEGACY_MIN)).isEmpty())
        assertTrue(validator.validate(request(format = EventFormatInput.LEGACY_ANY, participantLimit = null)).isEmpty())
    }

    @Test
    fun `legacy literal is accepted by Jackson, the wire value is the literal, an unknown one is rejected`() {
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        assertEquals(EventFormatInput.LEGACY_MIN, mapper.readValue("\"min\"", EventFormatInput::class.java))
        assertEquals(EventFormatInput.NORMAL, mapper.readValue("\"normal\"", EventFormatInput::class.java))
        assertEquals("\"normal\"", mapper.writeValueAsString(EventFormatInput.NORMAL))
        assertThrows<com.fasterxml.jackson.databind.JsonMappingException> {
            mapper.readValue("\"urgent\"", EventFormatInput::class.java)
        }
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

    // Открытая: формат заявляется ЯВНО + отсутствием лимита.
    @Test
    fun `format open with a null limit passes`() {
        val violations = validator.validate(request(participantLimit = null, format = EventFormatInput.OPEN))
        assertTrue(violations.isEmpty(), "got: ${violations.map { "${it.propertyPath}: ${it.message}" }}")
    }

    // Пропущенный лимит при формате с лимитом — ошибка ввода, а не молчаливая смена формата.
    @Test
    fun `missing limit on a limited format is rejected`() {
        assertTrue("participantLimitConsistent" in violatedProperties(request(participantLimit = null)))
    }

    // Противоречивый ввод: формат без лимита вместе с лимитом.
    @Test
    fun `format open combined with a limit is rejected`() {
        assertTrue("participantLimitConsistent" in violatedProperties(request(participantLimit = 20, format = EventFormatInput.OPEN)))
    }

    // @Positive продолжает отсекать бессмысленные ненулевые значения.
    @Test
    fun `zero or negative participant limit is still rejected`() {
        assertTrue("participantLimit" in violatedProperties(request(participantLimit = 0)))
        assertTrue("participantLimit" in violatedProperties(request(participantLimit = -5)))
    }
}

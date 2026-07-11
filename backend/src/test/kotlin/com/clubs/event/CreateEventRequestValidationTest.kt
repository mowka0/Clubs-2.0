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
        locationHint: String? = null
    ) = CreateEventRequest(
        title = "Test event",
        description = null,
        locationText = locationText,
        locationLat = locationLat,
        locationLon = locationLon,
        locationHint = locationHint,
        eventDatetime = OffsetDateTime.now().plusDays(7),
        participantLimit = 20,
        votingOpensDaysBefore = 14
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
}

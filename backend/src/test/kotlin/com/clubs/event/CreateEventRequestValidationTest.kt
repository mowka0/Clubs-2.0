package com.clubs.event

import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Bean Validation границ гео-полей CreateEventRequest (фича event-geo).
 * Решение PO — fail-closed: координаты обязательны, событие без точки на карте
 * не создаётся; уточнение к месту ограничено 200 символами.
 */
class CreateEventRequestValidationTest {

    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator

    private fun request(
        locationLat: Double? = 55.761216,
        locationLon: Double? = 37.646488,
        locationHint: String? = null
    ) = CreateEventRequest(
        title = "Test event",
        description = null,
        locationText = "ул. Покровка, 47/24с1, Москва",
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
    fun `valid request with coordinates and hint passes`() {
        val violations = validator.validate(request(locationHint = "Вход со двора, домофон 12"))
        assertTrue(violations.isEmpty(), "Expected no violations, got: $violations")
    }

    @Test
    fun `missing latitude is rejected`() {
        assertTrue("locationLat" in violatedProperties(request(locationLat = null)))
    }

    @Test
    fun `missing longitude is rejected`() {
        assertTrue("locationLon" in violatedProperties(request(locationLon = null)))
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
        assertTrue(validator.validate(request(locationHint = "х".repeat(200))).isEmpty())
    }
}

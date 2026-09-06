package com.clubs.eventtemplate

import com.clubs.event.EventFormatInput
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Test
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bean Validation тела сохранения шаблона: инварианты зеркалят CHECK-и V79 и правила события —
 * координаты только парой, формат согласован с лимитом, свой интервал Этапа 2 неприменим к
 * открытой и срочной встрече, день недели в 1..7.
 *
 * Отдельно фиксируем НАМЕРЕННОЕ отличие от CreateEventRequest: требования «место ИЛИ уточнение»
 * у шаблона нет (docs/modules/event-templates.md § 5.1).
 */
class SaveEventTemplateRequestValidationTest {

    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator

    private fun request(
        name: String = "Разговорный клуб",
        title: String = "Разговорный клуб",
        locationText: String? = "ул. Покровка, 47/24с1, Москва",
        locationLat: Double? = 55.761216,
        locationLon: Double? = 37.646488,
        locationHint: String? = null,
        participantLimit: Int? = 20,
        minParticipants: Int? = null,
        format: EventFormatInput = EventFormatInput.NORMAL,
        stage2LeadMinutes: Int? = null,
        defaultWeekday: Short? = 2,
        defaultTime: LocalTime? = LocalTime.of(19, 0)
    ) = SaveEventTemplateRequest(
        name = name,
        title = title,
        locationText = locationText,
        locationLat = locationLat,
        locationLon = locationLon,
        locationHint = locationHint,
        participantLimit = participantLimit,
        minParticipants = minParticipants,
        format = format,
        stage2LeadMinutes = stage2LeadMinutes,
        defaultWeekday = defaultWeekday,
        defaultTime = defaultTime
    )

    private fun violatedProperties(request: SaveEventTemplateRequest): Set<String> =
        validator.validate(request).map { it.propertyPath.toString() }.toSet()

    @Test
    fun `полный шаблон встречи с местами проходит`() {
        assertTrue(validator.validate(request()).isEmpty())
    }

    @Test
    fun `шаблон без места и без уточнения проходит — это заготовка, а не событие`() {
        val violations = validator.validate(
            request(locationText = null, locationLat = null, locationLon = null, locationHint = null)
        )
        assertTrue(
            violations.isEmpty(),
            "Требование «место ИЛИ уточнение» осталось на форме создания встречи, got: $violations"
        )
    }

    @Test
    fun `одна координата без второй отклоняется`() {
        assertEquals(
            setOf("locationPairConsistent"),
            violatedProperties(request(locationLon = null))
        )
    }

    @Test
    fun `пустое имя отклоняется`() {
        assertEquals(setOf("name"), violatedProperties(request(name = "   ")))
    }

    @Test
    fun `имя длиннее 60 символов отклоняется`() {
        assertEquals(setOf("name"), violatedProperties(request(name = "я".repeat(61))))
    }

    @Test
    fun `формат без лимита вместе с лимитом отклоняется`() {
        assertEquals(
            setOf("participantLimitConsistent"),
            violatedProperties(request(format = EventFormatInput.OPEN, participantLimit = 20))
        )
    }

    @Test
    fun `формат с лимитом без лимита отклоняется`() {
        assertEquals(
            setOf("participantLimitConsistent"),
            violatedProperties(request(format = EventFormatInput.NORMAL, participantLimit = null))
        )
        assertEquals(
            setOf("participantLimitConsistent"),
            violatedProperties(request(format = EventFormatInput.LEGACY_MIN, participantLimit = null))
        )
    }

    @Test
    fun `формат без лимита со своим интервалом набора отклоняется`() {
        assertEquals(
            setOf("stage2LeadConsistent"),
            violatedProperties(request(format = EventFormatInput.OPEN, participantLimit = null, stage2LeadMinutes = 1080))
        )
    }

    @Test
    fun `шаблон с минимумом и своим интервалом набора проходит, минимум выше лимита — нет`() {
        assertEquals(emptySet(), violatedProperties(request(minParticipants = 4, stage2LeadMinutes = 2160)))
        assertEquals(setOf("minParticipantsConsistent"), violatedProperties(request(participantLimit = 4, minParticipants = 5)))
        // Легаси-литерал `min` подставляет минимум равным лимиту (AC-17).
        assertEquals(20, request(format = EventFormatInput.LEGACY_MIN).effectiveMinParticipants)
    }

    @Test
    fun `интервал набора короче 6 часов отклоняется`() {
        // V83: нижняя граница пресетов — 6 ч (было 18 ч).
        assertEquals(setOf("stage2LeadMinutes"), violatedProperties(request(stage2LeadMinutes = 359)))
        assertEquals(emptySet(), violatedProperties(request(stage2LeadMinutes = 360)))
    }

    @Test
    fun `интервал Этапа 2 длиннее 5 дней отклоняется`() {
        assertEquals(setOf("stage2LeadMinutes"), violatedProperties(request(stage2LeadMinutes = 7201)))
    }

    @Test
    fun `день недели вне 1-7 отклоняется`() {
        assertEquals(setOf("defaultWeekday"), violatedProperties(request(defaultWeekday = 0)))
        assertEquals(setOf("defaultWeekday"), violatedProperties(request(defaultWeekday = 8)))
    }

    @Test
    fun `шаблон без дня недели и времени проходит — дата останется пустой`() {
        assertTrue(validator.validate(request(defaultWeekday = null, defaultTime = null)).isEmpty())
    }
}

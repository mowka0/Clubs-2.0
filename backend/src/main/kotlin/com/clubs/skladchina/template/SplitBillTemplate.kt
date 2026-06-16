package com.clubs.skladchina.template

import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.event.EventRepository
import com.clubs.event.EventResponseRepository
import com.clubs.generated.jooq.enums.SkladchinaMode
import com.clubs.generated.jooq.enums.SkladchinaTemplate
import com.clubs.skladchina.CreateSkladchinaRequest
import com.clubs.skladchina.SkladchinaRepository
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * "Разделить счёт" — split a past event's bill equally across those who ACTUALLY attended.
 *
 * The unique template with a VERIFIED anchor: the participant set comes from organizer-marked
 * attendance, not the organizer's free choice — so randoms can't be mobilized, and the benefit was
 * already consumed (you were there), which removes the free-rider escape. `totalGoalKopecks` is the
 * bill; the share is bill ÷ attended (server-authoritative). Deliberate cross-module read of the
 * event domain (skladchina → event) — split is inherently about an event; the dependency is one-way.
 */
@Component
class SplitBillTemplate(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val skladchinaRepository: SkladchinaRepository,
) : SkladchinaTemplateStrategy {

    override val type = SkladchinaTemplate.split_bill
    override val outcomesVerified = true

    override fun resolveCreation(clubId: UUID, creatorId: UUID, request: CreateSkladchinaRequest): TemplateResolution {
        val eventId = request.eventId
            ?: throw ValidationException("Не указано событие для разделения счёта")
        val event = eventRepository.findById(eventId)
            ?: throw NotFoundException("Событие не найдено")
        if (event.clubId != clubId) {
            throw ValidationException("Событие принадлежит другому клубу")
        }
        if (!event.attendanceMarked) {
            throw ValidationException("Сначала отметьте, кто пришёл на событие")
        }
        if (event.eventDatetime.isBefore(OffsetDateTime.now().minusDays(MAX_EVENT_AGE_DAYS))) {
            throw ValidationException("Событие старше $MAX_EVENT_AGE_DAYS дней — счёт уже не разделить")
        }

        val bill = request.totalGoalKopecks
            ?: throw ValidationException("Укажите сумму чека")
        if (bill <= 0) throw ValidationException("Сумма чека должна быть положительной")

        val attendedAll = eventResponseRepository.findAttendedUserIds(eventId)
        // Only still-active members can be in the skladchina (page access + DM). Someone who
        // attended but has since left the club is silently dropped (their share is uncollectable here).
        val notActive = skladchinaRepository.findNonActiveMembers(clubId, attendedAll)
        val attended = attendedAll.filter { it !in notActive }
        if (attended.size < MIN_ATTENDED) {
            throw ValidationException("Нужно минимум $MIN_ATTENDED пришедших участника для разделения счёта")
        }

        val participants = SkladchinaShares.equal(bill, attended).map { it.first to (it.second as Long?) }
        return TemplateResolution(SkladchinaMode.fixed_equal, bill, participants, eventId)
    }

    companion object {
        private const val MAX_EVENT_AGE_DAYS = 30L
        private const val MIN_ATTENDED = 2
    }
}

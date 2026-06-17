package com.clubs.skladchina.template

import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.event.EventRepository
import com.clubs.event.EventResponseRepository
import com.clubs.generated.jooq.enums.SkladchinaMode
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.generated.jooq.enums.SkladchinaTemplate
import com.clubs.skladchina.CreateSkladchinaRequest
import com.clubs.skladchina.SkladchinaRepository
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * "Разделить счёт" — split a past event's bill across those who ACTUALLY attended.
 *
 * The unique template with a VERIFIED anchor: the participant set comes from organizer-marked
 * attendance, not the organizer's free choice — so randoms can't be mobilized, and the benefit was
 * already consumed (you were there), which removes the free-rider escape. `totalGoalKopecks` is the
 * bill (= the goal the progress bar fills to) in either mode. Deliberate cross-module read of the
 * event domain (skladchina → event) — split is inherently about an event; the dependency is one-way.
 *
 * Two split modes (organizer's choice on the form):
 *  - `fixed_equal`  — the bill is split equally across attendees; each pays a server-assigned share.
 *  - `voluntary`    — each attendee enters their own amount; the bar fills toward the bill by what
 *                     people contribute (uneven orders). `fixed_individual` is NOT offered — split
 *                     never has the organizer assign per-head amounts up front.
 */
@Component
class SplitBillTemplate(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val skladchinaRepository: SkladchinaRepository,
) : SkladchinaTemplateStrategy {

    override val type = SkladchinaTemplate.split_bill
    override val outcomesVerified = true
    // You already consumed the good (you attended) — a free decline would be free-riding, so a
    // decline must be justified and organizer-approved (V28).
    override val declinePolicy = DeclinePolicy.REQUIRES_APPROVAL

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
        // One split per event: an active one blocks (open it instead), a successfully-closed one
        // blocks (already collected). A failed/cancelled split does NOT block — the organizer retries.
        skladchinaRepository.findBlockingByEventId(eventId)?.let { existing ->
            throw if (existing.status == SkladchinaStatus.active) {
                ValidationException("По этому событию уже есть активный сбор — откройте его")
            } else {
                ValidationException("По этому событию счёт уже собран")
            }
        }

        // Split offers exactly two modes; fixed_individual (organizer assigns per-head) makes no
        // sense when the bill is the anchor, so it's rejected rather than silently coerced.
        val mode = when (request.paymentMode) {
            SkladchinaMode.fixed_equal.literal -> SkladchinaMode.fixed_equal
            SkladchinaMode.voluntary.literal -> SkladchinaMode.voluntary
            else -> throw ValidationException("Для счёта выберите режим: поровну или каждый сам")
        }

        val bill = request.totalGoalKopecks
            ?: throw ValidationException("Укажите сумму чека")
        if (bill <= 0) throw ValidationException("Сумма чека должна быть положительной")

        val attendedAll = eventResponseRepository.findAttendedUserIds(eventId)
        // Only still-active members can be in the skladchina (page access + DM). Someone who
        // attended but has since left the club is silently dropped (their share is uncollectable here).
        val notActive = skladchinaRepository.findNonActiveMembers(clubId, attendedAll)
        val attended = attendedAll
            .filter { it !in notActive }
            // "Исключить себя": the organizer attended but isn't being charged — drop them so the
            // equal share divides across the rest and no payment panel is shown to them (not a participant).
            .filter { !request.excludeSelf || it != creatorId }
        if (attended.size < MIN_ATTENDED) {
            throw ValidationException("Нужно минимум $MIN_ATTENDED пришедших участника для разделения счёта")
        }

        // fixed_equal: server-assigned equal share. voluntary: no assigned share — each enters
        // their own amount when paying; the bill stays the goal the bar fills toward.
        val participants: List<Pair<UUID, Long?>> =
            if (mode == SkladchinaMode.voluntary) attended.map { it to null }
            else SkladchinaShares.equal(bill, attended).map { it.first to (it.second as Long?) }
        return TemplateResolution(mode, bill, participants, eventId)
    }

    companion object {
        private const val MAX_EVENT_AGE_DAYS = 30L
        private const val MIN_ATTENDED = 2
    }
}

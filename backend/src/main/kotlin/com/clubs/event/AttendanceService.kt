package com.clubs.event

import com.clubs.club.ClubRepository
import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class AttendanceService(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val clubRepository: ClubRepository,
    private val eventPublisher: ApplicationEventPublisher,
    // Окно спора (в минутах) до финализации посещаемости (PRD §4.4.3, дефолт 48ч = 2880).
    // Единица — минуты, чтобы на staging можно было выставить буквально 5 для сквозного теста репутации.
    @Value("\${events.dispute-window-minutes:2880}") private val disputeWindowMinutes: Long,
    // EXP-2: дедлайн (в минутах после event_datetime) для нейтральной авто-финализации неотмеченных
    // прошедших событий. Дефолт 48ч. Единица — минуты, чтобы на staging можно было выставить 5.
    @Value("\${events.auto-finalize-unmarked-minutes:2880}") private val autoFinalizeUnmarkedMinutes: Long
) {

    private val log = LoggerFactory.getLogger(AttendanceService::class.java)

    @Transactional
    fun markAttendance(eventId: UUID, organizerId: UUID, request: MarkAttendanceRequest): AttendanceResultDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        val club = clubRepository.findById(event.clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != organizerId) throw ForbiddenException("Only the club organizer can mark attendance")

        // ATT-4: после финализации ростер заморожен и репутация посчитана; повторная отметка
        // молча рассинхронизировала бы отображаемую посещаемость с зафиксированным леджером
        // (recompute не перезапускается — claimEvent одноразовый). Зеркалит dispute/resolve,
        // которые уже это проверяют.
        if (event.attendanceFinalized) {
            throw ValidationException("Attendance has been finalized")
        }

        if (event.eventDatetime.isAfter(OffsetDateTime.now())) {
            throw ValidationException("Cannot mark attendance before the event takes place")
        }

        val newlyAbsentUserIds = mutableListOf<UUID>()
        var markedCount = 0
        request.attendance.forEach { entry ->
            val updated = eventResponseRepository.setAttendance(eventId, entry.userId, entry.attended)
            if (updated > 0) {
                markedCount++
                // setAttendance затрагивает только настоящие переходы (IS DISTINCT FROM target),
                // так что updated > 0 при attended=false означает: эта строка ТОЛЬКО ЧТО стала
                // отсутствующей — именно она получает DM "оспорьте". Повторная отметка уже
                // отсутствующей строки затрагивает 0 строк → без повторной DM (F5-15.2).
                if (!entry.attended) newlyAbsentUserIds.add(entry.userId)
            }
        }

        // F5-09: markAttendanceMarked защищён условием attendance_finalized=false. 0 строк означает,
        // что финализатор выиграл гонку TOCTOU и финализировал событие между проверкой выше и этим
        // моментом — отклонить и откатить записи setAttendance (единая @Transactional).
        if (eventRepository.markAttendanceMarked(eventId) == 0) {
            throw ValidationException("Attendance has been finalized")
        }

        // ATT-3 / F5-15.2: уведомляем только НОВЫХ отсутствующих участников (DM "вас отметили
        // отсутствующим, оспорьте"), чтобы окно спора было доступно без спама всем при повторной
        // отметке. Публикуется, а не вызывается напрямую: @Async DM резолвит получателей на
        // отдельном соединении ПОСЛЕ коммита этой транзакции. AttendanceMarkedListener реагирует
        // AFTER_COMMIT — та же проблема, что пайплайн репутации решает для AttendanceFinalizedEvent.
        eventPublisher.publishEvent(AttendanceMarkedEvent(eventId, newlyAbsentUserIds))

        log.info("Attendance marked: eventId={} markedCount={} newlyAbsent={} organizerId={}", eventId, markedCount, newlyAbsentUserIds.size, organizerId)
        return AttendanceResultDto(eventId, markedCount)
    }

    /**
     * F5-04: СОБСТВЕННОЕ состояние посещаемости вызывающего для события. Намеренно НЕ завязано на
     * членство в клубе — участник, покинувший клуб (или с истёкшей подпиской), всё равно получает
     * штраф no_show и DM "оспорьте", так что должен иметь доступ к UI спора. Ограничено его
     * собственной строкой ответа; [MyAttendanceDto.canDispute] — единственный источник истины,
     * от которого фронтенд включает кнопку спора.
     */
    @Transactional(readOnly = true)
    fun getMyAttendance(eventId: UUID, userId: UUID): MyAttendanceDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")
        val response = eventResponseRepository.findByEventAndUser(eventId, userId)
            ?: throw NotFoundException("No participation in this event")

        val windowOpen = event.attendanceMarked && !event.attendanceFinalized
        val canDispute = windowOpen &&
            response.attendance == AttendanceStatus.absent &&
            !response.disputeTerminal
        return MyAttendanceDto(
            attendance = response.attendance?.literal,
            attendanceMarked = event.attendanceMarked,
            attendanceFinalized = event.attendanceFinalized,
            disputeTerminal = response.disputeTerminal,
            canDispute = canDispute,
            disputeNote = response.disputeNote
        )
    }

    @Transactional
    fun disputeAttendance(eventId: UUID, userId: UUID, note: String?): AttendanceResultDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        if (event.attendanceFinalized) {
            throw ValidationException("Attendance has been finalized and cannot be disputed")
        }

        if (!event.attendanceMarked) {
            throw ValidationException("Attendance has not been marked yet")
        }

        val updated = eventResponseRepository.disputeAbsentAttendance(eventId, userId, note?.trim()?.ifBlank { null })
        if (updated == 0) {
            throw ValidationException("No absent attendance to dispute")
        }

        // Организатор должен узнать о споре пока окно ещё открыто: молчание превращает отметку
        // обратно в absent (штраф no_show) при финализации. Переход через AFTER_COMMIT по той же
        // причине, что и AttendanceMarkedEvent — @Async DM читает уже закоммиченные строки.
        eventPublisher.publishEvent(AttendanceDisputedEvent(eventId, userId))

        log.info("Attendance disputed: eventId={} userId={} hasNote={}", eventId, userId, note != null)
        return AttendanceResultDto(eventId, updated)
    }

    @Transactional
    fun resolveDispute(eventId: UUID, organizerId: UUID, userId: UUID, attended: Boolean): AttendanceResultDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")
        val club = clubRepository.findById(event.clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != organizerId) throw ForbiddenException("Only the club organizer can resolve disputes")

        if (event.attendanceFinalized) {
            throw ValidationException("Attendance has been finalized")
        }

        // Отклонить no-op разрешение спора: если у целевого пользователя нет спорной отметки по
        // этому событию, update затронет 0 строк. Возврат успеха обманул бы организатора и скрыл
        // бы неверный userId / уже разрешённый спор. Зеркалит проверку "0 строк" в disputeAttendance.
        val updated = eventResponseRepository.resolveDisputedAttendance(eventId, userId, attended)
        if (updated == 0) {
            throw ValidationException("No disputed attendance to resolve for this user")
        }

        log.info("Dispute resolved: eventId={} userId={} attended={} organizerId={}", eventId, userId, attended, organizerId)
        return AttendanceResultDto(eventId, updated)
    }

    @Scheduled(fixedDelayString = "\${events.finalize-poll-ms:3600000}")
    @Transactional
    fun finalizeAttendance() {
        val cutoff = OffsetDateTime.now().minusMinutes(disputeWindowMinutes)
        val finalizedEventIds = eventRepository.finalizeAttendanceBefore(cutoff)
        if (finalizedEventIds.isEmpty()) return

        // ATT-2: окно спора истекло без корректировки организатора, так что исходная отметка
        // остаётся в силе — превращаем оставшиеся `disputed` обратно в `absent`. Выполняется в той
        // же транзакции, до того как слушатель репутации прочитает ростер (AFTER_COMMIT), поэтому
        // (going, absent) маппится в no_show, а не в confirmed_unresolved (0). Спорная отметка может
        // существовать только на отмеченном событии, так что нейтрально-финализированные
        // (неотмеченные) события не затрагиваются. См. events.md § ATT-2.
        val resolved = eventResponseRepository.resolveExpiredDisputesToAbsent(finalizedEventIds)
        log.info("Finalized attendance for {} events ({} expired disputes → absent)", finalizedEventIds.size, resolved)
        // Слушатель репутации (AFTER_COMMIT) подхватывает их для низколатентной обработки леджера;
        // почасовой опрос — надёжная подстраховка. См. reputation-v2.md.
        finalizedEventIds.forEach { eventPublisher.publishEvent(AttendanceFinalizedEvent(it)) }
    }

    /**
     * EXP-2: нейтрально финализирует прошедшие события, посещаемость которых организатор так и не
     * отметил, по истечении дедлайна ([autoFinalizeUnmarkedMinutes] после `event_datetime`).
     * Выставляет `attendance_finalized = true`, оставляя `attendance_marked = false`, так что
     * пайплайн репутации (который забирает только marked+finalized события) НЕ создаёт строк в
     * леджере — событие просто не засчитывается (ни +100, ни −50). Добросовестные участники не
     * наказываются за бездействие организатора, и никто на этом не выигрывает. Намеренно НЕ
     * публикует AttendanceFinalizedEvent. Та же периодичность опроса, что и [finalizeAttendance];
     * два пути затрагивают непересекающиеся строки (marked=true vs marked=false). См. events.md
     * § EXP-2 и reputation-v2.md.
     */
    @Scheduled(fixedDelayString = "\${events.finalize-poll-ms:3600000}")
    @Transactional
    fun neutrallyFinalizeUnmarkedEvents() {
        val cutoff = OffsetDateTime.now().minusMinutes(autoFinalizeUnmarkedMinutes)
        val ids = eventRepository.neutrallyFinalizeUnmarkedBefore(cutoff)
        if (ids.isNotEmpty()) {
            log.info("Neutrally auto-finalized {} unmarked past events (no reputation accrued)", ids.size)
        }
    }
}

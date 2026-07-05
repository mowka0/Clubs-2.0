package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationAxis
import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.generated.jooq.enums.ReputationSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Основной пайплайн репутации поверх append-only леджера (reputation v2, P1a).
 * Таблица user_club_reputation — производный кэш: recompute() — ЕДИНСТВЕННЫЙ, кто в неё пишет.
 * Баг B (почасовое переначисление) невозможен по конструкции — строки леджера уникальны по
 * (user, source) и вставляются через ON CONFLICT DO NOTHING; агрегаты пересчитываются, а не
 * инкрементируются. См. docs/modules/reputation-v2.md.
 */
@Service
class ReputationService(
    private val repository: ReputationRepository
) {

    private val log = LoggerFactory.getLogger(ReputationService::class.java)

    /**
     * Проводит одно финализированное событие в леджер. Вызывается и слушателем событий
     * (низкая задержка), и поллером (надёжный бэкстоп); атомарный claim делает их взаимоисключающими.
     * REQUIRES_NEW, чтобы каждое событие коммитилось независимо, а при ошибке claim откатывался
     * (поллер потом повторит попытку) — должен вызываться через границу бина (ReputationScheduler /
     * AttendanceFinalizedListener), чтобы сработал прокси.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun processFinalizedEvent(eventId: UUID) {
        if (!repository.claimEvent(eventId)) return // уже обработано — ничего не делаем

        val ctx = repository.findEventContext(eventId) ?: return
        val entries = repository.findConfirmedResponses(eventId)
            .filter { it.userId != ctx.ownerId } // анти-фарм правило 1: владелец не копит репутацию в своём клубе
            .map { response ->
                val kind = ReputationPolicy.attendanceKind(response.stage1Vote, response.attendance)
                LedgerEntry(
                    userId = response.userId,
                    clubId = ctx.clubId,
                    axis = ReputationAxis.attendance,
                    kind = kind,
                    points = ReputationPolicy.pointsFor(kind),
                    occurredAt = ctx.eventDatetime,
                    sourceType = ReputationSource.event,
                    sourceId = eventId
                )
            }

        appendAndRecompute(entries)
        log.info("Reputation processed: eventId={} entries={}", eventId, entries.size)
    }

    /**
     * Exit-with-obligations (P1b дыра B): записывает штрафы за обязательства, которые пользователь
     * бросает, покидая клуб, ДО того, как каскад членства удалит исходные строки. Каждое брошенное
     * подтверждённое бронирование → `no_show` (−200); каждая pending влияющая на репутацию складчина
     * с ещё не истёкшим дедлайном → `skladchina_expired` (−40). Присоединяется к транзакции выхода
     * вызывающего, так что штраф + каскад коммитятся атомарно. Идемпотентно по конструкции: леджер
     * UNIQUE(user, source_type, source_id) + ON CONFLICT DO NOTHING означает, что более поздний
     * естественный исход для того же source конфликтует, и строка выхода побеждает — двойной выход
     * никогда не даёт двойного учёта.
     */
    @Transactional
    fun penalizeExit(
        userId: UUID,
        clubId: UUID,
        eventNoShows: List<ExitObligation>,
        skladchinaExpiries: List<ExitObligation>
    ) {
        if (eventNoShows.isEmpty() && skladchinaExpiries.isEmpty()) return
        val entries = eventNoShows.map {
            LedgerEntry(
                userId = userId,
                clubId = clubId,
                axis = ReputationAxis.attendance,
                kind = ReputationKind.no_show,
                points = ReputationPolicy.pointsFor(ReputationKind.no_show),
                occurredAt = it.occurredAt,
                sourceType = ReputationSource.event,
                sourceId = it.sourceId
            )
        } + skladchinaExpiries.map {
            LedgerEntry(
                userId = userId,
                clubId = clubId,
                axis = ReputationAxis.finance,
                kind = ReputationKind.skladchina_expired,
                points = ReputationPolicy.pointsFor(ReputationKind.skladchina_expired),
                occurredAt = it.occurredAt,
                sourceType = ReputationSource.skladchina,
                sourceId = it.sourceId
            )
        }
        appendAndRecompute(entries)
        log.info(
            "Exit penalties written: userId={} clubId={} eventNoShows={} skladchinaExpiries={}",
            userId, clubId, eventNoShows.size, skladchinaExpiries.size
        )
    }

    /**
     * Штраф за отказ от ПОДТВЕРЖДЁННОГО места на Этапе 2, когда в очереди нет замены (abandoned_slot,
     * −100). Вызывается из Stage2Service.declineParticipation В ТОЙ ЖЕ транзакции (default REQUIRED →
     * отказ + штраф атомарны), только при пустом waitlist — иначе слот сразу закрывает первый из
     * очереди, ущерба нет. Идемпотентно по конструкции леджера: UNIQUE(user, source) + ON CONFLICT DO
     * NOTHING (одна строка на пару user×event; у отказавшегося её ещё нет — явка не размечалась).
     * occurredAt = момент отказа (якорь decay).
     */
    @Transactional
    fun penalizeAbandonedSlot(userId: UUID, clubId: UUID, eventId: UUID, occurredAt: OffsetDateTime) {
        appendAndRecompute(
            listOf(
                LedgerEntry(
                    userId = userId,
                    clubId = clubId,
                    axis = ReputationAxis.attendance,
                    kind = ReputationKind.abandoned_slot,
                    points = ReputationPolicy.pointsFor(ReputationKind.abandoned_slot),
                    occurredAt = occurredAt,
                    sourceType = ReputationSource.event,
                    sourceId = eventId
                )
            )
        )
        log.info("Abandoned-slot penalty: userId={} clubId={} eventId={}", userId, clubId, eventId)
    }

    /**
     * Добавляет строки в леджер (идемпотентно) и пересчитывает кэш для каждой затронутой
     * пары (user, club). Без новой транзакции — присоединяется к транзакции вызывающего
     * (REQUIRES_NEW из processFinalizedEvent, либо транзакция закрытия складчины).
     */
    @Transactional
    fun appendAndRecompute(entries: List<LedgerEntry>) {
        if (entries.isEmpty()) return
        repository.appendLedgerIfAbsent(entries)
        // Детерминированный порядок (user, club) — recompute берёт по одной advisory xact-lock на
        // пару, и две конкурентные транзакции (посещение события × закрытие складчины в одном и том
        // же клубе), захватывающие одни и те же пары в разном порядке, дают дедлок (40P01).
        // Сортировка гарантирует, что каждый вызывающий блокирует в одном и том же глобальном порядке (F5-13).
        entries.map { it.userId to it.clubId }.toSet()
            .sortedWith(compareBy({ it.first }, { it.second }))
            .forEach { (userId, clubId) -> repository.recompute(userId, clubId) }
    }
}

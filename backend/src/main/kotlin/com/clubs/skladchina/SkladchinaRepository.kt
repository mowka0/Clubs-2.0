package com.clubs.skladchina

import com.clubs.common.dto.PageResponse
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.SkladchinaStatus
import java.time.OffsetDateTime
import java.util.UUID

interface SkladchinaRepository {

    fun create(
        skladchina: Skladchina,
        participants: List<Pair<UUID, Long?>>          // (userId, expectedAmountKopecks)
    ): Skladchina

    fun findById(id: UUID): Skladchina?

    /**
     * Складчина, которая БЛОКИРУЕТ создание новой для [eventId]: активная ИЛИ успешно закрытая.
     * Активная возвращается в приоритете (на неё ведёт кнопка на EventPage), иначе — последняя
     * closed_success. null → блокера нет, новую складчину можно создать. Провалившаяся/отменённая
     * складчина НЕ блокирует (можно повторить попытку).
     */
    fun findBlockingByEventId(eventId: UUID): Skladchina?

    fun findActiveByClub(clubId: UUID): List<Skladchina>

    /**
     * Возвращает ВСЕ складчины указанного клуба (любой статус при [includeCompleted] = true,
     * иначе только активные) с батчево подгруженными агрегатами (собранная сумма, количество
     * участников). Отсортировано по `created_at DESC, id ASC` для стабильного слияния с лентой событий.
     *
     * Используется единой лентой активностей. НЕ подгружает поля, зависящие от вызывающего пользователя —
     * `myStatus` и т.п. вычисляется на экране деталей, а не в ленте.
     */
    fun findAllByClubWithAggregates(clubId: UUID, includeCompleted: Boolean): List<SkladchinaWithAggregates>

    fun findMyFeed(userId: UUID, page: Int, size: Int): PageResponse<MySkladchinaFeedItem>

    /**
     * Количество активных складчин, где пользователь — участник, всё ещё ожидающий
     * оплаты (status='pending'). Отражает флаг `actionRequired`, который лента
     * вычисляет для каждого элемента — используется для бейджа таба "Сборы", чтобы
     * неоплаченные обязательства не терялись из виду.
     */
    fun countActionRequired(userId: UUID): Int

    fun findExpiredActive(now: OffsetDateTime): List<Skladchina>

    /**
     * Атомарная заявка на закрытие (F5-12): выставляет финальный status/closed_at/closed_by ТОЛЬКО
     * если складчина ещё `active`. Возвращает false, если другой закрывающий (шедулер ×
     * автозакрытие × ручное) уже выиграл гонку — проигравший должен ничего не делать, чтобы участники
     * были expired/released ровно один раз и SkladchinaClosedEvent публиковался ровно один раз.
     * Тот же паттерн rows-affected, что и в JooqReputationRepository.claimEvent.
     */
    fun claimClose(id: UUID, status: SkladchinaStatus, closedBy: UUID?, closedAt: OffsetDateTime): Boolean

    /** Возвращает участников с присоединённой информацией о пользователе — для вида организатора + хука репутации. */
    fun findParticipantsWithInfo(skladchinaId: UUID): List<SkladchinaParticipantInfo>

    /** Простые записи участников — для логики конечного автомата (отметить оплату, хук репутации). */
    fun findParticipants(skladchinaId: UUID): List<SkladchinaParticipant>

    fun findParticipant(skladchinaId: UUID, userId: UUID): SkladchinaParticipant?

    /**
     * Переводит участника `pending` → `paid` (F5-03: защищено условием
     * `WHERE status = 'pending'`). Возвращает число затронутых строк — 0 означает, что участник
     * был параллельно expired/released/declined, и вызывающий должен вернуть 409.
     */
    fun setParticipantPaid(
        skladchinaId: UUID,
        userId: UUID,
        declaredAmountKopecks: Long,
        paidAt: OffsetDateTime
    ): Int

    /** Переводит `pending` → `declined`; тот же контракт rows-affected, что и [setParticipantPaid]. */
    fun setParticipantDeclined(
        skladchinaId: UUID,
        userId: UUID,
        declinedAt: OffsetDateTime
    ): Int

    /**
     * A-2 (отмена организатором): переводит `paid` → `pending`, очищая declared_amount
     * и paid_at. Защищено условием `WHERE status = 'paid'` — возвращает число затронутых строк
     * (0 означает, что участник параллельно был закрыт закрытием складчины и не должен переоткрываться).
     */
    fun revertParticipantToPending(skladchinaId: UUID, userId: UUID): Int

    /**
     * V28: открывает запрос на отказ (шаблоны REQUIRES_APPROVAL) — заметка + временная метка. Защищено
     * условием: участник всё ещё `pending`, и путь отказа ещё не закрыт (decline_rejected=false).
     * Возвращает число затронутых строк.
     */
    fun requestDecline(skladchinaId: UUID, userId: UUID, note: String, requestedAt: OffsetDateTime): Int

    /**
     * V28/V29: организатор отклоняет запрос на отказ — закрывает путь (decline_rejected=true),
     * сохраняет обязательное обоснование [note] и очищает открытый запрос; участник остаётся
     * `pending` (обязан заплатить). Защищено: участник pending с открытым запросом. Возвращает число затронутых строк.
     */
    fun rejectDeclineRequest(skladchinaId: UUID, userId: UUID, note: String): Int

    /**
     * Сдвигает дедлайн ВПЕРЁД на [newDeadline] (защита: активна + текущий дедлайн раньше нового).
     * Используется, когда запрос на отказ приходит с <48ч до дедлайна — у организатора всегда должно
     * быть 48-часовое окно на решение. Возвращает число затронутых строк (0 = изменение не требуется).
     */
    fun extendDeadline(skladchinaId: UUID, newDeadline: OffsetDateTime): Int

    /** Переводит всех участников `pending` в `expired_no_response` (закрытие в момент дедлайна или после). */
    fun expirePendingParticipants(skladchinaId: UUID): Int

    /**
     * Переводит всех участников `pending` в `released` — складчина закрылась ДО своего
     * дедлайна, поэтому молчание не нарушило никакого обещания (F5-02). Нейтрально: для этого
     * статуса не создаются строки в леджере (ReputationPolicy.financeKind(released) = null).
     */
    fun releasePendingParticipants(skladchinaId: UUID): Int

    fun markReputationApplied(skladchinaId: UUID, userId: UUID)

    /** Сумма declared_amount по участникам со статусом 'paid'. */
    fun sumCollectedKopecks(skladchinaId: UUID): Long

    fun countParticipants(skladchinaId: UUID): Int

    fun countParticipantsByStatus(skladchinaId: UUID, status: SkladchinaParticipantStatus): Int

    /** Возвращает подмножество указанных userIds, которые НЕ являются активными участниками указанного клуба. */
    fun findNonActiveMembers(clubId: UUID, userIds: Collection<UUID>): Set<UUID>

    /**
     * Количество влияющих на репутацию складчин клуба, созданных после [since] —
     * питает рейт-лимит "≤3 важных складчины на клуб за скользящие 7 дней"
     * (единственный настоящий анти-фарм И анти-грифинг механизм редизайна).
     */
    fun countReputationAffectingCreatedSince(clubId: UUID, since: OffsetDateTime): Int

    /**
     * Активные влияющие на репутацию складчины, чей дедлайн попадает в (now, until]
     * и для которых напоминание ещё не отправлено — фид для SkladchinaReminderScheduler.
     */
    fun findNeedingDeadlineReminder(now: OffsetDateTime, until: OffsetDateTime): List<Skladchina>

    /** Штамп дедупликации для напоминания о дедлайне (ставится ДО отправки, как и напоминания о событиях). */
    fun markReminderSent(skladchinaId: UUID, at: OffsetDateTime)

    /**
     * Выход с обязательствами (P1b дыра B): PENDING-участия [userId] в активных, влияющих на
     * репутацию складчинах клуба [clubId] — финансовые обязательства, нарушенные выходом (каждое →
     * skladchina_expired −40, occurred_at = deadline). Дедлайн НЕ фильтруется: каскад выхода удаляет
     * каждую такую строку участника, поэтому pending с уже прошедшим дедлайном иначе избежал бы и
     * штрафа за выход, и естественного истечения. Результат выхода эквивалентен тому, что записало бы
     * естественное истечение (−40), а более поздняя естественная строка столкнётся на UNIQUE леджера —
     * дубля не будет. Тот же охват, что удаляет каскад ([deleteParticipantFromActiveSkladchinasInClub]).
     * Читать ДО каскада.
     */
    fun findPendingReputationObligations(userId: UUID, clubId: UUID): List<SkladchinaObligation>

    /**
     * Каскадное удаление при выходе из клуба: убирает [userId] из каждой активной складчины
     * клуба [clubId]. Закрытые/отменённые складчины сохраняются как исторические
     * обязательства. Возвращает число удалённых строк.
     */
    fun deleteParticipantFromActiveSkladchinasInClub(userId: UUID, clubId: UUID): Int

    /**
     * Каскад мягкого удаления клуба: отменяет каждую активную складчину клуба [clubId] и освобождает
     * её ожидающих участников (pending → released — нейтральный для репутации терминальный статус, НЕ
     * expired_no_response, который бы штрафовал). Намеренно обходит SkladchinaService.closeInternal,
     * чтобы для удаляемого клуба не срабатывали ни дельты репутации, ни SkladchinaClosedEvent DM.
     * Уже закрытые/отменённые складчины не трогаются. Возвращает число отменённых складчин.
     */
    fun cancelActiveByClub(clubId: UUID): Int

    /**
     * Отменяет активную складчину, привязанную к [eventId] (отмена события F5-14): ожидающие участники
     * освобождаются (без влияния на репутацию), складчина → cancelled. closed_success складчина
     * остаётся нетронутой (деньги уже собраны). Возвращает число затронутых строк складчины.
     */
    fun cancelActiveByEventId(eventId: UUID): Int
}

/**
 * Ожидающее влияющее на репутацию участие, которое покидающий пользователь бросает: id складчины
 * (source_id в леджере) + её дедлайн (occurred_at для skladchina_expired). Читается при выходе из клуба.
 */
data class SkladchinaObligation(
    val skladchinaId: UUID,
    val deadline: OffsetDateTime
)

/**
 * Строка ленты, не зависящая от вызывающего пользователя: складчина плюс агрегаты, используемые
 * карточкой единой ленты активностей. Без `myStatus` / `clubName` — они относятся к персональным
 * представлениям (`MySkladchinaFeedItem`).
 */
data class SkladchinaWithAggregates(
    val skladchina: Skladchina,
    val collectedKopecks: Long,
    val participantCount: Int,
    val paidCount: Int
)

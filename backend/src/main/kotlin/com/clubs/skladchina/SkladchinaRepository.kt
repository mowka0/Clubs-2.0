package com.clubs.skladchina

import com.clubs.common.dto.PageResponse
import com.clubs.generated.jooq.enums.SkladchinaStatus
import java.time.OffsetDateTime
import java.util.UUID

interface SkladchinaRepository {

    fun create(skladchina: Skladchina): Skladchina

    fun findById(id: UUID): Skladchina?

    /**
     * Строка сбора под блокировкой (`SELECT … FOR UPDATE`) на время транзакции: «Беру» / «Перевёл» /
     * «В деле» сериализуются с «Заказываю» / «Закрыть запись», которые обновляют ту же строку.
     */
    fun findByIdForUpdate(id: UUID): Skladchina?

    /**
     * Сбор, который БЛОКИРУЕТ новый «Скинуться» по встрече [eventId]: активный или собранный.
     * Активный в приоритете (на него ведёт кнопка на EventPage). Отменённый не блокирует.
     */
    fun findBlockingByEventId(eventId: UUID): Skladchina?

    fun findActiveByClub(clubId: UUID): List<Skladchina>

    /**
     * Встречи клуба, по которым ещё можно скинуться: завершённые, с отмеченной явкой, не старше
     * [notOlderThan], без блокирующего сбора и минимум с [minAttended] пришедшими активными участниками.
     */
    fun findSplittableEvents(clubId: UUID, notOlderThan: OffsetDateTime, minAttended: Int): List<SplittableEvent>

    /**
     * Все сборы клуба (любой статус при [includeCompleted], иначе только активные) с батчевыми
     * итогами по долгам, без сборов, скрытых от [viewerId]. Сортировка `created_at DESC, id ASC`.
     */
    fun findAllByClubWithAggregates(clubId: UUID, includeCompleted: Boolean, viewerId: UUID): List<SkladchinaWithAggregates>

    /** Сборы, где [userId] создатель, должник или отметился «В деле»; скрытые от него не входят. */
    fun findMyFeed(userId: UUID, page: Int, size: Int): PageResponse<MySkladchinaFeedItem>

    /**
     * Атомарный переход active → [status] (`UPDATE … WHERE status = 'active'`). false = другой
     * закрывающий (шедулер × автозакрытие × рука) уже выиграл гонку — проигравший ничего не делает.
     */
    fun claimClose(id: UUID, status: SkladchinaStatus, closedAt: OffsetDateTime): Boolean

    /** Атомарная заморозка списка «Кто в деле?»: только активный сбор с ещё открытой записью. */
    fun claimLock(id: UUID, lockedAt: OffsetDateTime): Boolean

    /** Атомарное «Заказываю»: только активный per_head без ordered_at. */
    fun claimOrder(id: UUID, orderedAt: OffsetDateTime): Boolean

    /** Активные сборы с этапом записи, срок которого наступил, а заморозки ещё не было. */
    fun findEnrollmentDue(now: OffsetDateTime): List<Skladchina>

    /**
     * Активные per_head без заказа с прошедшим сроком, которым пора напомнить создателю: ещё
     * не напоминали или напоминали раньше [remindedBefore] (раз в день).
     */
    fun findPerHeadNeedingOrderReminder(now: OffsetDateTime, remindedBefore: OffsetDateTime): List<Skladchina>

    fun markOrderReminded(id: UUID, at: OffsetDateTime)

    /**
     * Активные не тихие сборы со сроком в (now, until], по которым чат-напоминание ещё не уходило —
     * фид напоминания «за 24 часа» в чат клуба.
     */
    fun findNeedingDeadlineReminder(now: OffsetDateTime, until: OffsetDateTime): List<Skladchina>

    /** Штамп дедупликации чат-напоминания (ставится ДО отправки). */
    fun markReminderSent(id: UUID, at: OffsetDateTime)

    /** Подмножество [userIds], которые НЕ активные участники клуба [clubId]. */
    fun findNonActiveMembers(clubId: UUID, userIds: Collection<UUID>): Set<UUID>

    /** Все активные участники клуба — адресаты DM о сборе на весь клуб. */
    fun findActiveMemberIds(clubId: UUID): List<UUID>

    /**
     * Каскад мягкого удаления клуба: активные сборы → cancelled, их открытые долги → forgiven.
     * Минует сервисы намеренно — репутация и DM при удалении клуба не трогаются. Возвращает число сборов.
     */
    fun cancelActiveByClub(clubId: UUID): Int

    /** Отмена встречи: привязанный активный сбор → cancelled, открытые долги → forgiven. */
    fun cancelActiveByEventId(eventId: UUID): Int

    // --- Этап «Кто в деле?» (skladchina_enrollments) ---

    /** «В деле»: ON CONFLICT DO NOTHING; false = уже отмечен. */
    fun addEnrollment(skladchinaId: UUID, userId: UUID): Boolean

    /** «Передумал» до заморозки. Возвращает число удалённых строк. */
    fun removeEnrollment(skladchinaId: UUID, userId: UUID): Int

    fun findEnrolledUserIds(skladchinaId: UUID): List<UUID>

    fun countEnrolled(skladchinaId: UUID): Int

    fun isEnrolled(skladchinaId: UUID, userId: UUID): Boolean

    /** Выход/кик из клуба: снять отметки «В деле» в ещё не замороженных сборах клуба. Возвращает id сборов. */
    fun removeEnrollmentsForUserInClub(userId: UUID, clubId: UUID): List<UUID>
}

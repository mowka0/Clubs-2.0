package com.clubs.event

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.enums.Stage_2Vote
import java.time.OffsetDateTime
import java.util.UUID

interface EventResponseRepository {

    fun upsertStage1Vote(eventId: UUID, userId: UUID, vote: Stage_1Vote): EventResponse

    /**
     * Создаёт строку ответа для участника, который НЕ голосовал на Этапе 1, но подтверждает участие
     * на Этапе 2 (Этап 2 открыт всем участникам клуба). `stage_1_vote` остаётся NULL; `stage_1_timestamp`
     * ставится в now — это ключ FIFO очереди waitlist, поэтому поздний участник встаёт В КОНЕЦ, после
     * голосовавших на Этапе 1 (у них метка из прошлого). Вызывать только под slot-lock — сериализация
     * confirm'ов гарантирует, что UNIQUE(event_id, user_id) не нарушится гонкой.
     */
    fun createLateStage2Entry(eventId: UUID, userId: UUID): EventResponse

    fun findByEventAndUser(eventId: UUID, userId: UUID): EventResponse?

    fun countByVote(eventId: UUID): Map<String, Int>

    fun countConfirmed(eventId: UUID): Int

    /**
     * S2-01/F5-07/F5-11: берёт per-event transaction-scoped Postgres advisory lock
     * (`pg_advisory_xact_lock`), сериализующий мутации слотов Этапа 2. Должен вызываться внутри
     * транзакции; освобождается автоматически при commit/rollback. И confirm, и decline берут его
     * перед чтением состояния слотов, поэтому проверки ёмкости и продвижение waitlist никогда не гонятся.
     */
    fun lockEventSlots(eventId: UUID)

    fun findFirstWaitlisted(eventId: UUID): EventResponse?

    fun updateStage2Vote(id: UUID, vote: Stage_2Vote, finalStatus: FinalStatus): EventResponse

    fun findGoingByEventOrderByTimestamp(eventId: UUID): List<EventResponse>

    fun findMaybeByEventOrderByTimestamp(eventId: UUID): List<EventResponse>

    /**
     * Feature A авто-истечение: для каждого начавшегося, запустившего Этап 2, неотменённого события
     * переводит going/maybe-ответы, которые так и не были подтверждены (stage_2_vote IS NULL), в
     * [com.clubs.generated.jooq.enums.Stage_2Vote.expired_no_confirm] /
     * [com.clubs.generated.jooq.enums.FinalStatus.expired_no_confirm]. Одно массовое обновление;
     * предикат NULL делает его идемпотентным и не трогает confirmed/waitlisted/declined.
     * Возвращает число обновлённых строк.
     */
    fun expireUnconfirmedForStartedEvents(now: OffsetDateTime): Int

    /**
     * Telegram id проголосовавших going/maybe, которые ещё НЕ подтвердили (stage_2_vote IS NULL).
     * Адресаты напоминания Feature A "подтверди участие" (~2ч до события).
     */
    fun findUnconfirmedVoterTelegramIds(eventId: UUID): List<Long>

    /**
     * Telegram id проголосовавших going/maybe — «заинтересованные» участники события. Используется
     * DM об ОТМЕНЕ события (F5-14, sendEventCancelled): о ней сообщаем только тем, кто выразил интерес,
     * а не всему клубу.
     */
    fun findStage2TargetTelegramIds(eventId: UUID): List<Long>

    /**
     * Telegram id аудитории приглашения на Этап 2 (sendStage2Started): участники клуба С ДОСТУПОМ,
     * которые НЕ голосовали not_going на Этапе 1 — т.е. going / maybe / вообще не ответившие. Этап 2
     * открыт всем участникам клуба, поэтому не ответивших тоже зовём подтвердить; проголосовавшим
     * not_going DM НЕ шлём (но подтвердить они всё равно смогут — см. Stage2Service.confirmParticipation).
     * Строится от memberships (LEFT JOIN event_responses), а не от голосов, иначе не ответившие бы выпали.
     */
    fun findStage2InviteTelegramIds(eventId: UUID): List<Long>

    /**
     * F5-15(2): telegram ID для данных (eventId, userIds) — участники, которые СТАЛИ absent именно
     * в этой отметке. Используется NotificationService.sendAttendanceMarked, чтобы повторная
     * отметка не рассылала DM всем, кто уже был отмечен как absent. Пустой ввод → пустой результат
     * (без запроса).
     */
    fun findTelegramIdsByEventAndUserIds(eventId: UUID, userIds: List<UUID>): List<Long>

    /**
     * Массово выставляет ATTENDANCE для пары (eventId, userId) в attended/absent.
     * Возвращает число обновлённых строк (0, если у пользователя нет строки ответа).
     */
    fun setAttendance(eventId: UUID, userId: UUID, attended: Boolean): Int

    /**
     * Помечает отметку absent как оспоренную (disputed), сохраняя опциональный свободный текст
     * [note] от участника. Возвращает число обновлённых строк (0, если пользователь не absent).
     */
    fun disputeAbsentAttendance(eventId: UUID, userId: UUID, note: String?): Int

    /**
     * Разрешает оспоренную (disputed) отметку в attended/absent. Возвращает число обновлённых строк
     * (0, если не disputed).
     */
    fun resolveDisputedAttendance(eventId: UUID, userId: UUID, attended: Boolean): Int

    /**
     * ATT-2: при финализации переводит все ещё `disputed`-отметки на данных событиях обратно в
     * `absent` (окно оспаривания истекло без правки организатора → исходная отметка остаётся в силе).
     * Возвращает число обновлённых строк. Пустой ввод → 0 (без запроса).
     */
    fun resolveExpiredDisputesToAbsent(eventIds: List<UUID>): Int

    /**
     * Exit-with-obligations (P1b дыра B): CONFIRMED-брони [userId] на активных, ещё не
     * финализированных событиях [clubId] — обязательства, нарушаемые выходом. Ровно тот же охват
     * событий, что и [deleteByUserAndClubAndActiveEvents] (status IN upcoming/stage_1/stage_2 AND
     * NOT attendance_finalized), отфильтрован до confirmed-строк и возвращён с датой/временем
     * каждого события (якорь для decay no_show). Финализированные события исключены и из этого
     * перечисления, и из каскада: их реальный исход посещаемости принадлежит пайплайну репутации.
     * Читается ДО того, как каскад удаляет строки.
     */
    fun findConfirmedActiveEventObligations(userId: UUID, clubId: UUID): List<EventObligation>

    /**
     * Продвигает самый ранний в очереди waitlisted-ответ события [eventId] (по времени голоса
     * Этапа 1) в confirmed, занимая слот, который только что освободил ушедший confirmed-участник.
     * Возвращает true, если кто-то был продвинут. Вызывающий ОБЯЗАН держать [lockEventSlots], чтобы
     * это никогда не гонялось с конкурентным confirm/decline, продвигающим ту же строку.
     */
    fun promoteFirstWaitlisted(eventId: UUID): Boolean

    /**
     * Каскадное удаление при выходе из клуба: убирает ответы [userId] на все активные, ещё не
     * финализированные события [clubId] (status IN upcoming/stage_1/stage_2 AND NOT
     * attendance_finalized). Завершённые, отменённые И финализированные по посещаемости события
     * сохраняются — их посещаемость это записанная история / ещё не обработанный исход репутации,
     * которым по-прежнему владеет пайплайн (событие может быть финализировано, пока status ещё
     * stage_2). Возвращает число удалённых строк.
     */
    fun deleteByUserAndClubAndActiveEvents(userId: UUID, clubId: UUID): Int

    /**
     * Возвращает всех проголосовавших по событию (тех, у кого есть голос Этапа 1), соединённых с
     * их данными пользователя, отсортированных going → maybe → not_going, затем по времени голоса.
     * Используется для рендера списка «кто идёт» на странице события.
     */
    fun findRespondersWithUsers(eventId: UUID): List<EventResponderInfo>

    /**
     * ID пользователей, чья посещаемость на [eventId] равна `attended` (отмечено организатором).
     * Верифицированный набор участников для шаблона складчины split_bill.
     */
    fun findAttendedUserIds(eventId: UUID): List<UUID>
}

/**
 * Confirmed-бронь уходящего пользователя на активном событии: id события (source_id в леджере)
 * + его дата/время (occurred_at для no_show). Читается при выходе из клуба, чтобы штрафовать
 * брошенные обязательства.
 */
data class EventObligation(
    val eventId: UUID,
    val eventDatetime: OffsetDateTime
)

/** Строка репозитория: данные пользователя-респондента + сырые enum'ы голоса/финального статуса/посещаемости. */
data class EventResponderInfo(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val stage1Vote: Stage_1Vote?,
    val finalStatus: FinalStatus?,
    val attendance: AttendanceStatus?,
    val disputeNote: String?
)

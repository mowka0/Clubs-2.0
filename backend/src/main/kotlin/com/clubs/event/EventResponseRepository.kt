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

    /** Размер очереди листа ожидания (stage_2_vote = waitlisted) — для «живого закрепа» в чате. */
    fun countWaitlisted(eventId: UUID): Int

    /**
     * S2-01/F5-07/F5-11: берёт per-event transaction-scoped Postgres advisory lock
     * (`pg_advisory_xact_lock`), сериализующий мутации слотов Этапа 2. Должен вызываться внутри
     * транзакции; освобождается автоматически при commit/rollback. И confirm, и decline берут его
     * перед чтением состояния слотов, поэтому проверки ёмкости и продвижение waitlist никогда не гонятся.
     */
    fun lockEventSlots(eventId: UUID)

    fun findFirstWaitlisted(eventId: UUID): EventResponse?

    fun updateStage2Vote(id: UUID, vote: Stage_2Vote, finalStatus: FinalStatus): EventResponse

    /**
     * Убирает участника из состава/очереди, возвращая строку в состояние «в наборе не участвует»
     * (stage_2_vote и final_status → NULL). Нужен формату 🎟 (V83): смена голоса «Иду» на
     * «Возможно»/«Не иду» до закрытия состава — это не отказ (declined — терминальный статус,
     * который закрыл бы дорогу назад и попал бы в счётчики отказов), а выход из набора.
     * Вызывать под [lockEventSlots]: следом освободившийся слот отдаётся первому из очереди.
     */
    fun clearStage2Vote(id: UUID): EventResponse

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
     * Telegram id участников события с данным статусом Этапа 2 — адресаты DM «состав собран»
     * (V83): confirmed получают «ждём вас», waitlisted — «вы в очереди». Отдельный метод, потому
     * что существующие выборки строятся от голосов Этапа 1, а состав живёт в stage_2_vote.
     */
    fun findTelegramIdsByStage2Vote(eventId: UUID, vote: Stage_2Vote): List<Long>

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
     * Продвигает самый ранний в очереди waitlisted-ответ события [eventId] (по времени вставания в
     * лист ожидания на Этапе 2, stage_2_timestamp) в confirmed, занимая слот, который только что
     * освободил ушедший confirmed-участник. Возвращает id продвинутого пользователя, либо null, если
     * очередь пуста. Вызывающий ОБЯЗАН держать [lockEventSlots], чтобы это никогда не гонялось с
     * конкурентным confirm/decline, продвигающим ту же строку.
     */
    fun promoteFirstWaitlisted(eventId: UUID): UUID?

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
     * Помечает ручное напоминание Этапа 2 отправленным для (eventId, [userIds]) и возвращает
     * telegram id тех, кому оно действительно уходит.
     *
     * У участника, промолчавшего на Этапе 1, строки ответа НЕТ — отмечать напоминание негде,
     * поэтому недостающие строки создаются здесь же (`stage_1_vote` и `stage_2_vote` остаются
     * NULL). Такая строка-заглушка намеренно невидима для остальной механики: авто-истечение
     * броней фильтрует `stage_1_vote IN (going, maybe)`, ростер требует непустой голос или
     * финальный статус, очередь продвигается по `stage_2_timestamp`. При подтверждении заглушка
     * просто становится обычным ответом (`createLateStage2Entry` не вызовется).
     *
     * Фильтры живут внутри UPDATE, а не в вызывающем коде: они же защищают от гонки двух
     * менеджеров, жмущих колокольчик одновременно. Напоминание получает только тот, кто
     *   - не сделал шаг Этапа 2 (`stage_2_vote IS NULL`) — подтвердившим, отказавшимся
     *     и стоящим в очереди напоминать не о чем;
     *   - не отказывался на Этапе 1 (`stage_1_vote IS DISTINCT FROM 'not_going'`) — человека,
     *     который прямо сказал «не пойду», уведомлениями не задалбливаем;
     *   - ещё не получал напоминания по этому событию (`stage2_reminded_at IS NULL`).
     * Пустой ввод → пустой результат без запроса.
     */
    fun markStage2Reminded(eventId: UUID, userIds: List<UUID>): List<Long>

    /**
     * Сбрасывает отметки ручного напоминания всем участникам встречи (V86): напоминание — одно на
     * человека НА ЭТАП, а не на встречу. Зовётся при закрытии набора: после него у молчуна снова
     * есть о чём напоминать (подтвердить участие), и второе напоминание законно.
     */
    fun clearStage2Reminders(eventId: UUID): Int

    /**
     * Участники клуба, от которых ещё ждут ответа на Этапе 2 («Без ответа», решение PO 2026-08-16).
     * Строится от ЧЛЕНСТВА с доступом, а не от голосов: промолчавшего в `event_responses` нет.
     * Исключены явно отказавшиеся (`not_going`), уже взявшие место или сделавшие шаг Этапа 2 и
     * создатель встречи (V86: напоминать самому себе не о чем; в состав это его не ставит).
     * На наборе «Возможно» — тоже без ответа: место оно не даёт. Порядок:
     * going → maybe → промолчавшие. `stage1Vote = null` означает «не голосовал».
     */
    fun findStage2PendingMembers(eventId: UUID): List<EventResponderInfo>

    /**
     * ID пользователей, чья посещаемость на [eventId] равна `attended` (отмечено организатором).
     * Верифицированный набор участников для шаблона складчины split_bill.
     */
    fun findAttendedUserIds(eventId: UUID): List<UUID>

    /**
     * Статистика ОТКРЫТЫХ ВСТРЕЧ (participant_limit IS NULL, V62) пользователя в клубе для
     * карточки участника: пришёл X из Y подтверждённых с ВЫЯСНЕННОЙ явкой. В знаменатель входят
     * только строки attended/absent — неотмеченная явка (NULL) и незакрытый спор (disputed) процент
     * не портят, зеркаля confirmed_unresolved репутационной оси. Метрика вне репутации: считается
     * из сырых отметок явки, в ledger открытые встречи не пишут ничего.
     */
    fun countOpenEventAttendance(userId: UUID, clubId: UUID): OpenEventAttendance

    /**
     * «Статистика» профиля (мокап P3, PO 2026-07-21): сырые посещения пользователя по ВСЕМ клубам —
     * все attended-отметки (события с лимитом + открытые встречи) и отдельно открытые. Вне репутации,
     * питает блок «Всего посетил событий» в профиле. Индекс V61 (user, attended) делает COUNT дешёвым.
     */
    fun countUserVisits(userId: UUID): UserVisits

    /**
     * Имена (first_name) пришедших, для кого это событие — ПЕРВОЕ посещённое в клубе (других
     * attended-строк по событиям того же клуба нет). Питает «🎉 впервые на встрече клуба»
     * в посте-итоге живого закрепа. Порядок — по имени, чтобы текст был стабильным.
     */
    fun findFirstTimeAttendeeFirstNames(eventId: UUID, clubId: UUID): List<String>
}

/**
 * Счётчики открытых встреч пользователя в клубе: [attended] пришёл из [total] подтверждённых
 * броней с выясненной явкой (attended + absent). total = 0 → истории открытых встреч нет,
 * фронт кольцо не показывает.
 */
data class OpenEventAttendance(
    val attended: Int,
    val total: Int
)

/**
 * Сырые посещения по всем клубам для «Статистики» профиля: [totalEventsAttended] — все
 * attended-отметки, [openEventsAttended] — из них на открытых встречах (participant_limit IS NULL).
 */
data class UserVisits(
    val totalEventsAttended: Int,
    val openEventsAttended: Int
)

/**
 * Confirmed-бронь уходящего пользователя на активном событии: id события (source_id в леджере)
 * + его дата/время (occurred_at для no_show). Читается при выходе из клуба, чтобы штрафовать
 * брошенные обязательства. [isOpenEvent] = открытая встреча (V62): такая бронь НЕ штрафуется
 * (мест нет, отказ свободен), но событие всё равно участвует в каскаде/перерисовке закрепа.
 */
data class EventObligation(
    val eventId: UUID,
    val eventDatetime: OffsetDateTime,
    val isOpenEvent: Boolean = false
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
    val disputeNote: String?,
    // Username в Telegram (без @). NULL = не задан или скрыт настройками — личного чата
    // для такого участника не существует. Наружу уходит только менеджеру, см. VoteService.
    val telegramUsername: String?,
    // Когда участнику отправили ручное напоминание ответить (NULL = не напоминали). Наружу — только менеджеру.
    val stage2RemindedAt: OffsetDateTime? = null
)

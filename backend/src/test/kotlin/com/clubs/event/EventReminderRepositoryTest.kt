package com.clubs.event

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Интеграционный тест запросов напоминаний (EventReminderScheduler) на реальном Postgres:
 * какие события попадают под напоминание о подтверждении (A) / об отметке явки (B), dedup-флаги,
 * множество получателей среди неподтвердивших голосовавших и поиск telegram-id организатора.
 */
@SpringBootTest(
    properties = [
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=0",
        "telegram.bot-token=test-bot-token"
    ]
)
@Testcontainers
@ActiveProfiles("test")
class EventReminderRepositoryTest {

    companion object {
        // Общий Postgres-контейнер на все тесты класса
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("clubs_test").withUsername("test").withPassword("test")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var eventRepository: EventRepository
    @Autowired lateinit var eventResponseRepository: EventResponseRepository
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
    private var ownerTelegramId = 0L
    private lateinit var clubId: UUID
    private var telegramSeq = 7000L

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM membership_history")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")
        telegramSeq = 7000L

        ownerTelegramId = telegramSeq
        ownerId = newUser()
        clubId = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit, subscription_price, is_active)
            VALUES ('$clubId', '$ownerId', 'Club', 'desc', 'sport', 'open', 'Moscow', 20, 0, true)
            """.trimIndent()
        )
    }

    // --- A: напоминание о подтверждении ---

    @Test
    fun `confirm reminder finds stage_2 events starting within the window, not yet reminded`() {
        val now = OffsetDateTime.now()
        val due = insertEvent(now.plusHours(1), "stage_2")
        insertEvent(now.plusHours(3), "stage_2")                       // вне окна
        insertEvent(now.minusHours(1), "stage_2")                      // уже началось
        insertEvent(now.plusHours(1), "upcoming")                      // не stage_2
        insertEvent(now.plusHours(1), "stage_2", confirmReminderSent = true) // уже напомнили

        val result = eventRepository.findEventsNeedingConfirmReminder(now, now.plusHours(2)).map { it.id }

        assertEquals(listOf(due), result)
    }

    @Test
    fun `markConfirmReminderSent flips the flag (dedup)`() {
        val now = OffsetDateTime.now()
        val id = insertEvent(now.plusHours(1), "stage_2")

        eventRepository.markConfirmReminderSent(id)

        assertTrue(flag(id, "confirm_reminder_sent"))
        assertTrue(eventRepository.findEventsNeedingConfirmReminder(now, now.plusHours(2)).isEmpty())
    }

    @Test
    fun `unconfirmed voter recipients = going_maybe with null stage_2_vote only`() {
        val event = insertEvent(OffsetDateTime.now().plusHours(1), "stage_2")
        val goingNull = insertResponseUser(event, "going", null)
        val maybeNull = insertResponseUser(event, "maybe", null)
        insertResponseUser(event, "going", "confirmed")   // исключён
        insertResponseUser(event, "going", "declined")    // исключён
        insertResponseUser(event, "not_going", null)      // исключён

        val ids = eventResponseRepository.findUnconfirmedVoterTelegramIds(event).toSet()

        assertEquals(setOf(goingNull, maybeNull), ids)
    }

    // --- B: напоминание об отметке явки ---

    @Test
    fun `attendance reminder finds past unmarked non-cancelled events with a confirmed roster, not yet reminded`() {
        val now = OffsetDateTime.now()
        val due = insertEvent(now.minusHours(25), "completed")
        insertResponseUser(due, "going", "confirmed")                            // есть ростер для отметки
        insertEvent(now.minusHours(10), "completed").also { insertResponseUser(it, "going", "confirmed") } // ещё не прошло 24ч
        insertEvent(now.minusHours(25), "completed", attendanceMarked = true).also { insertResponseUser(it, "going", "confirmed") }       // уже отмечено
        insertEvent(now.minusHours(25), "completed", attendanceReminderSent = true).also { insertResponseUser(it, "going", "confirmed") } // уже напомнили
        insertEvent(now.minusHours(25), "cancelled").also { insertResponseUser(it, "going", "confirmed") } // отменено
        insertEvent(now.minusHours(25), "completed")                             // CC-2: нет confirmed-ростера → пропуск

        val result = eventRepository.findEventsNeedingAttendanceReminder(now.minusHours(24)).map { it.id }

        assertEquals(listOf(due), result)
    }

    @Test
    fun `attendance reminder excludes a neutrally-finalized event (F5-17)`() {
        val now = OffsetDateTime.now()
        // EXP-2: нейтральная финализация оставляет marked=false, finalized=true. Без guard'а по finalized
        // напоминание всё равно уходит → организатор жмёт «отметить» → markAttendance кидает finalized → 400.
        val neutrallyFinalized = insertEvent(now.minusHours(25), "completed", attendanceFinalized = true)
        insertResponseUser(neutrallyFinalized, "going", "confirmed")
        // регрессионная страховка: по всё ещё открытому неотмеченному событию напоминание сохраняется
        val stillDue = insertEvent(now.minusHours(25), "completed")
        insertResponseUser(stillDue, "going", "confirmed")

        val result = eventRepository.findEventsNeedingAttendanceReminder(now.minusHours(24)).map { it.id }

        assertEquals(listOf(stillDue), result)
    }

    @Test
    fun `markAttendanceReminderSent flips the flag (dedup)`() {
        val now = OffsetDateTime.now()
        val id = insertEvent(now.minusHours(25), "completed")
        insertResponseUser(id, "going", "confirmed") // подходит под условия — остановить его может только флаг

        eventRepository.markAttendanceReminderSent(id)

        assertTrue(flag(id, "attendance_reminder_sent"))
        assertTrue(eventRepository.findEventsNeedingAttendanceReminder(now.minusHours(24)).isEmpty())
    }

    @Test
    fun `findOrganizerTelegramId returns the club owner telegram id`() {
        val event = insertEvent(OffsetDateTime.now().minusHours(25), "completed")

        assertEquals(ownerTelegramId, eventRepository.findOrganizerTelegramId(event))
    }

    // --- Этап 2 открыт всем: приглашение (все кроме not_going) + поздняя строка ---

    @Test
    fun `stage2 invite = active members minus not_going, including non-voters`() {
        val event = insertEvent(OffsetDateTime.now().plusHours(1), "stage_2")
        val (goingU, goingTg) = insertMember()
        insertResponse(event, goingU, "going")
        val (maybeU, maybeTg) = insertMember()
        insertResponse(event, maybeU, "maybe")
        val (notGoingU, _) = insertMember()
        insertResponse(event, notGoingU, "not_going")        // не иду → DM не шлём
        val (_, silentTg) = insertMember()                   // не ответил → включён
        val (frozenU, _) = insertMember("frozen")            // нет доступа → исключён, даже если голосовал
        insertResponse(event, frozenU, "going")

        val ids = eventResponseRepository.findStage2InviteTelegramIds(event).toSet()

        assertEquals(setOf(goingTg, maybeTg, silentTg), ids)
    }

    @Test
    fun `responders include late joiners (null stage1) and fall back to stage_1 order when no stage_2 action`() {
        val event = insertEvent(OffsetDateTime.now().plusHours(1), "stage_2")
        // Ни у кого нет stage_2_timestamp → первичный ключ NULLS LAST равнозначен, сортировка падает
        // на вторичный stage_1_timestamp ASC (предварительный порядок «кто раньше откликнулся»).
        val (uConf, _) = insertMember(); insertResponseAt(event, uConf, "going", "confirmed", "2026-01-01T08:00:00Z")   // самый ранний
        val (uW2, _) = insertMember(); insertResponseAt(event, uW2, "going", "waitlisted", "2026-01-01T10:00:00Z")     // 3-й
        val (uW1, _) = insertMember(); insertResponseAt(event, uW1, "maybe", "waitlisted", "2026-01-01T09:00:00Z")     // 2-й (раньше going-uW2!)
        val (uLate, _) = insertMember(); insertResponseAt(event, uLate, null, "confirmed", "2026-01-01T12:00:00Z")     // поздний, stage1=NULL

        val order = eventResponseRepository.findRespondersWithUsers(event).map { it.userId }

        // строго по времени, НЕ сгруппировано по голосу:
        assertEquals(listOf(uConf, uW1, uW2, uLate), order)
        // поздний участник (stage_1_vote=NULL, final_status=confirmed) не потерян
        assertTrue(uLate in order)
    }

    @Test
    fun `waitlist queue is ordered by stage_2 timestamp, not stage_1`() {
        val event = insertEvent(OffsetDateTime.now().plusHours(1), "stage_2")
        // uEarly1 голосовал РАНЬШЕ на Этапе 1, но встал в очередь (подтвердил) ПОЗЖЕ на Этапе 2.
        val (uEarly1, _) = insertMember()
        insertResponseAt(event, uEarly1, "going", "waitlisted", ts = "2026-01-01T08:00:00Z", stage2Ts = "2026-01-02T20:00:00Z")
        // uLate1 голосовал ПОЗЖЕ на Этапе 1, но подтвердил РАНЬШЕ на Этапе 2 → он первый в очереди.
        val (uLate1, _) = insertMember()
        insertResponseAt(event, uLate1, "going", "waitlisted", ts = "2026-01-01T10:00:00Z", stage2Ts = "2026-01-02T18:00:00Z")

        val first = eventResponseRepository.findFirstWaitlisted(event)

        // Очередь по времени вставания в лист ожидания на Этапе 2 (18:00 < 20:00), НЕ по голосу Этапа 1.
        assertEquals(uLate1, first?.userId)
    }

    @Test
    fun `createLateStage2Entry inserts a row with null stage_1 vote and timestamp (queue ordered by stage_2)`() {
        val event = insertEvent(OffsetDateTime.now().plusHours(1), "stage_2")
        val (userId, _) = insertMember()

        val entry = eventResponseRepository.createLateStage2Entry(event, userId)

        assertEquals(userId, entry.userId)
        assertEquals(null, entry.stage1Vote)         // не голосовал на Этапе 1
        assertEquals(null, entry.stage1Timestamp)    // метки Этапа 1 нет — позицию в очереди задаёт stage_2_timestamp
        assertEquals(null, entry.stage2Vote)         // подтверждение проставит confirmParticipation отдельным шагом
    }

    // --- хелперы ---

    /** Пользователь + членство в клубе с заданным статусом; возвращает (userId, telegramId). */
    private fun insertMember(status: String = "active"): Pair<UUID, Long> {
        val tgId = telegramSeq
        val userId = newUser()
        dsl.execute(
            "INSERT INTO memberships (user_id, club_id, status) VALUES ('$userId', '$clubId', '$status'::membership_status)"
        )
        return userId to tgId
    }

    /** Ответ на событие для уже существующего пользователя (stage1/stage2 опциональны), метка = NOW. */
    private fun insertResponse(eventId: UUID, userId: UUID, stage1: String?, stage2: String? = null) =
        insertResponseAt(eventId, userId, stage1, stage2, "NOW()")

    /**
     * Как insertResponse, но с явными метками времени (передавай ISO-строку в кавычках или NOW()).
     * [ts] — stage_1_timestamp; [stage2Ts] — stage_2_timestamp (по умолчанию NULL — действия Этапа 2 нет).
     */
    private fun insertResponseAt(
        eventId: UUID, userId: UUID, stage1: String?, stage2: String?, ts: String, stage2Ts: String? = null
    ) {
        val s1 = stage1?.let { "'$it'::stage_1_vote" } ?: "NULL"
        val s2 = stage2?.let { "'$it'::stage_2_vote" } ?: "NULL"
        val fs = stage2?.let { "'$it'::final_status" } ?: "NULL"
        val tsExpr = if (ts == "NOW()") "NOW()" else "'$ts'::timestamptz"
        val s2tsExpr = when (stage2Ts) {
            null -> "NULL"
            "NOW()" -> "NOW()"
            else -> "'$stage2Ts'::timestamptz"
        }
        dsl.execute(
            """
            INSERT INTO event_responses (id, event_id, user_id, stage_1_vote, stage_1_timestamp, stage_2_vote, stage_2_timestamp, final_status)
            VALUES ('${UUID.randomUUID()}', '$eventId', '$userId', $s1, $tsExpr, $s2, $s2tsExpr, $fs)
            """.trimIndent()
        )
    }

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$id', ${telegramSeq++}, 'U')")
        return id
    }

    private fun insertEvent(
        eventDatetime: OffsetDateTime,
        status: String,
        confirmReminderSent: Boolean = false,
        attendanceMarked: Boolean = false,
        attendanceReminderSent: Boolean = false,
        attendanceFinalized: Boolean = false
    ): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime,
                participant_limit, voting_opens_days_before, status, stage_2_triggered,
                attendance_marked, confirm_reminder_sent, attendance_reminder_sent, attendance_finalized)
            VALUES ('$id', '$clubId', '$ownerId', 'Event', 'Place', '$eventDatetime', 10, 14,
                '$status'::event_status, true, $attendanceMarked, $confirmReminderSent, $attendanceReminderSent, $attendanceFinalized)
            """.trimIndent()
        )
        return id
    }

    /** Вставляет свежего пользователя + его ответ; возвращает telegram id пользователя. */
    private fun insertResponseUser(eventId: UUID, stage1: String, stage2: String?): Long {
        val tgId = telegramSeq
        val userId = newUser()
        val s2 = stage2?.let { "'$it'::stage_2_vote" } ?: "NULL"
        val fs = stage2?.let { "'$it'::final_status" } ?: "NULL"
        dsl.execute(
            """
            INSERT INTO event_responses (id, event_id, user_id, stage_1_vote, stage_1_timestamp, stage_2_vote, final_status)
            VALUES ('${UUID.randomUUID()}', '$eventId', '$userId', '$stage1'::stage_1_vote, NOW(), $s2, $fs)
            """.trimIndent()
        )
        return tgId
    }

    private fun flag(id: UUID, col: String): Boolean =
        dsl.fetchOne("SELECT $col FROM events WHERE id = ?", id)!!.get(0, Boolean::class.java)
}

package com.clubs.clubquality

import com.clubs.common.exception.NotFoundException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Интеграционные тесты фактов L1 качества клуба на реальном Postgres. Покрывают правила
 * окна/порогов каждого факта + пути пустого клуба и отсутствующего клуба (404). Критерии приёмки:
 * docs/modules/club-quality.md §7.
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
class ClubQualityIntegrationTest {

    companion object {
        // Testcontainers-контейнер Postgres 16 — реальная БД для интеграционных тестов
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("clubs_test")
            .withUsername("test")
            .withPassword("test")

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var clubQualityService: ClubQualityService
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
    private lateinit var clubId: UUID
    private var telegramSeq = 9000L

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM event_responses")
        dsl.execute("DELETE FROM events")
        dsl.execute("DELETE FROM skladchina_participants")
        dsl.execute("DELETE FROM skladchinas")
        dsl.execute("DELETE FROM membership_history")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")
        telegramSeq = 9000L

        ownerId = newUser()
        clubId = insertClub(createdAt = OffsetDateTime.now().minusMonths(14).minusDays(5))
    }

    @Test
    fun `empty club yields zero facts and a correct age`() {
        val facts = clubQualityService.getClubFacts(clubId)

        assertEquals(0.0, facts.meetingsPerMonth, 0.001)
        assertEquals(0, facts.avgAttendance)
        assertEquals(0, facts.coreSize)
        assertEquals(14, facts.ageMonths)
        assertEquals(0, facts.totalMeetings)
        assertEquals(0, facts.successfulSkladchinas)
    }

    @Test
    fun `missing club throws NotFoundException (404, not 500)`() {
        assertFailsWith<NotFoundException> { clubQualityService.getClubFacts(UUID.randomUUID()) }
    }

    @Test
    fun `meetingsPerMonth counts only held events in the 90-day window`() {
        insertEvent(daysFromNow(-2), "completed")   // состоялось, в окне
        insertEvent(daysFromNow(-30), "completed")  // состоялось, в окне
        insertEvent(daysFromNow(-60), "stage_2")    // состоялось, в окне (не отменено)
        insertEvent(daysFromNow(-89), "completed")  // состоялось, в окне
        insertEvent(daysFromNow(3), "upcoming")     // будущее → исключено
        insertEvent(daysFromNow(-10), "cancelled")  // отменено → исключено
        insertEvent(daysFromNow(-100), "completed") // старше 90 дней → исключено

        // 4 состоявшихся в окне ÷ 3 = 1.333 → 1.3
        assertEquals(1.3, clubQualityService.getClubFacts(clubId).meetingsPerMonth, 0.001)
    }

    @Test
    fun `avgAttendance averages distinct attendees over finalized meetings only`() {
        val a = insertEvent(daysFromNow(-5), "completed", finalized = true)
        insertResponse(a, attendance = "attended")
        insertResponse(a, attendance = "attended")
        insertResponse(a, attendance = "attended")
        insertResponse(a, attendance = "absent")

        val b = insertEvent(daysFromNow(-6), "completed", finalized = true)
        insertResponse(b, attendance = "attended")

        // Нефинализированная встреча с отметками посещаемости вообще не должна влиять на среднее.
        val c = insertEvent(daysFromNow(-7), "completed", finalized = false)
        insertResponse(c, attendance = "attended")
        insertResponse(c, attendance = "attended")

        // (3 + 1) пришедших ÷ 2 финализированные встречи = 2
        assertEquals(2, clubQualityService.getClubFacts(clubId).avgAttendance)
    }

    @Test
    fun `a finalized meeting nobody attended lowers the average (counts in denominator)`() {
        val a = insertEvent(daysFromNow(-5), "completed", finalized = true)
        repeat(4) { insertResponse(a, attendance = "attended") }
        insertEvent(daysFromNow(-6), "completed", finalized = true) // финализирована, нулевая посещаемость

        // 4 пришедших ÷ 2 финализированные встречи = 2 (не 4)
        assertEquals(2, clubQualityService.getClubFacts(clubId).avgAttendance)
    }

    @Test
    fun `coreSize counts distinct users with at least 3 attended events`() {
        val events = (1..4).map { insertEvent(daysFromNow(-it.toLong()), "completed", finalized = true) }
        val core1 = newUser()
        val core2 = newUser()
        val casual = newUser()
        listOf(core1, core2, casual).forEach { insertMembership(it, "active") } // текущие участники

        events.take(3).forEach { insertResponse(it, attendance = "attended", userId = core1) } // 3 → ядро
        events.forEach { insertResponse(it, attendance = "attended", userId = core2) }          // 4 → ядро
        events.take(2).forEach { insertResponse(it, attendance = "attended", userId = casual) } // 2 → не ядро

        assertEquals(2, clubQualityService.getClubFacts(clubId).coreSize)
    }

    @Test
    fun `coreSize ignores attended responses on cancelled events`() {
        // У отменённого события могут остаться строки attended (каскад удаления клуба отменяет
        // stage_2-событие с уже отмеченной посещаемостью) — они не должны раздувать ядро.
        val cancelled = (1..3).map { insertEvent(daysFromNow(-it.toLong()), "cancelled") }
        val ghost = newUser()
        insertMembership(ghost, "active") // текущий участник — изолирует правило отменённых событий
        cancelled.forEach { insertResponse(it, attendance = "attended", userId = ghost) } // 3 посещения, все на отменённых

        assertEquals(0, clubQualityService.getClubFacts(clubId).coreSize)
    }

    @Test
    fun `coreSize excludes the organizer even when they attend their own events`() {
        val events = (1..3).map { insertEvent(daysFromNow(-it.toLong()), "completed", finalized = true) }
        // Организатор отмечает себе посещение на всех трёх — в «основу клуба» засчитываться НЕ должен.
        events.forEach { insertResponse(it, attendance = "attended", userId = ownerId) }
        val member = newUser()
        insertMembership(member, "active")
        events.forEach { insertResponse(it, attendance = "attended", userId = member) } // настоящий участник → ядро

        assertEquals(1, clubQualityService.getClubFacts(clubId).coreSize)
    }

    @Test
    fun `coreSize drops a member who left or was removed (membership cancelled)`() {
        // Пункт 5: «основа клуба» должна отражать ТЕКУЩИХ участников — завсегдатай, который вышел
        // или был кикнут (status cancelled), перестаёт учитываться, хотя его посещения остаются в истории.
        val events = (1..3).map { insertEvent(daysFromNow(-it.toLong()), "completed", finalized = true) }
        val stayer = newUser()
        val leaver = newUser()
        insertMembership(stayer, "active")
        insertMembership(leaver, "cancelled")
        events.forEach { insertResponse(it, attendance = "attended", userId = stayer) } // 3 → ядро
        events.forEach { insertResponse(it, attendance = "attended", userId = leaver) } // 3 посещения, но вышел

        assertEquals(1, clubQualityService.getClubFacts(clubId).coreSize)
    }

    @Test
    fun `coreSize still counts a frozen member (de-Stars dues pause is not a departure)`() {
        // Frozen-участник — посреди цикла взносов, а не ушёл: ядро не должно мигать каждый раз,
        // когда у платящего участника ненадолго истекает окно.
        val events = (1..3).map { insertEvent(daysFromNow(-it.toLong()), "completed", finalized = true) }
        val frozen = newUser()
        insertMembership(frozen, "frozen")
        events.forEach { insertResponse(it, attendance = "attended", userId = frozen) }

        assertEquals(1, clubQualityService.getClubFacts(clubId).coreSize)
    }

    @Test
    fun `totalMeetings counts all-time held events, excluding future and cancelled`() {
        insertEvent(daysFromNow(-200), "completed") // старше 90-дневного окна, но метрика all-time → считается
        insertEvent(daysFromNow(-2), "completed")   // состоялось → считается
        insertEvent(daysFromNow(3), "upcoming")     // будущее → исключено
        insertEvent(daysFromNow(-5), "cancelled")   // отменено → исключено

        assertEquals(2, clubQualityService.getClubFacts(clubId).totalMeetings)
    }

    @Test
    fun `successfulSkladchinas counts only closed_success`() {
        insertSkladchina("closed_success")
        insertSkladchina("closed_success")
        insertSkladchina("active")
        insertSkladchina("closed_failed")
        insertSkladchina("cancelled")

        assertEquals(2, clubQualityService.getClubFacts(clubId).successfulSkladchinas)
    }

    // ---- батч (Discovery-карточка) ----

    @Test
    fun `card facts batch returns age in days and engagement`() {
        // 4 живых участника (знаменатель); expired-участник исключается.
        val m1 = newUser(); val m2 = newUser()
        listOf(m1, m2, newUser(), newUser()).forEach { insertMembership(it, "active") }
        insertMembership(newUser(), "expired")

        // 2 разных участника откликаются на недавние события → числитель вовлечённости = 2.
        val e1 = insertEvent(daysFromNow(-3), "completed")
        val e2 = insertEvent(daysFromNow(-10), "completed")
        insertResponse(e1, attendance = null, userId = m1)
        insertResponse(e1, attendance = null, userId = m2)
        insertResponse(e2, attendance = null, userId = m1) // снова m1 → уникальные откликнувшиеся = {m1, m2}

        val facts = clubQualityService.getClubCardFacts(listOf(clubId)).single()
        assertEquals(clubId, facts.clubId)
        assertEquals(50, facts.engagementPercent) // 2 уникальных откликнувшихся ÷ 4 живых участника
        // Клуб создан ~14 месяцев назад в setUp → возраст в ДНЯХ (не в месяцах и не ноль).
        assertTrue(facts.ageDays >= 420, "ageDays should reflect days since creation, was ${facts.ageDays}")
    }

    @Test
    fun `card facts batch skips ids with no club row`() {
        val facts = clubQualityService.getClubCardFacts(listOf(clubId, UUID.randomUUID()))
        assertEquals(setOf(clubId), facts.map { it.clubId }.toSet())
    }

    @Test
    fun `card facts engagement is zero when club has no alive members`() {
        val e = insertEvent(daysFromNow(-3), "completed")
        insertResponse(e, attendance = null) // откликнувшийся есть, но живых участников ноль
        assertEquals(0, clubQualityService.getClubCardFacts(listOf(clubId)).single().engagementPercent)
    }

    @Test
    fun `card facts engagement clamps at 100 percent`() {
        insertMembership(newUser(), "active") // 1 живой участник
        val e = insertEvent(daysFromNow(-3), "completed")
        repeat(3) { insertResponse(e, attendance = null) } // 3 уникальных откликнувшихся > 1 участника
        assertEquals(100, clubQualityService.getClubCardFacts(listOf(clubId)).single().engagementPercent)
    }

    @Test
    fun `card facts batch returns empty for empty input`() {
        assertEquals(emptyList<ClubCardFactsDto>(), clubQualityService.getClubCardFacts(emptyList()))
    }

    // ---- хелперы ----

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$id', ${telegramSeq++}, 'U')")
        return id
    }

    private fun insertClub(createdAt: OffsetDateTime): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city,
                               member_limit, subscription_price, is_active, created_at)
            VALUES ('$id', '$ownerId', 'Club', 'desc', 'sport', 'open', 'Moscow', 20, 0, true, '$createdAt')
            """.trimIndent()
        )
        return id
    }

    private fun insertEvent(eventDatetime: OffsetDateTime, status: String, finalized: Boolean = false): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO events (id, club_id, created_by, title, location_text, event_datetime,
                                participant_limit, voting_opens_days_before, status,
                                stage_2_triggered, attendance_marked, attendance_finalized)
            VALUES ('$id', '$clubId', '$ownerId', 'Event', 'Place', '$eventDatetime', 10, 14,
                    '$status'::event_status, true, $finalized, $finalized)
            """.trimIndent()
        )
        return id
    }

    private fun insertResponse(eventId: UUID, attendance: String?, userId: UUID = newUser()): UUID {
        val id = UUID.randomUUID()
        val att = attendance?.let { "'$it'::attendance_status" } ?: "NULL"
        dsl.execute(
            """
            INSERT INTO event_responses (id, event_id, user_id, stage_1_vote, stage_1_timestamp,
                                         stage_2_vote, final_status, attendance)
            VALUES ('$id', '$eventId', '$userId', 'going'::stage_1_vote, NOW(),
                    'confirmed'::stage_2_vote, 'confirmed'::final_status, $att)
            """.trimIndent()
        )
        return id
    }

    private fun insertMembership(userId: UUID, status: String): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO memberships (id, user_id, club_id, status, role)
            VALUES ('$id', '$userId', '$clubId', '$status'::membership_status, 'member'::membership_role)
            """.trimIndent()
        )
        return id
    }

    private fun insertSkladchina(status: String): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO skladchinas (id, club_id, creator_id, title, payment_mode, payment_link, deadline, status)
            VALUES ('$id', '$clubId', '$ownerId', 'Сбор', 'voluntary'::skladchina_mode, 'http://pay',
                    NOW() + INTERVAL '7 days', '$status'::skladchina_status)
            """.trimIndent()
        )
        return id
    }

    private fun daysFromNow(days: Long): OffsetDateTime = OffsetDateTime.now().plusDays(days)
}

package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipEvent
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.MEMBERSHIP_HISTORY
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

/**
 * Verifies the append-only membership_history log captures the right lifecycle event at each
 * transition point in [JooqMembershipRepository], against a real Postgres. Spec: V31.
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
class MembershipHistoryIntegrationTest {

    companion object {
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

    @Autowired lateinit var membershipRepository: MembershipRepository
    @Autowired lateinit var dsl: DSLContext

    private lateinit var ownerId: UUID
    private lateinit var memberId: UUID
    private lateinit var clubId: UUID
    private var telegramSeq = 7000L

    @BeforeEach
    fun setUp() {
        dsl.execute("DELETE FROM membership_history")
        dsl.execute("DELETE FROM memberships")
        dsl.execute("DELETE FROM clubs")
        dsl.execute("DELETE FROM users")
        telegramSeq = 7000L
        ownerId = newUser()
        memberId = newUser()
        clubId = insertClub(ownerId)
    }

    @Test
    fun `create records joined`() {
        membershipRepository.create(memberId, clubId)
        assertEvents(memberId, MembershipEvent.joined)
    }

    @Test
    fun `createOrganizer records nothing (owner lifecycle is not tracked)`() {
        membershipRepository.createOrganizer(ownerId, clubId)
        assertEvents(ownerId) // member-only log: the organizer's own membership is not recorded
    }

    @Test
    fun `activateSubscription records joined`() {
        membershipRepository.activateSubscription(memberId, clubId, OffsetDateTime.now().plusDays(30))
        assertEvents(memberId, MembershipEvent.joined)
    }

    @Test
    fun `cancel records left`() {
        val m = membershipRepository.create(memberId, clubId)
        membershipRepository.cancel(m.id)
        assertEvents(memberId, MembershipEvent.joined, MembershipEvent.left)
    }

    @Test
    fun `reactivateFree records rejoined`() {
        val m = membershipRepository.create(memberId, clubId)
        membershipRepository.cancel(m.id)
        membershipRepository.reactivateFree(m.id)
        assertEvents(memberId, MembershipEvent.joined, MembershipEvent.left, MembershipEvent.rejoined)
    }

    @Test
    fun `renewSubscription from active does NOT record (renewal, not churn)`() {
        val id = membershipRepository.activateSubscription(memberId, clubId, OffsetDateTime.now().plusDays(30))
        membershipRepository.renewSubscription(id, OffsetDateTime.now().plusDays(60))
        assertEvents(memberId, MembershipEvent.joined) // only the original join
    }

    @Test
    fun `renewSubscription from a dead membership records rejoined`() {
        val id = membershipRepository.activateSubscription(memberId, clubId, OffsetDateTime.now().plusDays(30))
        // Simulate a lapsed membership directly (the flip itself is not a logged transition here).
        dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.expired)
            .where(MEMBERSHIPS.ID.eq(id))
            .execute()
        membershipRepository.renewSubscription(id, OffsetDateTime.now().plusDays(30))
        assertEvents(memberId, MembershipEvent.joined, MembershipEvent.rejoined)
    }

    @Test
    fun `expireOverdueAccess flips overdue active to frozen without a churn event`() {
        val now = OffsetDateTime.now()
        membershipRepository.activateSubscription(memberId, clubId, now.minusDays(1)) // joined, already past expiry
        val count = membershipRepository.expireOverdueAccess(now) // active → frozen (access suspension)
        assertEquals(1, count)
        // De-Stars: a freeze (access suspension) is NOT churn, so only the original join is logged.
        assertEvents(memberId, MembershipEvent.joined)
    }

    // ---- helpers ----

    /** Asserts the multiset of logged events for (userId, clubId) equals [expected] (order-independent). */
    private fun assertEvents(userId: UUID, vararg expected: MembershipEvent) {
        val actual = dsl.select(MEMBERSHIP_HISTORY.EVENT)
            .from(MEMBERSHIP_HISTORY)
            .where(MEMBERSHIP_HISTORY.USER_ID.eq(userId).and(MEMBERSHIP_HISTORY.CLUB_ID.eq(clubId)))
            .fetch(MEMBERSHIP_HISTORY.EVENT)
            .filterNotNull()
        assertEquals(
            expected.sortedBy { it.ordinal },
            actual.sortedBy { it.ordinal },
            "membership_history events for the user"
        )
    }

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        dsl.execute("INSERT INTO users (id, telegram_id, first_name) VALUES ('$id', ${telegramSeq++}, 'U')")
        return id
    }

    private fun insertClub(owner: UUID): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            """
            INSERT INTO clubs (id, owner_id, name, description, category, access_type, city, member_limit)
            VALUES ('$id', '$owner', 'Club', 'desc', 'sport', 'open', 'Moscow', 20)
            """.trimIndent()
        )
        return id
    }
}

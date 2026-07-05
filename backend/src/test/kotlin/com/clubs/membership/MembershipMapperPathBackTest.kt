package com.clubs.membership

import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.reputation.ClubTrust
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Гейты «пути назад» на границе DTO (reputation-path-back.md AC-2): проекция отдаётся только при
 * видимой просадке (trust показан, < 70) в АКТИВНОМ клубе; сборы — вместе с остальными метриками.
 */
class MembershipMapperPathBackTest {

    private val mapper = MembershipMapper()
    private val clubId = UUID.randomUUID()
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-07-05T12:00:00Z")

    private fun info(outcomeCount: Int = 5, active: Boolean = true) = UserClubReputationInfo(
        clubId = clubId,
        clubName = "Партия",
        clubAvatarUrl = null,
        category = ClubCategory.board_games,
        role = MembershipRole.member,
        joinedAt = now.minusDays(30),
        promiseFulfillmentPct = BigDecimal("71"),
        totalConfirmations = 9,
        totalAttendances = 7,
        spontaneityCount = 0,
        outcomeCount = outcomeCount,
        active = active
    )

    private fun clubTrust(trust: Int) = ClubTrust(
        clubId = clubId,
        trust = trust,
        outcomeCount = 5,
        lastOccurredAt = now.minusDays(3),
        projectedNext1 = 66,
        projectedNext2 = 70,
        meetingsToReliable = 2,
        skladchinaPaid = 3,
        skladchinaTotal = 3
    )

    @Test
    fun `dip in an active club exposes the full path back`() {
        val dto = mapper.toUserClubReputationDto(info(), clubTrust(trust = 60))
        assertEquals(60, dto.trust)
        assertEquals(66, dto.projectedNext1)
        assertEquals(70, dto.projectedNext2)
        assertEquals(2, dto.meetingsToReliable)
        assertEquals(3, dto.skladchinaPaid)
        assertEquals(3, dto.skladchinaTotal)
    }

    @Test
    fun `healthy trust hides the path back but keeps skladchina counters`() {
        val dto = mapper.toUserClubReputationDto(info(), clubTrust(trust = 82))
        assertEquals(82, dto.trust)
        assertNull(dto.projectedNext1)
        assertNull(dto.projectedNext2)
        assertNull(dto.meetingsToReliable)
        assertEquals(3, dto.skladchinaPaid)
    }

    @Test
    fun `inactive (history) club never shows the path back - there is nowhere to return`() {
        val dto = mapper.toUserClubReputationDto(info(active = false), clubTrust(trust = 60))
        assertEquals(60, dto.trust)
        assertNull(dto.projectedNext1)
        assertNull(dto.meetingsToReliable)
    }

    @Test
    fun `below the newcomer display gate everything reputation-shaped is null`() {
        val dto = mapper.toUserClubReputationDto(info(outcomeCount = 2), clubTrust(trust = 40))
        assertNull(dto.trust)
        assertNull(dto.projectedNext1)
        assertNull(dto.projectedNext2)
        assertNull(dto.meetingsToReliable)
        assertNull(dto.skladchinaPaid)
        assertNull(dto.skladchinaTotal)
    }
}

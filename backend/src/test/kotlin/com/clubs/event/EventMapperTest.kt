package com.clubs.event

import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Unit-тесты чистого маппинга MyFeedItem → MyEventListItemDto: проброс isHistory и инвариант
 * «история никогда не actionRequired». БД/Spring не нужны — маппер чист.
 */
class EventMapperTest {

    // 240 мин — дефолт events.stage2-decline-cutoff-minutes; в этих тестах поле не проверяется.
    private val mapper = EventMapper(declineCutoffMinutes = 240)
    private val now = OffsetDateTime.parse("2026-07-20T12:00:00Z")

    private fun event(
        status: EventStatus,
        eventDatetime: OffsetDateTime,
        votingOpensDaysBefore: Int = 14
    ) = Event(
        id = UUID.randomUUID(),
        clubId = UUID.randomUUID(),
        createdBy = UUID.randomUUID(),
        title = "T",
        description = null,
        locationText = "Place",
        eventDatetime = eventDatetime,
        participantLimit = 10,
        votingOpensDaysBefore = votingOpensDaysBefore,
        status = status,
        stage2Triggered = false,
        attendanceMarked = false,
        attendanceFinalized = false,
        photoUrl = null,
        createdAt = null,
        updatedAt = null
    )

    private fun feedItem(
        event: Event,
        myVote: Stage_1Vote? = null,
        myFinalStatus: FinalStatus? = null,
        isHistory: Boolean = false
    ) = MyFeedItem(
        event = event,
        clubName = "Club",
        clubAvatarUrl = null,
        myVote = myVote,
        myFinalStatus = myFinalStatus,
        goingCount = 0,
        confirmedCount = 0,
        isHistory = isHistory
    )

    @Test
    fun `isHistory is passed through to the dto`() {
        val historyItem = feedItem(event(EventStatus.completed, now.minusDays(3)), isHistory = true)
        val upcomingItem = feedItem(event(EventStatus.upcoming, now.plusDays(3)), isHistory = false)

        assertThat(mapper.toMyFeedItemDto(historyItem, now).isHistory).isTrue()
        assertThat(mapper.toMyFeedItemDto(upcomingItem, now).isHistory).isFalse()
    }

    @Test
    fun `history item is never actionRequired even in a stage_2 shape that would otherwise trigger`() {
        // Лаг крона (AC-H14): статус ещё stage_2, голос going, финального статуса нет — обычно это
        // actionRequired=true. Но строка помечена isHistory ⇒ действий по прошедшему событию нет.
        val laggedHistory = feedItem(
            event(EventStatus.stage_2, now.minusHours(2)),
            myVote = Stage_1Vote.going,
            myFinalStatus = null,
            isHistory = true
        )

        val dto = mapper.toMyFeedItemDto(laggedHistory, now)

        assertThat(dto.isHistory).isTrue()
        assertThat(dto.actionRequired).isFalse()
    }

    @Test
    fun `upcoming with open voting and no vote is actionRequired`() {
        val item = feedItem(
            event(EventStatus.upcoming, now.plusDays(3), votingOpensDaysBefore = 14),
            myVote = null,
            isHistory = false
        )

        assertThat(mapper.toMyFeedItemDto(item, now).actionRequired).isTrue()
    }

    @Test
    fun `stage_2 upcoming with going vote and no final status is actionRequired`() {
        val item = feedItem(
            event(EventStatus.stage_2, now.plusDays(1)),
            myVote = Stage_1Vote.going,
            myFinalStatus = null,
            isHistory = false
        )

        assertThat(mapper.toMyFeedItemDto(item, now).actionRequired).isTrue()
    }
}

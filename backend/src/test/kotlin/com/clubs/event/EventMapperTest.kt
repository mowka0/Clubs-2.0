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
    private val mapper = EventMapper(declineCutoffMinutes = 240, stage2TriggerMinutesBefore = 1080)
    private val now = OffsetDateTime.parse("2026-07-20T12:00:00Z")

    private fun event(
        status: EventStatus,
        eventDatetime: OffsetDateTime,
        votingOpensDaysBefore: Int = 14,
        // null = открытая встреча (V62) — кейс дедлайна отказа передаёт null явно.
        participantLimit: Int? = 10,
        // Свой интервал Этапа 2 (V67); null = событие следует глобальному дефолту.
        stage2LeadMinutes: Int? = null
    ) = Event(
        id = UUID.randomUUID(),
        clubId = UUID.randomUUID(),
        createdBy = UUID.randomUUID(),
        title = "T",
        description = null,
        locationText = "Place",
        eventDatetime = eventDatetime,
        participantLimit = participantLimit,
        votingOpensDaysBefore = votingOpensDaysBefore,
        stage2LeadMinutes = stage2LeadMinutes,
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

    // Встреча с порогом набора (V83): состав закрывается сам, подтверждать нечего — бейдж
    // «требуется действие» на закрытом составе врал бы. Действие у формата бывает только на
    // наборе (status=upcoming, голос не отдан).
    @Test
    fun `roster event in stage_2 is never actionRequired`() {
        val item = feedItem(
            event(EventStatus.stage_2, now.plusDays(1)),
            myVote = Stage_1Vote.going,
            myFinalStatus = null,
            isHistory = false
        )

        assertThat(mapper.toMyFeedItemDto(item, now).actionRequired).isFalse()
    }

    // Этап 2 требует действия от КАЖДОГО без решения на самом Этапе 2 (PO 2026-07-23):
    // голос Этапа 1 не финален (даже «Не пойду» может передумать), у срочной (V69) его нет вовсе.
    @Test
    fun `stage_2 with no vote (urgent case) and no final status is actionRequired`() {
        val item = feedItem(
            event(EventStatus.stage_2, now.plusHours(5)).copy(isUrgent = true),
            myVote = null,
            myFinalStatus = null,
            isHistory = false
        )

        assertThat(mapper.toMyFeedItemDto(item, now).actionRequired).isTrue()
    }

    @Test
    fun `stage_2 with a not_going stage-1 vote is still actionRequired (urgent event)`() {
        val item = feedItem(
            event(EventStatus.stage_2, now.plusHours(5)).copy(isUrgent = true),
            myVote = Stage_1Vote.not_going,
            myFinalStatus = null,
            isHistory = false
        )

        assertThat(mapper.toMyFeedItemDto(item, now).actionRequired).isTrue()
    }

    @Test
    fun `stage_2 stops being actionRequired once the user decided on stage 2`() {
        val item = feedItem(
            event(EventStatus.stage_2, now.plusHours(5)).copy(isUrgent = true),
            myVote = null,
            myFinalStatus = FinalStatus.confirmed,
            isHistory = false
        )

        assertThat(mapper.toMyFeedItemDto(item, now).actionRequired).isFalse()
    }

    // Эффективный интервал Этапа 2 (V67): свой → свой, NULL → глобальный дефолт мапера (1080),
    // открытая встреча → null (интервал не настраивается) — фронт порог не хардкодит.
    @Test
    fun `stage2LeadMinutes is own value, config default for NULL and null for an open event`() {
        val start = now.plusDays(2)

        val custom = mapper.toDetailDto(event(EventStatus.upcoming, start, stage2LeadMinutes = 720), 0, 0, 0, 0)
        assertThat(custom.stage2LeadMinutes).isEqualTo(720)

        val legacy = mapper.toDetailDto(event(EventStatus.upcoming, start), 0, 0, 0, 0)
        assertThat(legacy.stage2LeadMinutes).isEqualTo(1080)

        val open = mapper.toDetailDto(event(EventStatus.upcoming, start, participantLimit = null), 0, 0, 0, 0)
        assertThat(open.stage2LeadMinutes).isNull()
    }

    // Цена отказа приходит с бэка готовой (V83, ReputationPolicy + RosterPolicy): фронт не выводит
    // её из даты, формата и размера очереди — копия этой логики на клиенте разъехалась бы.
    @Test
    fun `declineCostPoints — 100 на закрытом составе без замены и 0, пока набор идёт`() {
        val start = now.plusDays(2)

        // now передаём явно: у мапера дефолт — реальное «сейчас», и фиксированная дата фикстуры
        // без него оказалась бы в прошлом (тогда порог отказа считался бы пройденным).
        val closed = mapper.toDetailDto(event(EventStatus.stage_2, start), 0, 0, 0, 0, now = now)
        assertThat(closed.declineCostPoints).isEqualTo(100)

        val collecting = mapper.toDetailDto(event(EventStatus.upcoming, start), 0, 0, 0, 0, now = now)
        assertThat(collecting.declineCostPoints).isEqualTo(0)
    }

    @Test
    fun `declineCostPoints — 0 при живой очереди и 150 внутри порога отказа`() {
        val start = now.plusDays(2)

        val withQueue = mapper.toDetailDto(
            event(EventStatus.stage_2, start), 0, 0, 0, 0, waitlistedCount = 2, now = now
        )
        assertThat(withQueue.declineCostPoints).isEqualTo(0)

        // Порог отказа мапера — 240 мин: встреча через час уже внутри него, замены нет.
        val lastHour = mapper.toDetailDto(
            event(EventStatus.stage_2, now.plusHours(1)), 0, 0, 0, 0, now = now
        )
        assertThat(lastHour.declineCostPoints).isEqualTo(150)
    }
}

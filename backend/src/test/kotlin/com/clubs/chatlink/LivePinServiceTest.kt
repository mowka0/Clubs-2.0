package com.clubs.chatlink

import com.clubs.bot.ChatTelegramGateway
import com.clubs.event.Event
import com.clubs.event.EventRepository
import com.clubs.event.EventResponseRepository
import com.clubs.generated.jooq.enums.EventStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

// Событие для живого закрепа: минимально заполненное, будущее по умолчанию.
private fun livePinEvent(
    id: UUID = UUID.randomUUID(),
    clubId: UUID = UUID.randomUUID(),
    eventDatetime: OffsetDateTime = OffsetDateTime.now().plusDays(2),
    status: EventStatus = EventStatus.upcoming,
    stage2Triggered: Boolean = false
): Event = Event(
    id = id,
    clubId = clubId,
    createdBy = UUID.randomUUID(),
    title = "Поход в баню",
    description = null,
    locationText = "Сандуны",
    eventDatetime = eventDatetime,
    participantLimit = 15,
    votingOpensDaysBefore = 14,
    status = status,
    stage2Triggered = stage2Triggered,
    attendanceMarked = false,
    attendanceFinalized = false,
    photoUrl = null,
    createdAt = null,
    updatedAt = null
)

class LivePinServiceTest {

    private lateinit var chatLinkRepository: ChatLinkRepository
    private lateinit var pinRepository: EventChatPinRepository
    private lateinit var eventRepository: EventRepository
    private lateinit var eventResponseRepository: EventResponseRepository
    private lateinit var gateway: ChatTelegramGateway
    private lateinit var service: LivePinService

    private val clubId = UUID.randomUUID()
    private val chatId = -100123L
    private val link = chatLinkFixture(clubId = clubId, chatId = chatId, livePinEnabled = true)

    @BeforeEach
    fun setUp() {
        chatLinkRepository = mockk(relaxed = true)
        pinRepository = mockk(relaxed = true)
        eventRepository = mockk(relaxed = true)
        eventResponseRepository = mockk(relaxed = true)
        gateway = mockk(relaxed = true)
        service = LivePinService(
            chatLinkRepository, pinRepository, eventRepository, eventResponseRepository,
            LivePinRenderer(botUsername = "clubs_test_bot"), gateway
        )
        every { chatLinkRepository.findByClubId(clubId) } returns link
        every { eventResponseRepository.countByVote(any()) } returns mapOf("going" to 3, "maybe" to 1, "notGoing" to 0)
    }

    @Test
    fun `onEventCreated постит статус, закрепляет и сохраняет строку`() {
        val event = livePinEvent(clubId = clubId)
        every { pinRepository.findByEventId(event.id) } returns null
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), any(), any()) } returns 777L

        service.onEventCreated(event)

        verify {
            gateway.sendGroupMessageWithUrlButton(
                chatId,
                match { it.contains("Поход в баню") && it.contains("Идут — 3") },
                "Проголосовать",
                "https://t.me/clubs_test_bot?startapp=event_${event.id}"
            )
        }
        verify { pinRepository.insert(match { it.eventId == event.id && it.messageId == 777L }) }
        verify { gateway.pinChatMessage(chatId, 777L) }
    }

    @Test
    fun `onEventCreated молчит при выключенном тумблере`() {
        every { chatLinkRepository.findByClubId(clubId) } returns link.copy(livePinEnabled = false)

        service.onEventCreated(livePinEvent(clubId = clubId))

        verify(exactly = 0) { gateway.sendGroupMessageWithUrlButton(any(), any(), any(), any()) }
    }

    @Test
    fun `onEventCreated молчит если бот выпал из чата`() {
        every { chatLinkRepository.findByClubId(clubId) } returns link.copy(botStatus = BotChatStatus.KICKED)

        service.onEventCreated(livePinEvent(clubId = clubId))

        verify(exactly = 0) { gateway.sendGroupMessageWithUrlButton(any(), any(), any(), any()) }
    }

    @Test
    fun `onEventCreated без права закрепа — постит, но не закрепляет`() {
        every { chatLinkRepository.findByClubId(clubId) } returns link.copy(canPinMessages = false)
        val event = livePinEvent(clubId = clubId)
        every { pinRepository.findByEventId(event.id) } returns null
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), any(), any()) } returns 777L

        service.onEventCreated(event)

        verify { pinRepository.insert(any()) }
        verify(exactly = 0) { gateway.pinChatMessage(any(), any()) }
    }

    @Test
    fun `onEventCreated не создаёт строку при сбое отправки`() {
        val event = livePinEvent(clubId = clubId)
        every { pinRepository.findByEventId(event.id) } returns null
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), any(), any()) } returns null

        service.onEventCreated(event)

        verify(exactly = 0) { pinRepository.insert(any()) }
    }

    @Test
    fun `flush перерисовывает dirty-событие в режиме Этапа 2 и снимает флаг`() {
        val event = livePinEvent(clubId = clubId, stage2Triggered = true, status = EventStatus.stage_2)
        every { pinRepository.findByEventId(event.id) } returns
            EventChatPin(event.id, chatId, 777L, closedAt = null, summaryMessageId = null)
        every { eventRepository.findById(event.id) } returns event
        every { eventResponseRepository.countConfirmed(event.id) } returns 12
        every { eventResponseRepository.countWaitlisted(event.id) } returns 2
        every { pinRepository.findOpenStartedPins(any()) } returns emptyList()

        service.markDirty(event.id)
        service.flush()

        verify {
            gateway.editGroupMessage(
                chatId, 777L,
                match { it.contains("подтверждение мест") && it.contains("12 из 15") && it.contains("В очереди — 2") },
                "Подтвердить участие",
                any()
            )
        }

        // Флаг снят: повторный flush ничего не редактирует
        service.flush()
        verify(exactly = 1) { gateway.editGroupMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `flush закрывает закреп стартовавшего события — финальный текст и unpin`() {
        val event = livePinEvent(clubId = clubId, eventDatetime = OffsetDateTime.now().minusHours(1))
        val pin = EventChatPin(event.id, chatId, 777L, closedAt = null, summaryMessageId = null)
        every { pinRepository.findOpenStartedPins(any()) } returns listOf(pin)
        every { eventRepository.findById(event.id) } returns event
        every { eventResponseRepository.countConfirmed(event.id) } returns 12

        service.flush()

        verify { gateway.editGroupMessage(chatId, 777L, match { it.contains("Сбор закрыт") }, null, null) }
        verify { gateway.unpinChatMessage(chatId, 777L) }
        verify { pinRepository.markClosed(event.id) }
    }

    @Test
    fun `onEventCancelled — текст отмены с причиной, unpin, закрытие`() {
        val event = livePinEvent(clubId = clubId)
        every { pinRepository.findByEventId(event.id) } returns
            EventChatPin(event.id, chatId, 777L, closedAt = null, summaryMessageId = null)

        service.onEventCancelled(event, "все заболели")

        verify {
            gateway.editGroupMessage(
                chatId, 777L,
                match { it.contains("отменено") && it.contains("все заболели") },
                null, null
            )
        }
        verify { gateway.unpinChatMessage(chatId, 777L) }
        verify { pinRepository.markClosed(event.id) }
    }

    @Test
    fun `onEventCancelled постит громкое сообщение об отмене и возвращает chatId (маршрутизатор)`() {
        val event = livePinEvent(clubId = clubId)
        every { pinRepository.findByEventId(event.id) } returns
            EventChatPin(event.id, chatId, 777L, closedAt = null, summaryMessageId = null)
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), null, null) } returns 999L

        val postedChatId = service.onEventCancelled(event, "все заболели")

        // Правки беззвучны — пост отмены единственный пинг чата, DM уйдёт только не-в-чате.
        verify { gateway.sendGroupMessageWithUrlButton(chatId, match { it.contains("отменено") }, null, null) }
        org.junit.jupiter.api.Assertions.assertEquals(chatId, postedChatId)
    }

    @Test
    fun `onEventCancelled — пост отмены не вышел (Telegram молчит) — null, DM всем`() {
        val event = livePinEvent(clubId = clubId)
        every { pinRepository.findByEventId(event.id) } returns null
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), null, null) } returns null

        org.junit.jupiter.api.Assertions.assertNull(service.onEventCancelled(event, null))
    }

    @Test
    fun `onEventCreated возвращает chatId вышедшего поста (маршрутизатор)`() {
        val event = livePinEvent(clubId = clubId)
        every { pinRepository.findByEventId(event.id) } returns null
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), any(), any()) } returns 777L

        org.junit.jupiter.api.Assertions.assertEquals(chatId, service.onEventCreated(event))
    }

    @Test
    fun `onEventCreated при выключенном тумблере — null (DM всем)`() {
        every { chatLinkRepository.findByClubId(clubId) } returns link.copy(livePinEnabled = false)

        org.junit.jupiter.api.Assertions.assertNull(service.onEventCreated(livePinEvent(clubId = clubId)))
    }

    @Test
    fun `onAttendanceMarked постит итог один раз — второй вызов гасится claim'ом`() {
        val event = livePinEvent(clubId = clubId, eventDatetime = OffsetDateTime.now().minusHours(3))
        every { eventRepository.findById(event.id) } returns event
        every { pinRepository.findByEventId(event.id) } returns
            EventChatPin(event.id, chatId, 777L, closedAt = OffsetDateTime.now(), summaryMessageId = null)
        every { pinRepository.tryClaimSummary(event.id, chatId) } returns true andThen false
        every { eventResponseRepository.findAttendedUserIds(event.id) } returns List(11) { UUID.randomUUID() }
        every { eventResponseRepository.countConfirmed(event.id) } returns 13
        every { eventRepository.countPastEvents(clubId, any()) } returns 14
        every { eventResponseRepository.findFirstTimeAttendeeFirstNames(event.id, clubId) } returns listOf("Наташа")
        val next = livePinEvent(clubId = clubId, eventDatetime = OffsetDateTime.now().plusDays(7))
        every { eventRepository.findFutureEventsByClub(clubId, any()) } returns listOf(next)
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), any(), any(), any(), any()) } returns 888L

        service.onAttendanceMarked(event.id)
        service.onAttendanceMarked(event.id)

        // Итог — тихий пост (silent=true, решение PO 2026-07-08): сводка без пуша всем участникам.
        verify(exactly = 1) {
            gateway.sendGroupMessageWithUrlButton(
                chatId,
                match {
                    it.contains("Встреча №14 прошла") && it.contains("Пришли — 11 из 13") &&
                        it.contains("Наташа — впервые") && it.contains("Следующая")
                },
                "Иду на следующую",
                "https://t.me/clubs_test_bot?startapp=event_${next.id}",
                null,
                true
            )
        }
        verify { pinRepository.setSummaryMessageId(event.id, 888L) }
    }

    @Test
    fun `onAttendanceMarked без следующего события — пост без кнопки`() {
        val event = livePinEvent(clubId = clubId, eventDatetime = OffsetDateTime.now().minusHours(3))
        every { eventRepository.findById(event.id) } returns event
        every { pinRepository.findByEventId(event.id) } returns null
        every { pinRepository.tryClaimSummary(event.id, chatId) } returns true
        every { eventResponseRepository.findAttendedUserIds(event.id) } returns emptyList()
        every { eventResponseRepository.countConfirmed(event.id) } returns 0
        every { eventRepository.countPastEvents(clubId, any()) } returns 1
        every { eventResponseRepository.findFirstTimeAttendeeFirstNames(event.id, clubId) } returns emptyList()
        every { eventRepository.findFutureEventsByClub(clubId, any()) } returns emptyList()
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), null, null, any(), any()) } returns 888L

        service.onAttendanceMarked(event.id)

        verify {
            gateway.sendGroupMessageWithUrlButton(
                chatId, match { it.contains("Пришли — 0") && !it.contains("из") }, null, null, null, true
            )
        }
    }

    @Test
    fun `backfillForClub создаёт пины только будущим событиям без строки`() {
        val withPin = livePinEvent(clubId = clubId)
        val withoutPin = livePinEvent(clubId = clubId)
        every { eventRepository.findFutureEventsByClub(clubId, any()) } returns listOf(withPin, withoutPin)
        every { pinRepository.findByEventId(withPin.id) } returns
            EventChatPin(withPin.id, chatId, 111L, closedAt = null, summaryMessageId = null)
        every { pinRepository.findByEventId(withoutPin.id) } returns null
        every { gateway.sendGroupMessageWithUrlButton(chatId, any(), any(), any()) } returns 222L

        service.backfillForClub(clubId)

        verify(exactly = 1) { gateway.sendGroupMessageWithUrlButton(chatId, any(), any(), any()) }
        verify { pinRepository.insert(match { it.eventId == withoutPin.id }) }
    }

    @Test
    fun `disableForClub открепляет живые пины и удаляет строки`() {
        val pin = EventChatPin(UUID.randomUUID(), chatId, 777L, closedAt = null, summaryMessageId = null)
        every { pinRepository.findOpenByChatId(chatId) } returns listOf(pin)

        service.disableForClub(link)

        verify { gateway.unpinChatMessage(chatId, 777L) }
        verify { pinRepository.delete(pin.eventId) }
    }
}

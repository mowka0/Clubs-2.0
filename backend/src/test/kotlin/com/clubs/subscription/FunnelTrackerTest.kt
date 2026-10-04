package com.clubs.subscription

import com.clubs.chatlink.ChatDisconnectedEvent
import com.clubs.chatlink.ChatLinkedEvent
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Шаги привлечения: разбор метки «/start ad_<slug>» и события привязки (funnel.md § 3.1, AC-1…AC-4). */
class FunnelTrackerTest {

    private val funnelEventRepository = mockk<FunnelEventRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>()
    private val tracker = FunnelTracker(funnelEventRepository, userRepository)

    @Test
    fun `метка ad_slug принимается, регистр приводится к строчным, лишние пробелы не мешают`() {
        assertEquals("vk1", FunnelTracker.parseCampaign("/start ad_vk1"))
        assertEquals("vk_1-b", FunnelTracker.parseCampaign("/start AD_Vk_1-B"))
        assertEquals("vk1", FunnelTracker.parseCampaign("  /start   ad_vk1  "))
        assertEquals("a".repeat(64), FunnelTracker.parseCampaign("/start ad_" + "a".repeat(64)))
    }

    @Test
    fun `без payload'а, чужой payload и метка не по маске - органика`() {
        assertNull(FunnelTracker.parseCampaign("/start"))
        assertNull(FunnelTracker.parseCampaign("/start new"))
        assertNull(FunnelTracker.parseCampaign("/start ad_"))
        assertNull(FunnelTracker.parseCampaign("/start ad_vk 1"))
        assertNull(FunnelTracker.parseCampaign("/start ad_вк1"))
        assertNull(FunnelTracker.parseCampaign("/start ad_" + "a".repeat(65)))
    }

    @Test
    fun `start известного пользователя - шаг с user_id, кампанией и telegram id`() {
        val userId = UUID.randomUUID()
        every { userRepository.findByTelegramId(777L) } returns mockk<UsersRecord> { every { id } returns userId }

        tracker.botStarted(777L, "/start ad_vk1")

        verify { funnelEventRepository.record(FunnelStep.BOT_STARTED, userId, null, "vk1", 777L) }
    }

    @Test
    fun `start незнакомца - шаг без user_id, но с telegram id (строки в users ещё нет)`() {
        every { userRepository.findByTelegramId(778L) } returns null

        tracker.botStarted(778L, "/start")

        verify { funnelEventRepository.record(FunnelStep.BOT_STARTED, null, null, null, 778L) }
    }

    @Test
    fun `привязка и потеря чата пишутся по событиям с данными владельца`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()

        tracker.onChatLinked(ChatLinkedEvent(clubId, ownerId, 111L))
        tracker.onChatDisconnected(ChatDisconnectedEvent(clubId, ownerId))

        verify { funnelEventRepository.record(FunnelStep.CHAT_CONNECTED, ownerId, clubId, null, 111L) }
        verify { funnelEventRepository.record(FunnelStep.CHAT_DISCONNECTED, ownerId, clubId, null, null) }
    }
}

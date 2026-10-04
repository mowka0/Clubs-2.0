package com.clubs.subscription

import com.clubs.chatlink.BotChatStatus
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.CLUB_CHAT_LINKS
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.FUNNEL_EVENT
import com.clubs.generated.jooq.tables.references.SERVICE_SUBSCRIPTION
import com.clubs.generated.jooq.tables.references.USERS
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/**
 * Агрегаты недельного отчёта на jOOQ. Определения метрик — funnel.md § 3.3; здесь они один в
 * один переведены в запросы, поэтому класс намеренно не разбит: определение и запрос читаются
 * рядом. Только чтение, продуктовые данные не трогает.
 */
@Repository
class JooqFunnelReportRepository(
    private val dsl: DSLContext,
    private val subscriptionRepository: SubscriptionRepository,
) : FunnelReportRepository {

    // «С чатом» = привязка есть И бот в чате: чат, откуда бота выгнали, клиентом не считается
    // (биллинг рассуждает так же — BillingGate).
    private val botInChat: Condition =
        CLUB_CHAT_LINKS.BOT_STATUS.`in`(BotChatStatus.entries.filter { it.isInChat }.map { it.literal })

    override fun collect(week: ReportWeek, now: OffsetDateTime): FunnelReport {
        val steps = stepCounts(week)
        return FunnelReport(
            clubs = countClubs(DSL.noCondition()),
            clubsWithChat = countClubsWithChat(DSL.noCondition()),
            newClubs = countClubs(createdIn(week)),
            newClubsWithChat = countClubsWithChat(createdIn(week)),
            connections = steps.rows(FunnelStep.CHAT_CONNECTED),
            disconnections = steps.rows(FunnelStep.CHAT_DISCONNECTED),
            paying = countSubscriptions(SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLED_PENDING_END),
            mrrRubles = countSubscriptions(SubscriptionStatus.ACTIVE) *
                subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT) / KOPECKS_IN_RUBLE,
            meetingsCompleted = countCompletedMeetings(week),
            idleChats = idleChatNames(now),
            starts = steps.distinctTelegramIds(FunnelStep.BOT_STARTED),
            firstMeetings = steps.rows(FunnelStep.TRIAL_STARTED),
            checkouts = steps.distinctClubs(FunnelStep.CHECKOUT_STARTED),
            payments = steps.rows(FunnelStep.PAYMENT_SUCCEEDED),
            campaigns = campaignRows(week),
        )
    }

    private fun createdIn(week: ReportWeek): Condition =
        CLUBS.CREATED_AT.ge(week.start).and(CLUBS.CREATED_AT.lt(week.end))

    private fun inWindow(week: ReportWeek): Condition =
        FUNNEL_EVENT.CREATED_AT.ge(week.start).and(FUNNEL_EVENT.CREATED_AT.lt(week.end))

    /** Клубы без мягко удалённых. */
    private fun countClubs(extra: Condition): Int =
        dsl.fetchCount(CLUBS, CLUBS.IS_ACTIVE.isTrue.and(extra))

    private fun countClubsWithChat(extra: Condition): Int =
        dsl.fetchCount(
            dsl.selectOne().from(CLUBS)
                .join(CLUB_CHAT_LINKS).on(CLUB_CHAT_LINKS.CLUB_ID.eq(CLUBS.ID))
                .where(CLUBS.IS_ACTIVE.isTrue).and(botInChat).and(extra),
        )

    /** Подписки за чат в заданных статусах; PAST_DUE — грейс, не платят; ENDED — история. */
    private fun countSubscriptions(vararg statuses: SubscriptionStatus): Int =
        dsl.fetchCount(
            SERVICE_SUBSCRIPTION,
            SERVICE_SUBSCRIPTION.STATUS.`in`(*statuses).and(SERVICE_SUBSCRIPTION.SUBJECT_CLUB_ID.isNotNull),
        )

    /**
     * «Состоялось» = время прошло и встреча не отменена (статус completed ставит EventCompletionService),
     * посещаемость не проверяется. Только клубы с привязанным чатом — без условия на бота: встреча
     * прошедшей недели состоялась, даже если бота выгнали вчера.
     */
    private fun countCompletedMeetings(week: ReportWeek): Int =
        dsl.fetchCount(
            dsl.selectOne().from(EVENTS)
                .join(CLUB_CHAT_LINKS).on(CLUB_CHAT_LINKS.CLUB_ID.eq(EVENTS.CLUB_ID))
                .where(EVENTS.STATUS.eq(EventStatus.completed))
                .and(EVENTS.EVENT_DATETIME.ge(week.start)).and(EVENTS.EVENT_DATETIME.lt(week.end)),
        )

    /**
     * Чаты, привязанные не меньше 30 дней назад, у чьего клуба нет ни одной неотменённой встречи
     * в окне ±30 дней от сейчас: ни прошедшей, ни запланированной. Запланированная спасает от
     * списка — такой чат не в зоне риска.
     */
    private fun idleChatNames(now: OffsetDateTime): List<String> {
        val horizonBack = now.minusDays(IDLE_DAYS)
        val horizonAhead = now.plusDays(IDLE_DAYS)
        val hasMeetingAround = DSL.exists(
            dsl.selectOne().from(EVENTS)
                .where(EVENTS.CLUB_ID.eq(CLUB_CHAT_LINKS.CLUB_ID))
                .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                .and(EVENTS.EVENT_DATETIME.ge(horizonBack)).and(EVENTS.EVENT_DATETIME.lt(horizonAhead)),
        )
        return dsl.select(CLUBS.NAME).from(CLUB_CHAT_LINKS)
            .join(CLUBS).on(CLUBS.ID.eq(CLUB_CHAT_LINKS.CLUB_ID))
            .where(CLUBS.IS_ACTIVE.isTrue).and(botInChat)
            .and(CLUB_CHAT_LINKS.LINKED_AT.le(horizonBack))
            .andNot(hasMeetingAround)
            .orderBy(CLUBS.NAME)
            .fetch(CLUBS.NAME)
            .filterNotNull()
    }

    /** Счётчики всех шагов в окне одним запросом: строки, уникальные telegram id, уникальные клубы. */
    private class StepCounts(private val byKind: Map<String, Triple<Int, Int, Int>>) {
        fun rows(step: FunnelStep): Int = byKind[step.kind]?.first ?: 0
        fun distinctTelegramIds(step: FunnelStep): Int = byKind[step.kind]?.second ?: 0
        fun distinctClubs(step: FunnelStep): Int = byKind[step.kind]?.third ?: 0
    }

    private fun stepCounts(week: ReportWeek): StepCounts {
        val rows = DSL.count()
        val telegramIds = DSL.countDistinct(FUNNEL_EVENT.TELEGRAM_ID)
        val clubs = DSL.countDistinct(FUNNEL_EVENT.CLUB_ID)
        val byKind = dsl.select(FUNNEL_EVENT.KIND, rows, telegramIds, clubs)
            .from(FUNNEL_EVENT)
            .where(inWindow(week))
            .groupBy(FUNNEL_EVENT.KIND)
            .fetch()
            .associate { it[FUNNEL_EVENT.KIND]!! to Triple(it[rows], it[telegramIds], it[clubs]) }
        return StepCounts(byKind)
    }

    /**
     * Старты недели по кампании (уникальные telegram id); подключения и оплаты недели — к кампании
     * самого раннего /start того же человека за всё время (first touch), без /start — органика.
     * Строка есть, если в ней хоть одно ненулевое число: поздняя конверсия не должна пропадать.
     */
    private fun campaignRows(week: ReportWeek): List<CampaignRow> {
        val startsByCampaign = dsl.select(FUNNEL_EVENT.CAMPAIGN, DSL.countDistinct(FUNNEL_EVENT.TELEGRAM_ID))
            .from(FUNNEL_EVENT)
            .where(inWindow(week)).and(FUNNEL_EVENT.KIND.eq(FunnelStep.BOT_STARTED.kind))
            .groupBy(FUNNEL_EVENT.CAMPAIGN)
            .fetch()
            .associate { it.value1() to it.value2() }
        val connected = dsl.select(FUNNEL_EVENT.TELEGRAM_ID).from(FUNNEL_EVENT)
            .where(inWindow(week)).and(FUNNEL_EVENT.KIND.eq(FunnelStep.CHAT_CONNECTED.kind))
            .fetch(FUNNEL_EVENT.TELEGRAM_ID)
            .filterNotNull()
        val paid = dsl.select(USERS.TELEGRAM_ID).from(FUNNEL_EVENT)
            .join(USERS).on(USERS.ID.eq(FUNNEL_EVENT.USER_ID))
            .where(inWindow(week)).and(FUNNEL_EVENT.KIND.eq(FunnelStep.PAYMENT_SUCCEEDED.kind))
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()
        val firstCampaign = firstCampaignByTelegramId((connected + paid).toSet())
        val connectionsByCampaign = connected.groupingBy { firstCampaign[it] }.eachCount()
        val paymentsByCampaign = paid.groupingBy { firstCampaign[it] }.eachCount()

        return (startsByCampaign.keys + connectionsByCampaign.keys + paymentsByCampaign.keys)
            .map { CampaignRow(it, startsByCampaign[it] ?: 0, connectionsByCampaign[it] ?: 0, paymentsByCampaign[it] ?: 0) }
            .sortedWith(compareBy<CampaignRow> { it.campaign == null }.thenByDescending { it.starts }.thenBy { it.campaign })
    }

    /** Самый ранний /start на человека — `DISTINCT ON` в БД: строк `bot_started` у одного id может быть сколько угодно. */
    private fun firstCampaignByTelegramId(telegramIds: Set<Long>): Map<Long, String?> {
        if (telegramIds.isEmpty()) return emptyMap()
        return dsl.select(FUNNEL_EVENT.TELEGRAM_ID, FUNNEL_EVENT.CAMPAIGN)
            .distinctOn(FUNNEL_EVENT.TELEGRAM_ID)
            .from(FUNNEL_EVENT)
            .where(FUNNEL_EVENT.KIND.eq(FunnelStep.BOT_STARTED.kind)).and(FUNNEL_EVENT.TELEGRAM_ID.`in`(telegramIds))
            .orderBy(FUNNEL_EVENT.TELEGRAM_ID, FUNNEL_EVENT.CREATED_AT.asc())
            .fetch()
            .associate { it.value1()!! to it.value2() }
    }

    companion object {
        // Порог «чат без встреч»: столько дней без встречи назад и вперёд (funnel.md § 3.3).
        private const val IDLE_DAYS = 30L
        private const val KOPECKS_IN_RUBLE = 100
    }
}

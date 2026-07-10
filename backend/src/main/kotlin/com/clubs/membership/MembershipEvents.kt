package com.clubs.membership

import java.time.OffsetDateTime
import java.util.UUID

data class ExpiringSubscriptionNotification(
    val telegramId: Long,
    val clubName: String,
    // Для inline-кнопки-диплинка в DM «подписка истекла» (открывает /clubs/{id}, где живёт «Оплатить взнос»).
    val clubId: UUID
)

/**
 * Минимальная проекция строки membership — ровно столько, чтобы платёжные/шедулерные
 * флоу отличили «новую» подписку от «продления», не вытягивая полный jOOQ Record.
 * Полный домен Membership появится при рефакторинге модуля `membership`.
 */
data class MembershipExpiryRef(
    val id: UUID,
    val subscriptionExpiresAt: OffsetDateTime?
)

/**
 * Доступ участника к клубу ОТКРЫЛСЯ (стал active): бесплатное вступление, «Взнос получен»,
 * разморозка, ручное окно доступа. Слушает чат-интеграция (club-chat-link, «дверь»):
 * AFTER_COMMIT впускает человека в привязанный чат. Публикуется внутри транзакции мутации.
 */
data class MembershipAccessOpenedEvent(
    val clubId: UUID,
    val userId: UUID,
    /**
     * TRUE = до мутации доступа НЕ БЫЛО (вступление, разморозка, взнос после frozen/expired) —
     * человек ждёт подтверждения, DM «доступ открыт» уместен, даже если он уже сидит в чате
     * (кейс PO 2026-07-08: кик → повторное вступление → участник остался в чате → молчание).
     * FALSE = продление при живом доступе — DM сидящему в чате был бы спамом.
     */
    val wasAccessClosed: Boolean
)

/**
 * Доступ участника к клубу ЗАКРЫЛСЯ, но он остаётся в клубе должником: организатор заморозил
 * (freeze), просрочка продления (шедулер active→expired), вступление в платный клуб до первого
 * взноса (frozen). Слушает строгий режим чата (слайс 5): AFTER_COMMIT переводит должника
 * в «только чтение». Зеркало [MembershipAccessOpenedEvent].
 */
data class MembershipAccessClosedEvent(
    val clubId: UUID,
    val userId: UUID
)

/**
 * Человек больше не на пути в клуб и не в клубе: заявка отклонена / frozen-вступление
 * отклонено / участник исключён / добровольный выход без живого оплаченного окна /
 * оплаченное окно отменённой подписки истекло (шедулер). Чат-интеграция AFTER_COMMIT
 * отклоняет висящую заявку на вход в чат (всегда), а строгий режим (слайс 5) — банит
 * (решение PO 2026-07-08: бан за ЛЮБОЙ уход из клуба).
 */
data class MembershipAccessRevokedEvent(
    val clubId: UUID,
    val userId: UUID
)

/**
 * Минимальная ссылка на membership для батч-переходов шедулера (active→expired,
 * истёкшие cancelled): ровно столько, чтобы опубликовать чат-события по каждой строке.
 */
data class MembershipAccessRef(
    val clubId: UUID,
    val userId: UUID
)

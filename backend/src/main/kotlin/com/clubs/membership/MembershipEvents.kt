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
 * Путь в клуб закрылся ДО получения доступа: заявка отклонена / frozen-вступление отклонено /
 * участник исключён. Чат-интеграция AFTER_COMMIT отклоняет его висящую заявку на вход в чат.
 * Бан уже-состоящих в чате — сознательно НЕ здесь (слайс «строгий режим»).
 */
data class MembershipAccessRevokedEvent(
    val clubId: UUID,
    val userId: UUID
)

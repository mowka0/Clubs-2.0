package com.clubs.skladchina

import com.clubs.debt.DebtTotals
import com.clubs.generated.jooq.enums.SkladchinaKind
import com.clubs.generated.jooq.enums.SkladchinaStatus
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Сбор: повод и обёртка над долгами (docs/modules/skladchina-v3.md § 2.1). Сами деньги живут в
 * `debts` — сбор знает только вид, срок, реквизиты и стадию (запись / заморозка / заказ).
 */
data class Skladchina(
    val id: UUID,
    val clubId: UUID,
    val creatorId: UUID,

    val title: String,
    val description: String?,
    val rules: String?,
    val photoUrl: String?,

    val kind: SkladchinaKind,
    // shared: общая сумма; per_head: цена за человека; voluntary: ориентир (необязателен).
    val amountKopecks: Long?,
    val paymentLink: String,
    val paymentMethodNote: String?,
    // shared после встречи: список должников = пришедшие.
    val eventId: UUID?,

    // Обязателен у shared и per_head, у voluntary необязателен.
    val deadline: OffsetDateTime?,
    // shared до события: этап «Кто в деле?» открыт до этого момента; NULL = этапа нет.
    val enrollmentUntil: OffsetDateTime?,
    val minParticipants: Int?,
    val lockedAt: OffsetDateTime?,
    // per_head: «Заказываю» нажато, приём закрыт.
    val orderedAt: OffsetDateTime?,
    // voluntary: от кого скрыть (тихий сбор: без чат-поста, DM всем кроме него).
    val hiddenFromUserId: UUID?,

    val status: SkladchinaStatus,
    val closedAt: OffsetDateTime?,
    val reminderSentAt: OffsetDateTime?,
    val orderRemindedAt: OffsetDateTime?,

    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
) {
    /** Этап «Кто в деле?» ещё открыт: долгов нет, участники отмечаются. */
    val isEnrolling: Boolean
        get() = kind == SkladchinaKind.shared && enrollmentUntil != null && lockedAt == null

    val isActive: Boolean
        get() = status == SkladchinaStatus.active

    fun isHiddenFrom(userId: UUID): Boolean = hiddenFromUserId == userId
}

/**
 * Строка ленты, не зависящая от вызывающего: сбор плюс денежные итоги по долгам и число
 * отметившихся на этапе записи. Персональные поля (мой долг) считает экран деталей.
 */
data class SkladchinaWithAggregates(
    val skladchina: Skladchina,
    val totals: DebtTotals,
    val enrolledCount: Int
)

/** Строка «моей ленты» сборов: сбор + клуб + итоги + моя роль в нём. */
data class MySkladchinaFeedItem(
    val skladchina: Skladchina,
    val clubName: String,
    val clubAvatarUrl: String?,
    val totals: DebtTotals,
    // Статус моего долга как должника (null = я не должник в этом сборе).
    val myDebtStatus: String?,
    // Есть ли у меня как получателя долги в claimed — «ждёт вашего ответа».
    val awaitingMyConfirmation: Boolean
)

/** Прошедшая встреча, по которой ещё можно «Скинуться»: id, название, дата, число пришедших. */
data class SplittableEvent(
    val eventId: UUID,
    val title: String,
    val eventDatetime: OffsetDateTime,
    val attendedCount: Int
)

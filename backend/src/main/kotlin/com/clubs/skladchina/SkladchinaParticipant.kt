package com.clubs.skladchina

import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import java.time.OffsetDateTime
import java.util.UUID

data class SkladchinaParticipant(
    val skladchinaId: UUID,
    val userId: UUID,
    val expectedAmountKopecks: Long?,
    val declaredAmountKopecks: Long?,
    val status: SkladchinaParticipantStatus,
    val paidAt: OffsetDateTime?,
    val declinedAt: OffsetDateTime?,
    val reputationApplied: Boolean,
    // V28: отказ с подтверждением (шаблоны REQUIRES_APPROVAL). Открытый запрос = status `pending`
    // И declineRequestedAt != null. declineRejected закрывает путь (обязан заплатить, повторный запрос невозможен).
    val declineNote: String?,
    val declineRequestedAt: OffsetDateTime?,
    val declineRejected: Boolean,
    // V89: сверка оплаты организатором. `paid` = «заявил», дальше организатор при закрытии
    // засчитывает (payment_confirmed) или не находит платёж (payment_rejected). У отклонённого
    // есть окно на чек: приложил (payment_disputed) — списание ждёт решения организатора.
    val paymentConfirmedAt: OffsetDateTime? = null,
    val paymentRejectedAt: OffsetDateTime? = null,
    val paymentRejectNote: String? = null,
    val receiptUrl: String? = null,
    val receiptNote: String? = null,
    val disputedAt: OffsetDateTime? = null,
    val disputeTerminal: Boolean = false,
    val createdAt: OffsetDateTime
)

/**
 * «Деньги за участника засчитаны». До закрытия под предикат попадают заявленные оплаты, после
 * закрытия — только подтверждённые организатором: `paid` живёт лишь пока сбор идёт, при закрытии
 * каждая заявка становится `payment_confirmed` либо `payment_rejected`. Один и тот же предикат
 * поэтому даёт и «сколько заявлено» по ходу сбора, и «сколько реально дошло» после него —
 * прогресс-бар, собранная сумма, статистика клуба и живой статус в чате считают по нему.
 */
val PAID_LIKE_STATUSES: Set<SkladchinaParticipantStatus> = setOf(
    SkladchinaParticipantStatus.paid,
    SkladchinaParticipantStatus.payment_confirmed
)

/**
 * Агрегированная строка для "моей ленты" — Skladchina + мой-участник + вычисленные счётчики.
 * Возвращается из repository в service, маппится в MySkladchinaListItemDto.
 */
data class MySkladchinaFeedItem(
    val skladchina: Skladchina,
    val clubName: String,
    val clubAvatarUrl: String?,
    val myStatus: SkladchinaParticipantStatus?,    // null, если пользователь создатель, но не участник
    // V89: когда организатор не нашёл мой платёж — по нему считается окно на чек в ленте.
    val myPaymentRejectedAt: OffsetDateTime? = null,
    val collectedKopecks: Long,
    val participantCount: Int,
    val paidCount: Int,
    // Из них сверено организатором (payment_confirmed) — зелёный сегмент прогресса.
    val confirmedCount: Int = 0
)

/**
 * Строка участника с денормализованной информацией о пользователе — для вида организатора на SkladchinaPage.
 */
data class SkladchinaParticipantInfo(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val expectedAmountKopecks: Long?,
    val declaredAmountKopecks: Long?,
    val status: SkladchinaParticipantStatus,
    val paidAt: OffsetDateTime?,
    val declineNote: String?,
    val declineRequestedAt: OffsetDateTime?,
    val declineRejected: Boolean,
    val declineRejectNote: String?,
    // V89: сверка оплат — организатор видит, кого он отклонил и что тот прислал в ответ.
    val paymentRejectedAt: OffsetDateTime? = null,
    val paymentRejectNote: String? = null,
    val receiptUrl: String? = null,
    val receiptNote: String? = null,
    val disputedAt: OffsetDateTime? = null,
    val disputeTerminal: Boolean = false
)

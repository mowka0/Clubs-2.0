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
    val createdAt: OffsetDateTime
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
    val collectedKopecks: Long,
    val participantCount: Int,
    val paidCount: Int
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
    val declineRejectNote: String?
)

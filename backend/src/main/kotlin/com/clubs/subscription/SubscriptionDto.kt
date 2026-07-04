package com.clubs.subscription

import jakarta.validation.constraints.NotBlank
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Оформить подписку / повысить план платформенного сервисного сбора. `plan` и `role` — строки
 * на HTTP-границе (валидируются → jOOQ enum в сервисе), по конвенции проекта (ср. CreateClubRequest.category).
 */
data class CreateSubscriptionRequest(
    @field:NotBlank val plan: String,
    val role: String = "ORGANIZER",
    /** Обязателен только для member-pays (за флагом MEMBER_PAYS_ENABLED). */
    val subjectClubId: UUID? = null,
)

/** Текущий план/статус организатора. `status` равен null на неявном плане FREE (нет строки подписки). */
data class SubscriptionStatusDto(
    val plan: String,
    val status: String?,
    val currentPeriodEnd: OffsetDateTime?,
    /** null = без ограничений. */
    val maxPaidClubs: Int?,
    val priceKopecks: Int,
)

/** Одна строка каталога планов (для экрана управления + модалки paywall). */
data class PlanOptionDto(
    val plan: String,
    /** null = без ограничений. */
    val maxPaidClubs: Int?,
    val priceKopecks: Int,
)

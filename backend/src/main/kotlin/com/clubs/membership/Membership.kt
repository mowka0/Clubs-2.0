package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import java.time.OffsetDateTime
import java.util.UUID

data class Membership(
    val id: UUID,
    val userId: UUID,
    val clubId: UUID,
    val status: MembershipStatus,
    val role: MembershipRole,
    val joinedAt: OffsetDateTime,
    val subscriptionExpiresAt: OffsetDateTime?,
    // Приватная заметка организатора (member admin profile S1). Null = заметки нет. Читает только организатор.
    // Дефолт null, чтобы не обновлять все тестовые билдеры Membership(...); прод задаёт значение через маппер.
    val organizerNote: String? = null,
    // Claim об оплате взноса от участника (de-Stars): frozen-участник заявил, что оплатил. duesClaimedAt =
    // когда (null = claim нет); duesClaimMethod = "sbp"|"cash"; duesProofUrl = скриншот (только sbp).
    // Сбрасывается, когда организатор открывает доступ. Дефолты null — чтобы не трогать тестовые билдеры.
    val duesClaimedAt: OffsetDateTime? = null,
    val duesClaimMethod: String? = null,
    val duesProofUrl: String? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
)

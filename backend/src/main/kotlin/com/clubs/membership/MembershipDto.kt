package com.clubs.membership

import java.time.OffsetDateTime
import java.util.UUID

data class MembershipDto(
    val id: UUID,
    val userId: UUID,
    val clubId: UUID,
    val status: String,
    val role: String,
    val joinedAt: OffsetDateTime?,
    val subscriptionExpiresAt: OffsetDateTime?,
    // Собственное заявление участника об оплате взносов (de-Stars): когда он задекларировал оплату
    // (null = не задекларировано) + способ ("sbp"|"cash"). Управляет состоянием участника
    // «оплата на проверке» на экране заморозки клуба.
    val duesClaimedAt: OffsetDateTime? = null,
    val duesClaimMethod: String? = null
)

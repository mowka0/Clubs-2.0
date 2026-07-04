package com.clubs.membership

import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.MembershipRole
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Проекция MEMBERSHIPS x CLUBS x USER_CLUB_REPUTATION для сводки репутации авторизованного
 * пользователя (таб "Профиль"). Одна строка на клуб, который у пользователя в scope: сейчас-активные
 * клубы И покинутые клубы, у которых всё ещё есть история репутации (секция "История").
 */
data class UserClubReputationInfo(
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val category: ClubCategory,
    val role: MembershipRole,
    val joinedAt: OffsetDateTime?,
    // Сырые соседи из кэша (nullable, если строки репутации нет); порог применяется маппером.
    // P1b показывает Trust (считается в MembershipService), а не сырой индекс.
    val promiseFulfillmentPct: BigDecimal?,
    val totalConfirmations: Int?,
    val totalAttendances: Int?,
    val spontaneityCount: Int?,
    val outcomeCount: Int,
    // true = сейчас-активный клуб (у участника есть доступ И клуб активен); false = "История"
    // (покинутый/истёкший membership, включён только потому что история репутации сохранилась).
    val active: Boolean
)

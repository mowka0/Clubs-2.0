package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Проекция MEMBERSHIPS x USERS x USER_CLUB_REPUTATION, используемая списком участников
 * клуба. Живёт в модуле membership, потому что строится вокруг строки membership с
 * присоединёнными полями user/reputation.
 *
 * [status] + [subscriptionExpiresAt] управляют бакетами дашборда организатора
 * (de-Stars Slice 2): `frozen` → «Ждут оплаты», `active` с истечением в пределах
 * недели → «Скоро закончится», иначе «Активные». Здесь они сырые; наружу организатору
 * их отдаёт только mapper.
 */
data class ClubMemberInfo(
    val userId: UUID,
    val firstName: String?,
    val lastName: String?,
    val avatarUrl: String?,
    val role: MembershipRole,
    val joinedAt: OffsetDateTime,
    // Сырой сосед из кэша (nullable, если нет строки репутации); порог «Новичок» (outcomeCount)
    // применяет mapper. P1b показывает Trust (считается в MemberService), а не сырой индекс.
    val promiseFulfillmentPct: BigDecimal?,
    // Подтверждения Stage-2 на текущий момент. Отличает участника только-по-финансам (0
    // подтверждений → нет трека посещаемости) от no-show (подтверждений > 0, 0% выполнения),
    // чтобы список скрывал вводящее в заблуждение «Обещания 0%» для первого — паритет с
    // hasActivity на ProfilePage (F5-08).
    val totalConfirmations: Int?,
    val outcomeCount: Int,
    val status: MembershipStatus,
    val subscriptionExpiresAt: OffsetDateTime?,
    // Заявка участника об оплате взноса (de-Stars): когда он задекларировал оплату (null = не было)
    // + способ ("sbp"|"cash"). Открывается только организатору (фильтруется в mapper), чтобы бакет
    // «Ждут оплаты» мог пометить «оплата заявлена».
    val duesClaimedAt: OffsetDateTime? = null,
    val duesClaimMethod: String? = null
)

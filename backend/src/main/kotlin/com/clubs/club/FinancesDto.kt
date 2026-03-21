package com.clubs.club

import java.math.BigDecimal

data class FinancesDto(
    val activeMembers: Int,
    val monthlyRevenue: Int,
    val organizerShare: Int,
    val platformFee: Int,
    val organizerSharePct: BigDecimal,
    val platformFeePct: BigDecimal
)

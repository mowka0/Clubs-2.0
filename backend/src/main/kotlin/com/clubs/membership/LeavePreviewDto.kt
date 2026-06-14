package com.clubs.membership

/**
 * Pre-leave preview shown in the "Выйти из клуба?" confirm dialog: how many open obligations
 * the caller would break by leaving a free club. Counts only — penalty magnitudes are internal
 * (H8), so the UI says "вы бросаете N обязательств и потеряете надёжность" without a number for
 * the points. Paid clubs break nothing (all zero).
 */
data class LeavePreviewDto(
    val eventObligations: Int,
    val skladchinaObligations: Int,
    val totalObligations: Int
)

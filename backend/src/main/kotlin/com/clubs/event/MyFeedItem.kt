package com.clubs.event

import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote

data class MyFeedItem(
    val event: Event,
    val clubName: String,
    val clubAvatarUrl: String?,
    val myVote: Stage_1Vote?,
    val myFinalStatus: FinalStatus?,
    val goingCount: Int,
    val confirmedCount: Int,
    // true = прошедшее посещённое событие (attendance='attended'), попадает в секцию «История».
    // Вычисляет репозиторий по бакету ORDER BY, а не клиент по status/дате: статус completed
    // выставляется кроном с лагом до ~7ч, окно рассинхрона реально (AC-H14).
    val isHistory: Boolean
)

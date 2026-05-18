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
    val confirmedCount: Int
)

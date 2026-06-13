package com.clubs.membership

/**
 * The authenticated user's reputation for the profile screen: the global "надёжен в N из M клубов"
 * aggregate over ALL clubs with a track record, plus the per-club lists split into currently-active
 * clubs and "История" (clubs the user left but still has a track record in).
 */
data class MyReputationDto(
    val global: GlobalTrustDto,
    val activeClubs: List<UserClubReputationDto>,
    val historyClubs: List<UserClubReputationDto>
)

/**
 * Global view (P1b). Primary signal = "надёжен в [reliableClubs] из [trackRecordClubs] клубов".
 * [score] is the secondary 0-100 number, null when there is no track record anywhere
 * (trackRecordClubs == 0) — the UI then shows "Пока недостаточно истории", never "0 из 0".
 */
data class GlobalTrustDto(
    val reliableClubs: Int,
    val trackRecordClubs: Int,
    val score: Int?
)

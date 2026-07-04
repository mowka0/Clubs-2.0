package com.clubs.membership

/**
 * Репутация аутентифицированного юзера для экрана профиля: глобальный агрегат
 * «надёжен в N из M клубов» по ВСЕМ клубам с историей, плюс списки по клубам,
 * разбитые на текущие активные клубы и «История» (клубы, которые юзер покинул,
 * но по которым у него ещё есть история).
 */
data class MyReputationDto(
    val global: GlobalTrustDto,
    val activeClubs: List<UserClubReputationDto>,
    val historyClubs: List<UserClubReputationDto>
)

/**
 * Глобальное представление (P1b). Основной сигнал = «надёжен в [reliableClubs] из
 * [trackRecordClubs] клубов». [score] — второстепенное число 0-100, null, если истории
 * нет нигде (trackRecordClubs == 0) — тогда UI показывает «Пока недостаточно истории»,
 * никогда не «0 из 0».
 */
data class GlobalTrustDto(
    val reliableClubs: Int,
    val trackRecordClubs: Int,
    val score: Int?
)

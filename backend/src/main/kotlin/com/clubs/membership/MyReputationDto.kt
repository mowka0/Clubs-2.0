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
    val historyClubs: List<UserClubReputationDto>,
    // «Статистика» профиля (мокап P3, PO 2026-07-21): сырые посещения по всем клубам — вне репутации.
    val visits: MyVisitsDto
)

/**
 * Сырые посещения для блока «Статистика» в профиле: [totalEventsAttended] — все attended-отметки
 * по всем клубам (события с лимитом + открытые встречи), [openEventsAttended] — из них открытые.
 * Вне репутации: считается из отметок явки, а не из ledger. Фронт скрывает блок при нуле.
 */
data class MyVisitsDto(
    val totalEventsAttended: Int,
    val openEventsAttended: Int
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

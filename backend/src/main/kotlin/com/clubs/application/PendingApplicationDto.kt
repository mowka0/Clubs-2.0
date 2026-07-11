package com.clubs.application

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Карточка ожидающей заявки для кросс-клубового инбокса организатора на MyClubsPage.
 * Обогащена личностью заявителя, агрегатом peer-сигналов и краткой информацией о клубе,
 * чтобы фронтенд рендерил список без N+1 доп. запросов.
 *
 * См. docs/modules/applications-inbox.md § "GET /api/users/me/applications-pending".
 */
data class PendingApplicationDto(
    val applicationId: UUID,
    val answerText: String?,
    val createdAt: OffsetDateTime,
    val hoursUntilAutoReject: Int,
    val applicant: ApplicantInfoDto,
    val peerStats: PeerStatsDto,
    val club: ClubBriefDto
)

data class ApplicantInfoDto(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val telegramUsername: String?,
    val avatarUrl: String?,
    val country: String?,
    val city: String?,
    val bio: String?,
    val interests: List<String>
)

/**
 * Кросс-клубовый сигнал о заявителе для карточки review организатора. Бэкенд возвращает сырые
 * счётчики; формулировки формируются на фронтенде.
 *
 * Счётчики участия (из `user_club_reputation`, см. docs/modules/reputation.md):
 *  - memberClubCount    = число клубов, где у пользователя есть строка репутации.
 *  - totalConfirmations = сумма подтверждений Этапа 2 (финальное "идёт"/"не идёт").
 *  - totalAttendances   = сумма реально посещённых событий из этих подтверждений.
 *
 * Кросс-клубовая репутация (вычисляется on-read из леджера, см. [com.clubs.reputation.ApplicantSignalService]):
 *  - reliableClubs / trackRecordClubs = donut «надёжен в N из M клубов».
 *  - level / levelName / levelTier     = глобальный геймификационный уровень (проекция «для других») для пилла.
 */
data class PeerStatsDto(
    val memberClubCount: Int,
    val totalConfirmations: Int,
    val totalAttendances: Int,
    val reliableClubs: Int,
    val trackRecordClubs: Int,
    val level: Int,
    val levelName: String,
    val levelTier: String
)

data class ClubBriefDto(
    val id: UUID,
    val name: String,
    val avatarUrl: String?,
    // Вместимость клуба (club-invites): инбокс группирует заявки ПОЛНОГО клуба в блок
    // «Расширить клуб и принять всех» — фронту нужны живой счётчик занятых мест и лимит.
    val memberCount: Int,
    val memberLimit: Int
)

/**
 * Счётчик, питающий точку на вкладке `/my-clubs`: ожидающие заявки во всех клубах вызывающего,
 * которыми он владеет (действие организатора). De-Stars Slice 2 убрал счётчики Stars «ожидает
 * оплаты» — approve создаёт членство сразу, поэтому такого состояния больше не существует.
 *
 * См. docs/modules/applications-inbox.md § "GET /api/users/me/applications-pending-count".
 */
data class PendingApplicationsCountDto(
    val inboxCount: Int,
    // De-Stars: frozen-участники, заявившие об оплате взноса во всех клубах вызывающего — они оплатили
    // и ждут решения организатора («Взнос получен» / «Отказать»). Тоже зажигает точку на «Мои клубы».
    val awaitingDuesCount: Int = 0
)

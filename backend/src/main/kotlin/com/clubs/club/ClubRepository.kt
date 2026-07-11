package com.clubs.club

import com.clubs.common.dto.PageResponse
import java.util.UUID

interface ClubRepository {

    fun create(request: CreateClubRequest, ownerId: UUID, inviteCode: String? = null): Club

    fun findById(id: UUID): Club?

    fun findByInviteCode(code: String): Club?

    fun updateInviteCode(id: UUID, code: String): Club?

    /** Точечное поднятие лимита участников («Расширить клуб и принять всех», club-invites). */
    fun updateMemberLimit(id: UUID, memberLimit: Int): Club?

    fun update(id: UUID, request: UpdateClubRequest): Club?

    fun countByOwnerId(ownerId: UUID): Int

    /** Число активных ПЛАТНЫХ клубов пользователя (subscription_price > 0). Бесплатные исключены —
     *  они не расходуют ёмкость плана (docs/modules/payment-v2.md §3.1). */
    fun countPaidByOwnerId(ownerId: UUID): Int

    /** ID активных клубов, которыми владеет [ownerId]. Пустой список, если клубов нет. */
    fun findIdsByOwnerId(ownerId: UUID): List<UUID>

    /** Батч-поиск активных клубов по ID. Пустой вход → пустой выход (без SQL-запроса). */
    fun findByIds(ids: Collection<UUID>): List<Club>

    fun softDelete(id: UUID)

    fun findAll(filters: ClubFilterParams): PageResponse<ClubListItemDto>

    /**
     * Ближайшее предстоящее событие для каждого клуба из [clubIds] (status=upcoming,
     * event_datetime > now). Клубы без предстоящих событий в map отсутствуют. Семантика та же,
     * что у «ближайшего события» Discovery-карточек; переиспользуется раскрывающейся карточкой
     * «Моих клубов» (CTA «Ближайшая встреча» — reputation-path-back.md).
     */
    fun findNearestEvents(clubIds: List<UUID>): Map<UUID, NearestEventDto>
}

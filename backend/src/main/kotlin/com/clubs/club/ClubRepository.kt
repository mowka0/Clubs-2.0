package com.clubs.club

import com.clubs.common.dto.PageResponse
import java.util.UUID

interface ClubRepository {

    fun create(request: CreateClubRequest, ownerId: UUID, inviteCode: String? = null): Club

    fun findById(id: UUID): Club?

    fun findByInviteCode(code: String): Club?

    fun updateInviteCode(id: UUID, code: String): Club?

    fun update(id: UUID, request: UpdateClubRequest): Club?

    fun countByOwnerId(ownerId: UUID): Int

    fun softDelete(id: UUID)

    fun findAll(filters: ClubFilterParams): PageResponse<ClubListItemDto>

    fun incrementMemberCount(clubId: UUID)

    fun decrementMemberCountSafely(clubId: UUID, delta: Int)

    fun decreaseActivityRatingSafely(clubId: UUID, delta: Int)

    fun linkTelegramGroup(clubId: UUID, telegramGroupId: Long)
}

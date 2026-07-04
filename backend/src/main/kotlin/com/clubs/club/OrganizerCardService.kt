package com.clubs.club

import com.clubs.common.exception.NotFoundException
import com.clubs.membership.MembershipRepository
import com.clubs.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Строит карточку доверия организатора, показываемую в форме оплаты взноса (de-Stars: деньги идут
 * напрямую организатору, поэтому участнику нужно знать, кому он платит). Только сигналы про аккаунт;
 * пороги отображения применяет фронтенд, чтобы у свежего аккаунта показывались только имя и
 * «недавно» (никогда не нули).
 *
 * На уровне контроллера — только чтение и JWT (видно всем, как факты качества клуба) —
 * организатор является публичным хостом клуба, гейта на владение нет.
 */
@Service
class OrganizerCardService(
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository,
    private val userRepository: UserRepository
) {

    @Transactional(readOnly = true)
    fun getOrganizerCard(clubId: UUID): OrganizerCardDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        val owner = userRepository.findById(club.ownerId) ?: throw NotFoundException("Organizer not found")
        val ownedClubIds = clubRepository.findIdsByOwnerId(club.ownerId)
        return OrganizerCardDto(
            firstName = owner.firstName,
            lastName = owner.lastName,
            username = owner.telegramUsername,
            avatarUrl = owner.avatarUrl,
            onPlatformSince = owner.createdAt!!,
            clubsCount = ownedClubIds.size,
            trustedMembers = membershipRepository.countActiveNonOrganizerMembersInClubs(ownedClubIds)
        )
    }
}

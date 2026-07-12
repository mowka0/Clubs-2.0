package com.clubs.common.auth

import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.membership.Membership
import com.clubs.membership.MembershipRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * ЕДИНСТВЕННАЯ реализация предиката «менеджер клуба» (co-organizers):
 * менеджер = владелец клуба (clubs.owner_id) ИЛИ участник с ролью co_organizer
 * при СТРОГО активном членстве (fail-close: frozen/expired/cancelled прав не дают).
 *
 * Намеренно НЕ использует MembershipRepository.findActiveByUserAndClub — тот метод misnomer
 * (включает frozen/expired, что для владельца было недостижимо, а для ролей стало бы дырой
 * fail-open). Канон статуса — только `active`, зеркалит MembershipAccess.hasAccess().
 *
 * Слой 1 (аннотация @RequiresClubManager) и слой 2 (service-проверки) оба ходят сюда —
 * копипасты предиката по сервисам быть не должно (docs/modules/co-organizers.md § Единый механизм).
 */
@Component
class ClubManagerGuard(
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository
) {

    /** Предикат менеджера для уже загруженного клуба (без 404-семантики). */
    fun isManager(club: Club, userId: UUID): Boolean {
        if (club.ownerId == userId) return true
        val membership = membershipRepository.findByUserAndClub(userId, club.id)
        return isActiveManagerMembership(membership)
    }

    /** Предикат менеджера по clubId; несуществующий/неактивный клуб → false. */
    fun isClubManager(clubId: UUID, userId: UUID): Boolean {
        val club = clubRepository.findById(clubId) ?: return false
        return isManager(club, userId)
    }

    /** Гейт для уже загруженного клуба: не менеджер → 403. */
    fun requireManager(club: Club, userId: UUID) {
        if (!isManager(club, userId)) {
            throw ForbiddenException("Управлять клубом может владелец или активный со-организатор")
        }
    }

    /** Гейт по clubId: клуб не найден/неактивен → 404, не менеджер → 403. Возвращает клуб. */
    fun requireClubManager(clubId: UUID, userId: UUID): Club {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        requireManager(club, userId)
        return club
    }

    /**
     * Роль менеджера у УЖЕ загруженной строки membership вызывающего (organizer|co_organizer)
     * при строго активном членстве. Для мест, где caller-membership уже прочитан
     * (MemberService: бакеты ростера, орг-поля карточки) — без второго запроса в БД.
     */
    fun isActiveManagerMembership(membership: Membership?): Boolean =
        membership != null &&
            membership.status == MembershipStatus.active &&
            (membership.role == MembershipRole.organizer || membership.role == MembershipRole.co_organizer)

    /**
     * Target-матрица управления участником (freeze/кик/взнос/заметка/награды/access-until):
     *  - владелец (caller = clubs.owner_id) управляет всеми, кроме организатора
     *    (единственная строка role=organizer — его собственная) → 400;
     *  - со-организатор управляет ТОЛЬКО участниками с ролью member; владелец или другой
     *    со-орг для него неприкосновенны → 403.
     */
    fun requireManageableTarget(club: Club, target: Membership, callerId: UUID) {
        if (club.ownerId != callerId && target.role != MembershipRole.member) {
            throw ForbiddenException("Со-организатор может управлять только обычными участниками")
        }
        if (target.role == MembershipRole.organizer) {
            throw ValidationException("Нельзя управлять организатором клуба")
        }
    }
}

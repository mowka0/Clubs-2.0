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
 * ЕДИНСТВЕННАЯ реализация авторизации по ролям клуба на капабилити-модели (docs/modules/club-roles.md):
 * право на действие = [ClubCapability], роль → набор прав живёт в [RoleCapabilities].
 *
 * Владелец клуба (clubs.owner_id) проходит любой гейт (owner-bypass = «все capabilities»). Прочим право
 * действует ТОЛЬКО при строго активном членстве (fail-close: frozen/expired/cancelled прав не дают, PO №6).
 *
 * Намеренно НЕ использует MembershipRepository.findActiveByUserAndClub — тот метод misnomer
 * (включает frozen/expired, что стало бы дырой fail-open). Канон статуса — только `active`,
 * зеркалит MembershipAccess.hasAccess().
 *
 * Слой 1 (аннотация @RequiresCapability) и слой 2 (service-проверки) оба ходят сюда — копипасты
 * предиката по сервисам быть не должно (docs/modules/co-organizers.md § Единый механизм).
 */
@Component
class ClubRoleGuard(
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository
) {

    /** Есть ли у пользователя [capability] в уже загруженном клубе (owner → всегда; иначе active-роль → набор). */
    fun hasCapability(club: Club, userId: UUID, capability: ClubCapability): Boolean {
        if (club.ownerId == userId) return true
        val membership = membershipRepository.findByUserAndClub(userId, club.id)
        return hasCapabilityInMembership(membership, capability)
    }

    /** Есть ли у пользователя [capability] в клубе по clubId; несуществующий/неактивный клуб → false. */
    fun hasCapability(clubId: UUID, userId: UUID, capability: ClubCapability): Boolean {
        val club = clubRepository.findById(clubId) ?: return false
        return hasCapability(club, userId, capability)
    }

    /** Гейт для уже загруженного клуба: нет [capability] → 403. */
    fun requireCapability(club: Club, userId: UUID, capability: ClubCapability) {
        if (!hasCapability(club, userId, capability)) {
            throw ForbiddenException("Управлять клубом может владелец или активный со-организатор")
        }
    }

    /** Гейт по clubId: клуб не найден/неактивен → 404, нет [capability] → 403. Возвращает клуб. */
    fun requireCapability(clubId: UUID, userId: UUID, capability: ClubCapability): Club {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        requireCapability(club, userId, capability)
        return club
    }

    /**
     * Есть ли [capability] у УЖЕ загруженной строки membership вызывающего (для мест, где
     * caller-membership уже прочитан — ростер/карточка, без второго запроса в БД).
     * Строго активное членство (fail-close). ВНИМАНИЕ: не покрывает owner-bypass — вызывать
     * только когда membership заведомо принадлежит вызывающему и owner-случай учтён отдельно.
     */
    fun hasCapabilityInMembership(membership: Membership?, capability: ClubCapability): Boolean =
        membership != null &&
            membership.status == MembershipStatus.active &&
            RoleCapabilities.roleHasCapability(membership.role, capability)

    /**
     * View-level гейт «активный менеджер» для УЖЕ загруженной строки membership вызывающего:
     * есть хоть одно ДЕЛЕГИРУЕМОЕ право при строго активном членстве. Используется там, где нужен
     * менеджерский вид (бакеты ростера, орг-поля карточки — co-organizers точки 40/41), а не конкретное
     * действие. Для owner (role=organizer) и co_organizer → true; member → false; frozen/expired → false.
     */
    fun isActiveManagerMembership(membership: Membership?): Boolean =
        membership != null &&
            membership.status == MembershipStatus.active &&
            RoleCapabilities.capabilitiesFor(membership.role).isNotEmpty()

    /**
     * Target-матрица управления участником (freeze/кик/взнос/заметка/награды/access-until), ортогональна
     * capability (PO №7): проверяется ДОПОЛНИТЕЛЬНО к MANAGE_MEMBERS/GRANT_AWARDS.
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

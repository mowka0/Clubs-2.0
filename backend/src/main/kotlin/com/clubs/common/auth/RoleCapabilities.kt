package com.clubs.common.auth

import com.clubs.generated.jooq.enums.MembershipRole

/**
 * Единственный источник истины о правах роли: карта `роль → набор capabilities` (docs/modules/club-roles.md § 2).
 * Права ролей живут В КОДЕ (решение PO №3): «добавить/убрать право у роли» = правка этой карты + деплой,
 * без редактора прав per-club и без хранения в БД.
 *
 * Владелец (organizer) в карте перечислен полным набором как справочник; фактический owner-bypass
 * («владелец = все capabilities») делает [ClubRoleGuard.hasCapability] по clubs.owner_id.
 *
 * Задел на будущее — роль `moderator` (ПРИМЕР, НЕ реализуем сейчас; enum membership_role не трогаем).
 * Показывает, что роль встаёт ОДНОЙ записью, не трогая ни одну из 45 точек авторизации:
 * ```
 * MembershipRole.moderator to setOf(APPROVE_APPLICATIONS, MANAGE_EVENTS, MANAGE_SKLADCHINA)
 * ```
 * Добавление такой роли = (а) ALTER TYPE membership_role ADD VALUE 'moderator' (будущая миграция),
 * (б) одна строка в этой карте, (в) label+описание на фронте. Гейты не меняются — они по capability.
 */
object RoleCapabilities {

    /**
     * Делегируемый бакет (9) — ЯВНЫЙ список прав, которые получает co_organizer. Владельческий бакет
     * выводится как разница (см. ниже), поэтому дефолт для НОВОГО права — «владельческое» (fail-closed):
     * забытая при расширении enum capability НЕ утечёт со-оргу автоматически. Добавляя право, которое
     * должно быть делегируемым, — впиши его СЮДА осознанно (и обнови тест классификации).
     */
    val DELEGATED_CAPABILITIES: Set<ClubCapability> = setOf(
        ClubCapability.APPROVE_APPLICATIONS,
        ClubCapability.MANAGE_EVENTS,
        ClubCapability.MANAGE_SKLADCHINA,
        ClubCapability.MANAGE_MEMBERS,
        ClubCapability.GRANT_AWARDS,
        ClubCapability.EDIT_CLUB_SETTINGS,
        ClubCapability.VIEW_FINANCES,
        ClubCapability.VIEW_STATS,
        ClubCapability.SEND_INVITES
    )

    /**
     * Владельческий бакет — всё, что НЕ делегируется (полный набор минус делегируемый). Новое
     * неклассифицированное право попадает сюда автоматически (fail-closed): владелец его получит
     * через owner-bypass, со-организатор — нет.
     */
    val OWNER_ONLY_CAPABILITIES: Set<ClubCapability> =
        ClubCapability.entries.toSet() - DELEGATED_CAPABILITIES

    /**
     * Карта роль → набор прав. organizer — все 14; co_organizer — 9 делегируемых; member — пусто.
     * Роли без записи (гипотетические) трактуются как «без прав» ([capabilitiesFor]).
     */
    private val capabilitiesByRole: Map<MembershipRole, Set<ClubCapability>> = mapOf(
        MembershipRole.organizer to ClubCapability.entries.toSet(),
        MembershipRole.co_organizer to DELEGATED_CAPABILITIES,
        MembershipRole.member to emptySet()
    )

    /** Набор прав роли (пустой для неизвестной/незамапленной роли — fail-close). */
    fun capabilitiesFor(role: MembershipRole): Set<ClubCapability> =
        capabilitiesByRole[role] ?: emptySet()

    /** Есть ли у роли данное право. */
    fun roleHasCapability(role: MembershipRole, capability: ClubCapability): Boolean =
        capabilitiesFor(role).contains(capability)
}

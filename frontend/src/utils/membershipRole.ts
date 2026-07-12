import type { MembershipRole } from '../types/api';

// Русские подписи ролей клуба — бейджи ростера и карточки участника (PO №4: владелец остаётся
// «Организатор», со-организатор помечается отдельным бейджем).
export const ROLE_LABELS: Record<MembershipRole, string> = {
  member: 'Участник',
  organizer: 'Организатор',
  co_organizer: 'Со-организатор',
};

/** Type guard: строка из API — валидная роль клуба. */
export function isMembershipRole(role: string | null | undefined): role is MembershipRole {
  return role === 'member' || role === 'organizer' || role === 'co_organizer';
}

/**
 * Менеджер клуба: владелец (role = organizer) или со-организатор. Единый предикат для гейтинга
 * управляющих экранов на фронте (таб «Управление», инбокс/hot actions, «+» создания активностей) —
 * зеркалит серверный гейт «owner ИЛИ active co_organizer». Owner-only поверхности (смена ролей,
 * чат-линк, удаление клуба, СБП-реквизиты, биллинг) этим предикатом НЕ гейтятся — только по
 * `club.ownerId === user.id` / role === 'organizer'.
 */
export function isManagerRole(role: string | null | undefined): boolean {
  return role === 'organizer' || role === 'co_organizer';
}

/** Подпись роли для UI; неожиданные значения с бэкенда откатываются в «Участник». */
export function membershipRoleLabel(role: string | null | undefined): string {
  return isMembershipRole(role) ? ROLE_LABELS[role] : ROLE_LABELS.member;
}

/**
 * Менеджерское членство с ДЕЙСТВУЮЩИМИ правами: владелец — всегда; со-организатор — только при
 * активном членстве. Fail-close, зеркалит бэкенд: замороженный/просроченный со-орг мгновенно
 * теряет manager-права (бейдж роли остаётся, но «Управление» и hot actions скрываются) и получает
 * их обратно после разморозки без повторного назначения.
 */
export function isActiveManagerMembership(
  membership: { role: string; status: string } | null | undefined,
): boolean {
  if (!membership) return false;
  if (membership.role === 'organizer') return true;
  return membership.role === 'co_organizer' && membership.status === 'active';
}

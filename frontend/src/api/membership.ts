import { apiClient } from './apiClient';
import type {
  AwardDto,
  AwardSuggestionDto,
  GamificationDto,
  LeavePreviewDto,
  MemberListItemDto,
  MembershipDto,
  MemberProfileDto,
  MyReputationDto,
  OrganizerDuesMemberDto,
  PendingApplicationDto,
  PendingApplicationsCountDto,
} from '../types/api';

export interface ApplicationDto {
  id: string;
  userId: string;
  clubId: string;
  status: string;
  answerText: string | null;
  rejectedReason: string | null;
  createdAt: string | null;
}

/**
 * Вступить в открытый клуб. De-Stars Slice 2: всегда 201 + MembershipDto. В платном клубе
 * membership попадает в `frozen` (нет доступа к контенту, пока организатор не подтвердит
 * офлайн-взнос); в бесплатном — сразу `active`. Инвойс Stars больше не создаётся.
 */
export function joinClub(clubId: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/clubs/${clubId}/join`);
}

/**
 * Отменить membership вызывающего в [clubId]. Поведение бэкенда зависит от
 * `subscription_price` клуба:
 *  - Бесплатный клуб → немедленная каскадная отмена (status=cancelled, member_count-=1,
 *                      активные складчины/отклики на события очищаются).
 *  - Платный клуб    → выключение автопродления (status=cancelled, доступ сохраняется до
 *                      `subscription_expires_at`, без каскада, member_count не меняется).
 * Ошибки:
 *  - 400 «Owner cannot leave the club»
 *  - 404 «Membership not found» (не участник, либо уже отменено/истекло).
 * См. docs/modules/club-leave.md.
 */
export function leaveClub(clubId: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/clubs/${clubId}/leave`);
}

/**
 * Превью перед выходом: сколько открытых обязательств (подтверждённые брони + pending
 * репутационные складчины) сломает выход из бесплатного клуба. Формирует предупреждающую
 * строку в диалоге подтверждения выхода. Платные клубы возвращают всё нулями (ничего не
 * ломается, пока не истечёт подписка). См. docs/modules/club-leave.md § "выход-с-обязательствами".
 */
export function getLeavePreview(clubId: string): Promise<LeavePreviewDto> {
  return apiClient.get<LeavePreviewDto>(`/api/clubs/${clubId}/leave-preview`);
}

export function applyToClub(clubId: string, answerText: string): Promise<ApplicationDto> {
  return apiClient.post<ApplicationDto>(`/api/clubs/${clubId}/apply`, { answerText });
}

/**
 * «Расширить клуб и принять всех» (club-invites): атомарно поднять лимит участников и
 * одобрить перечисленные pending-заявки полного клуба. Только владелец; 400 — лимит
 * не больше текущего или не вмещает всех.
 */
export function expandAndApprove(
  clubId: string,
  newMemberLimit: number,
  applicationIds: string[],
): Promise<ApplicationDto[]> {
  return apiClient.post<ApplicationDto[]>(`/api/clubs/${clubId}/expand-and-approve`, {
    newMemberLimit,
    applicationIds,
  });
}

export function getMyApplications(): Promise<ApplicationDto[]> {
  return apiClient.get<ApplicationDto[]>('/api/users/me/applications');
}

/** Вступить по инвайт-коду. Тот же de-Stars контракт, что у {@link joinClub}: 201 + MembershipDto,
 *  платные клубы попадают в `frozen`. */
export function joinByInviteCode(code: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/invite/${code}/join`);
}

export function getClubMembers(clubId: string): Promise<MemberListItemDto[]> {
  return apiClient.get<MemberListItemDto[]>(`/api/clubs/${clubId}/members`);
}

/**
 * Управление доступом организатором (de-Stars Slice 2). Оба — только для владельца
 * (`@RequiresOrganizer`), возвращают обновлённый `MembershipDto`, 409 при проигранной гонке
 * перехода статуса.
 *  - dues-paid : «Взнос получен» — открыть доступ + продлить платное окно +30d от max(now, текущий конец).
 *  - dues-unpaid: снять отметку о взносе (не трогает доступ/окно).
 * Клиенты /freeze и /unfreeze удалены (PO 2026-07-06) — просрочку окна закрывает шедулер, ручная
 * пауза убрана из UI; бэкенд-эндпоинты пока живы до переосмысления freeze-флоу.
 */
export function markMemberDuesPaid(clubId: string, userId: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/clubs/${clubId}/members/${userId}/dues-paid`);
}

export function unmarkMemberDues(clubId: string, userId: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/clubs/${clubId}/members/${userId}/dues-unpaid`);
}

/**
 * De-Stars B+C: отклонить платное вступление (вместо «Взнос получен»). Только владелец;
 * убирает frozen-участника. Возврат денег — офлайн-ответственность организатора, платформа
 * вне потока денег. Участник уведомляется (best-effort DM). Причина опциональна.
 */
export function rejectMember(clubId: string, userId: string, reason?: string | null): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/clubs/${clubId}/members/${userId}/reject-dues`, { reason: reason ?? null });
}

/** Исключение организатором: убрать участника из клуба (причина обязательна, ≥5 символов, шлётся DM участнику). */
export function removeMember(clubId: string, userId: string, reason: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/clubs/${clubId}/members/${userId}/remove`, { reason });
}

/**
 * Админ-профиль участника (S1), только владелец:
 *  - setMemberAccessUntil — задать кастомный конец окна доступа («своя дата»); `until` — ISO datetime,
 *    должен быть в будущем (бэкенд отклоняет прошедшие даты).
 *  - updateMemberNote — установить/очистить приватную заметку организатора (null/пусто — очищает).
 */
export function setMemberAccessUntil(clubId: string, userId: string, until: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/clubs/${clubId}/members/${userId}/access-until`, { until });
}

export function updateMemberNote(clubId: string, userId: string, note: string | null): Promise<MembershipDto> {
  return apiClient.patch<MembershipDto>(`/api/clubs/${clubId}/members/${userId}/note`, { note });
}

/**
 * Админ-профиль участника (S2), локальные для клуба награды «как интересы», только владелец:
 *  - grantMemberAward — выдать награду (emoji + подпись, ≤40 символов); 400 при дублирующейся
 *    подписи / достижении лимита (6).
 *  - revokeMemberAward — убрать награду по id.
 *  - getAwardSuggestions — уникальные прошлые награды в клубе, питают автокомплит формы выдачи.
 * Сами награды публичны на карточке участника (возвращаются в MemberProfileDto.awards, R3).
 */
export function grantMemberAward(
  clubId: string,
  userId: string,
  emoji: string,
  label: string,
): Promise<AwardDto> {
  return apiClient.post<AwardDto>(`/api/clubs/${clubId}/members/${userId}/awards`, { emoji, label });
}

export function revokeMemberAward(clubId: string, userId: string, awardId: string): Promise<void> {
  return apiClient.delete(`/api/clubs/${clubId}/members/${userId}/awards/${awardId}`);
}

export function getAwardSuggestions(clubId: string): Promise<AwardSuggestionDto[]> {
  return apiClient.get<AwardSuggestionDto[]>(`/api/clubs/${clubId}/award-suggestions`);
}

/** Роль, которую владелец может назначить участнику: значение "organizer" запрещено бэкендом
 *  (передача владения — вне скоупа co-organizers, см. club-leave PR-2). */
export type AssignableMemberRole = 'member' | 'co_organizer';

/**
 * Смена роли участника (co-organizers) — ТОЛЬКО владелец клуба:
 *  - `co_organizer` — промоут: требует активного membership у target'а (400 для frozen/expired),
 *    лимит со-оргов на клуб — 5 (400 при превышении);
 *  - `member` — демоут при любом статусе target'а.
 * 400 — self/владелец-target/role=organizer; 403 — вызывающий не владелец (со-орг тоже);
 * 404 — нет клуба/membership; 409 — параллельное изменение роли (кэш уже инвалидируется).
 * Идемпотентно: повторный промоут/демоут → 200 no-op.
 */
export function updateMemberRole(
  clubId: string,
  userId: string,
  role: AssignableMemberRole,
): Promise<MembershipDto> {
  return apiClient.put<MembershipDto>(`/api/clubs/${clubId}/members/${userId}/role`, { role });
}

/**
 * De-Stars: участник заявляет, что оплатил офлайн-взнос (самообслуживание участника, без гейта
 * владельца). method = 'sbp' (proofUrl = URL загруженного скриншота, обязателен) или 'cash'
 * (без доказательства). Создаёт claim, который проверяет организатор; доступ всё равно
 * открывается только когда организатор нажмёт «Взнос получен».
 */
export function claimDues(
  clubId: string,
  method: 'sbp' | 'cash',
  proofUrl?: string | null,
): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/clubs/${clubId}/dues-claim`, { method, proofUrl: proofUrl ?? null });
}

/** Кросс-клубовое «Ждут оплаты»: участники без доступа (frozen/expired) по всем клубам
 *  вызывающего-владельца. Пусто для не-владельцев. */
export function getOrganizerAwaitingDues(): Promise<OrganizerDuesMemberDto[]> {
  return apiClient.get<OrganizerDuesMemberDto[]>('/api/users/me/organizer/awaiting-dues');
}

export function getMemberProfile(clubId: string, userId: string): Promise<MemberProfileDto> {
  return apiClient.get<MemberProfileDto>(`/api/clubs/${clubId}/members/${userId}`);
}

export function getMyReputation(): Promise<MyReputationDto> {
  return apiClient.get<MyReputationDto>('/api/users/me/reputation');
}

/** Панель геймификации собственного профиля: XP, уровень + прогресс, бейджи. См. reputation-v2.md §H3. */
export function getMyGamification(): Promise<GamificationDto> {
  return apiClient.get<GamificationDto>('/api/users/me/gamification');
}

export function approveApplication(applicationId: string): Promise<void> {
  return apiClient.post(`/api/applications/${applicationId}/approve`);
}

/**
 * Отклонить заявку в статусе pending. Бэкенд теперь требует непустой `reason`
 * (5–500 символов после trim) — см. docs/modules/applications-inbox.md.
 */
export function rejectApplication(applicationId: string, reason: string): Promise<void> {
  return apiClient.post(`/api/applications/${applicationId}/reject`, { reason });
}

/** Самостоятельный отзыв заявителем своей pending-заявки → status `cancelled`. */
export function cancelApplication(applicationId: string): Promise<ApplicationDto> {
  return apiClient.post<ApplicationDto>(`/api/applications/${applicationId}/cancel`);
}

export function getMyPendingApplications(): Promise<PendingApplicationDto[]> {
  return apiClient.get<PendingApplicationDto[]>('/api/users/me/applications-pending');
}

/**
 * Счётчик, который управляет точкой на табе «Мои клубы»: pending-заявки на стороне организатора (`inboxCount`).
 */
export function getMyClubsActionCounts(): Promise<PendingApplicationsCountDto> {
  return apiClient.get<PendingApplicationsCountDto>(
    '/api/users/me/applications-pending-count',
  );
}

/**
 * Финализирует membership бесплатного клуба для одобренной заявки, застрявшей в
 * состоянии "approved-without-membership" (старые данные / прежний баг одобрения
 * бесплатного клуба). Бэкенд проверяет, что заявка одобрена И subscription_price
 * клуба <= 0 И нет активного/grace membership. При успехе возвращает
 * только что созданный MembershipDto.
 */
export function completeFreeMembership(applicationId: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(
    `/api/applications/${applicationId}/complete-free-membership`,
  );
}

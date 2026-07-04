import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import {
  claimDues,
  freezeMember,
  getAwardSuggestions,
  getClubMembers,
  getMemberAttention,
  getMemberProfile,
  getMyGamification,
  getMyReputation,
  getOrganizerAwaitingDues,
  grantMemberAward,
  markMemberDuesPaid,
  rejectMember,
  removeMember,
  revokeMemberAward,
  setMemberAccessUntil,
  unfreezeMember,
  unmarkMemberDues,
  updateMemberNote,
} from '../api/membership';
import { queryKeys } from './queryKeys';

/** Участники [clubId]. Организатор дополнительно получает замороженных участников + для каждого
 *  участника состояние доступа и дату, до которой оплачено (де-Stars дашборд); обычные участники
 *  получают список только активных с этими полями null. */
export function useClubMembersQuery(clubId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.clubs.members(clubId ?? ''),
    queryFn: () => getClubMembers(clubId!),
    enabled: Boolean(clubId),
  });
}

/** Лента red-dot: счётчики скоро-истекающих + замороженных-ждущих-оплаты. Только для владельца —
 *  ограничивать через `enabled: isOrganizer`, чтобы не получать гарантированный 403 в контексте
 *  участника/гостя. */
export function useMemberAttentionQuery(
  clubId: string | undefined,
  options: { enabled?: boolean } = {},
) {
  const enabled = Boolean(clubId) && (options.enabled ?? true);
  return useQuery({
    queryKey: queryKeys.clubs.memberAttention(clubId ?? ''),
    queryFn: () => getMemberAttention(clubId!),
    enabled,
    staleTime: 60_000,
  });
}

/** Кросс-клубовое «Ждут оплаты»: замороженные участники по всем клубам вызывающего. Возвращает []
 *  для не-владельцев (сервер фильтрует по владению) — ограничивать `enabled` на владение ≥1 клубом,
 *  чтобы избежать лишнего запроса. */
export function useOrganizerAwaitingDuesQuery(options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.organizer.awaitingDues,
    queryFn: getOrganizerAwaitingDues,
    enabled: options.enabled ?? true,
    staleTime: 60_000,
  });
}

/** После любого действия с доступом меняются: список участников (бейджи/корзины), счётчик
 *  red-dot по клубу и кросс-клубовая лента «Ждут оплаты». */
function invalidateAfterMemberGateAction(qc: QueryClient, clubId: string) {
  qc.invalidateQueries({ queryKey: queryKeys.clubs.members(clubId) });
  qc.invalidateQueries({ queryKey: queryKeys.clubs.memberAttention(clubId) });
  qc.invalidateQueries({ queryKey: queryKeys.organizer.awaitingDues });
}

interface MemberGateArgs {
  clubId: string;
  userId: string;
}

/** «Взнос получен» — открыть доступ + продлить оплаченный период. Основное действие дашборда. */
export function useMarkMemberDuesPaidMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId }: MemberGateArgs) => markMemberDuesPaid(clubId, userId),
    onSuccess: (_data, { clubId }) => invalidateAfterMemberGateAction(qc, clubId),
  });
}

/** «Закрыть доступ» — active → frozen. */
export function useFreezeMemberMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId }: MemberGateArgs) => freezeMember(clubId, userId),
    onSuccess: (_data, { clubId }) => invalidateAfterMemberGateAction(qc, clubId),
  });
}

/** Заново открыть доступ без продления оплаченного периода (frozen → active). */
export function useUnfreezeMemberMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId }: MemberGateArgs) => unfreezeMember(clubId, userId),
    onSuccess: (_data, { clubId }) => invalidateAfterMemberGateAction(qc, clubId),
  });
}

/** B+C: отказать в платном вступлении (возврат оффлайн) — удаляет замороженного участника.
 *  Те же инвалидации, что и у остальных действий с доступом (корзины + red-dot + кросс-клубовое
 *  «Ждут оплаты»). */
export function useRejectMemberMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId, reason }: MemberGateArgs & { reason?: string | null }) =>
      rejectMember(clubId, userId, reason),
    onSuccess: (_data, { clubId }) => invalidateAfterMemberGateAction(qc, clubId),
  });
}

/** Кик от организатора: удалить участника из клуба (причина обязательна). Те же инвалидации, что
 *  и у остальных действий с доступом (корзины ростера + red-dot + кросс-клубовое «Ждут оплаты»). */
export function useRemoveMemberMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId, reason }: MemberGateArgs & { reason: string }) =>
      removeMember(clubId, userId, reason),
    onSuccess: (_data, { clubId }) => invalidateAfterMemberGateAction(qc, clubId),
  });
}

/** Снять отметку взноса, не трогая доступ/период. */
export function useUnmarkMemberDuesMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId }: MemberGateArgs) => unmarkMemberDues(clubId, userId),
    onSuccess: (_data, { clubId }) => invalidateAfterMemberGateAction(qc, clubId),
  });
}

/** Member admin S1: задать произвольную дату конца доступа («своя дата»). Затрагивает доступ →
 *  инвалидировать корзины + карточку профиля участника (её строку «Подписка до …»). */
export function useSetMemberAccessUntilMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId, until }: MemberGateArgs & { until: string }) =>
      setMemberAccessUntil(clubId, userId, until),
    onSuccess: (_data, { clubId, userId }) => {
      invalidateAfterMemberGateAction(qc, clubId);
      qc.invalidateQueries({ queryKey: queryKeys.clubs.memberProfile(clubId, userId) });
    },
  });
}

/** Member admin S1: задать/очистить приватную заметку организатора. Хранится только в карточке профиля. */
export function useUpdateMemberNoteMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId, note }: MemberGateArgs & { note: string | null }) =>
      updateMemberNote(clubId, userId, note),
    onSuccess: (_data, { clubId, userId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.memberProfile(clubId, userId) });
    },
  });
}

/** Member admin S2: выдать клубную награду. Обновляет карточку участника (её чипы) + пул подсказок
 *  наград клуба (новый label становится будущим вариантом автодополнения). */
export function useGrantMemberAwardMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId, emoji, label }: MemberGateArgs & { emoji: string; label: string }) =>
      grantMemberAward(clubId, userId, emoji, label),
    onSuccess: (_data, { clubId, userId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.memberProfile(clubId, userId) });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.awardSuggestions(clubId) });
    },
  });
}

/** Member admin S2: отозвать клубную награду. Хранится только в карточке участника. */
export function useRevokeMemberAwardMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId, awardId }: MemberGateArgs & { awardId: string }) =>
      revokeMemberAward(clubId, userId, awardId),
    onSuccess: (_data, { clubId, userId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.memberProfile(clubId, userId) });
    },
  });
}

/** Пул автодополнения для формы выдачи. Только для организатора — ограничивать `enabled`
 *  открытием формы редактирования, чтобы контекст участника/гостя никогда не ловил гарантированный 403. */
export function useAwardSuggestionsQuery(
  clubId: string | undefined,
  options: { enabled?: boolean } = {},
) {
  const enabled = Boolean(clubId) && (options.enabled ?? true);
  return useQuery({
    queryKey: queryKeys.clubs.awardSuggestions(clubId ?? ''),
    queryFn: () => getAwardSuggestions(clubId!),
    enabled,
    staleTime: 60_000,
  });
}

/** Участник заявляет, что оплатил взнос (sbp + URL скриншота, либо cash). Обновляет «Мои клубы»,
 *  чтобы экран замороженного клуба переключился на «оплата на проверке». */
export function useClaimDuesMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, method, proofUrl }: { clubId: string; method: 'sbp' | 'cash'; proofUrl?: string | null }) =>
      claimDues(clubId, method, proofUrl),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.my() });
    },
  });
}

export function useMemberProfileQuery(
  clubId: string | undefined,
  userId: string | undefined,
) {
  return useQuery({
    queryKey: queryKeys.clubs.memberProfile(clubId ?? '', userId ?? ''),
    queryFn: () => getMemberProfile(clubId!, userId!),
    enabled: Boolean(clubId) && Boolean(userId),
  });
}

/** Обзор репутации авторизованного пользователя: глобальный агрегат «N из M» + Trust по клубам,
 *  разбит на активные клубы и «История» (покинутые клубы, где ещё сохраняется трек-рекорд). */
export function useMyReputationQuery() {
  return useQuery({
    queryKey: queryKeys.clubs.myReputation(),
    queryFn: getMyReputation,
  });
}

/** Панель геймификации авторизованного пользователя (XP, уровень, прогресс, бейджи). Только self-вид. */
export function useMyGamificationQuery() {
  return useQuery({
    queryKey: queryKeys.clubs.myGamification(),
    queryFn: getMyGamification,
  });
}

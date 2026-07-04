import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  approveApplication,
  cancelApplication,
  completeFreeMembership,
  getMyApplications,
  getMyClubsActionCounts,
  getMyPendingApplications,
  rejectApplication,
} from '../api/membership';
import { queryKeys } from './queryKeys';

export function useMyApplicationsQuery() {
  return useQuery({
    queryKey: queryKeys.applications.mine(),
    queryFn: getMyApplications,
  });
}

/**
 * Все ожидающие заявки по всем клубам, которыми владеет вызывающий — используется
 * кросс-клубовым инбоксом организатора на MyClubsPage. Зеркалит облегчённый
 * запрос-счётчик ниже.
 */
export function useMyPendingApplicationsQuery() {
  return useQuery({
    queryKey: queryKeys.applications.myPending,
    queryFn: getMyPendingApplications,
    staleTime: 60_000,
  });
}

/**
 * Счётчик для точки-индикатора на табе «Мои клубы»: ожидающие заявки на стороне
 * организатора (`{ inboxCount }`). Один вызов бэкенда, один слот кэша, зеркалит
 * useSkladchinaActionRequiredCountQuery.
 */
export function useMyClubsActionCountsQuery() {
  return useQuery({
    queryKey: queryKeys.applications.myPendingActionCounts,
    queryFn: getMyClubsActionCounts,
    staleTime: 60_000,
  });
}

interface ApproveApplicationArgs {
  applicationId: string;
  clubId: string;
}

export function useApproveApplicationMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ applicationId }: ApproveApplicationArgs) => approveApplication(applicationId),
    onSuccess: (_data, { clubId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.applications(clubId) });
      qc.invalidateQueries({
        queryKey: queryKeys.clubs.applications(clubId, 'pending'),
      });
      qc.invalidateQueries({ queryKey: queryKeys.applications.mine() });
      qc.invalidateQueries({ queryKey: queryKeys.applications.myPending });
      qc.invalidateQueries({ queryKey: queryKeys.applications.myPendingActionCounts });
      // Одобрение заявки в платный клуб теперь сразу создаёт membership (в статусе `frozen`),
      // поэтому список участников клуба (дашборд организатора) обновляется, чтобы показать новую строку.
      qc.invalidateQueries({ queryKey: queryKeys.clubs.members(clubId) });
      // …и кросс-клубовый блок «Оплата вступления» на «Мои клубы» — новый frozen-участник должен
      // появиться и там, а не только в Управление → Участники (иначе там свой 60-сек staleTime).
      qc.invalidateQueries({ queryKey: queryKeys.organizer.awaitingDues });
    },
  });
}

interface RejectApplicationArgs {
  applicationId: string;
  clubId: string;
  reason: string;
}

export function useRejectApplicationMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ applicationId, reason }: RejectApplicationArgs) =>
      rejectApplication(applicationId, reason),
    onSuccess: (_data, { clubId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.applications(clubId) });
      qc.invalidateQueries({
        queryKey: queryKeys.clubs.applications(clubId, 'pending'),
      });
      qc.invalidateQueries({ queryKey: queryKeys.applications.mine() });
      qc.invalidateQueries({ queryKey: queryKeys.applications.myPending });
      qc.invalidateQueries({ queryKey: queryKeys.applications.myPendingActionCounts });
    },
  });
}

interface CancelApplicationArgs {
  applicationId: string;
}

/** Заявитель отзывает свою ожидающую заявку (→ статус `cancelled`). Обновляем кэши самого
 *  заявителя, чтобы карточка исчезла из «Мои заявки» и обновился счётчик точки на табе. */
export function useCancelApplicationMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ applicationId }: CancelApplicationArgs) => cancelApplication(applicationId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: queryKeys.applications.mine() });
      qc.invalidateQueries({ queryKey: queryKeys.applications.myPendingActionCounts });
    },
  });
}

interface CompleteFreeMembershipArgs {
  applicationId: string;
  clubId: string;
}

/**
 * Завершает зависшую одобренную заявку в бесплатный клуб, создавая недостающий
 * membership. После успеха: перезапрашиваем клубы вызывающего (появляется новый
 * membership), детали клуба (memberCount увеличен) и все кэши заявок, чтобы
 * зависший CTA исчез.
 */
export function useCompleteFreeMembershipMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ applicationId }: CompleteFreeMembershipArgs) =>
      completeFreeMembership(applicationId),
    onSuccess: (_data, { clubId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.applications.mine() });
      qc.invalidateQueries({ queryKey: queryKeys.applications.myPendingActionCounts });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.my() });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.detail(clubId) });
    },
  });
}

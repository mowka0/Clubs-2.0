import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  addSkladchinaDebtor,
  cancelSkladchina,
  closeSkladchina,
  contributeSkladchina,
  createSkladchina,
  getEventSplitState,
  getMySkladchinas,
  getSkladchina,
  getSkladchinaActionRequiredCount,
  getSplittableEvents,
  joinSkladchina,
  leaveSkladchina,
  lockSkladchina,
  orderSkladchina,
  replaceSkladchinaDebtor,
} from '../api/skladchina';
import type { CreateSkladchinaRequest, SkladchinaDetailDto } from '../types/api';
import { queryKeys } from './queryKeys';

const PAGE_SIZE = 20;

export function useMySkladchinasQuery() {
  return useInfiniteQuery({
    queryKey: queryKeys.skladchinas.myFeed,
    queryFn: ({ pageParam }) => getMySkladchinas({ page: pageParam, size: PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
  });
}

/**
 * Сколько сборов ждут действия пользователя (открытый долг как должника или «Отдал», ждущий его
 * ответа). Питает точку на нижней навигации «Активности». Лёгкий запрос (один COUNT).
 */
export function useSkladchinaActionRequiredCountQuery() {
  return useQuery({
    queryKey: queryKeys.skladchinas.actionRequiredCount,
    queryFn: getSkladchinaActionRequiredCount,
    select: (data) => data.count,
    staleTime: 60_000,
  });
}

/** Встречи, по которым ещё можно скинуться. Фильтры (явка, ≥2 пришедших, ≤30 дней, без сбора) — на бэкенде. */
export function useSplittableEventsQuery(clubId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.skladchinas.splittableEvents(clubId ?? ''),
    queryFn: () => getSplittableEvents(clubId!),
    enabled: Boolean(clubId),
  });
}

export function useSkladchinaQuery(id: string | undefined) {
  return useQuery({
    queryKey: queryKeys.skladchinas.detail(id ?? ''),
    queryFn: () => getSkladchina(id!),
    enabled: Boolean(id),
  });
}

/** Существующий сбор по встрече — управляет кнопкой EventPage «Скинуться». */
export function useEventSplitStateQuery(eventId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.skladchinas.eventState(eventId ?? ''),
    queryFn: () => getEventSplitState(eventId!),
    enabled: Boolean(eventId),
  });
}

interface CreateSkladchinaArgs {
  clubId: string;
  body: CreateSkladchinaRequest;
}

export function useCreateSkladchinaMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, body }: CreateSkladchinaArgs) => createSkladchina(clubId, body),
    onSuccess: (_data, { clubId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.all });
      qc.invalidateQueries({ queryKey: queryKeys.debts.all });
      // Единый фид активностей тоже должен обновиться — новый сбор появляется сверху.
      qc.invalidateQueries({ queryKey: queryKeys.activities.byClubAll(clubId) });
    },
  });
}

/** Действия над сбором (§ 7): участник — «В деле» / «Беру» / «Перевёл» / «Передумал»; создатель — стадии и список. */
export type SkladchinaAction =
  | { type: 'join'; note?: string | null }
  | { type: 'leave' }
  | { type: 'contribute'; amountKopecks: number }
  | { type: 'lock' }
  | { type: 'order' }
  | { type: 'close' }
  | { type: 'cancel' }
  | { type: 'addDebtor'; userId: string; amountKopecks?: number | null }
  | { type: 'replace'; debtId: string; userId: string };

function runSkladchinaAction(id: string, action: SkladchinaAction): Promise<SkladchinaDetailDto> {
  switch (action.type) {
    case 'join': return joinSkladchina(id, action.note);
    case 'leave': return leaveSkladchina(id);
    case 'contribute': return contributeSkladchina(id, action.amountKopecks);
    case 'lock': return lockSkladchina(id);
    case 'order': return orderSkladchina(id);
    case 'close': return closeSkladchina(id);
    case 'cancel': return cancelSkladchina(id);
    case 'addDebtor': return addSkladchinaDebtor(id, action.userId, action.amountKopecks);
    case 'replace': return replaceSkladchinaDebtor(id, action.debtId, action.userId);
  }
}

/** Одна мутация на все действия сбора: ответ всегда деталка, инвалидация одинаковая. */
export function useSkladchinaActionMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: SkladchinaAction }) => runSkladchinaAction(id, action),
    onSuccess: (data) => {
      qc.setQueryData(queryKeys.skladchinas.detail(data.id), data);
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.actionRequiredCount });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.byClubActive(data.clubId) });
      qc.invalidateQueries({ queryKey: queryKeys.debts.all });
      qc.invalidateQueries({ queryKey: queryKeys.activities.byClubAll(data.clubId) });
    },
  });
}

import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  confirmSkladchinaPayments,
  createSkladchina,
  declineSkladchina,
  disputeSkladchinaPayment,
  getEventSplitState,
  getMySkladchinas,
  getSkladchina,
  getSkladchinaActionRequiredCount,
  getSplittableEvents,
  markPaidSkladchina,
  organizerMarkPaidParticipant,
  organizerUnmarkParticipant,
  requestDeclineSkladchina,
  resolveDeclineSkladchina,
  resolveSkladchinaPayment,
  unmarkOwnPayment,
} from '../api/skladchina';
import type { CreateSkladchinaRequest } from '../types/api';
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
 * Число активных складчин, которые пользователю ещё нужно оплатить. Питает бейдж
 * таба «Сборы» + точку на нижней навигации «Активности», чтобы неоплаченные
 * обязательства были видны отовсюду. Лёгкий запрос (один COUNT), выполняется
 * везде, где живёт навигация.
 */
export function useSkladchinaActionRequiredCountQuery() {
  return useQuery({
    queryKey: queryKeys.skladchinas.actionRequiredCount,
    queryFn: getSkladchinaActionRequiredCount,
    select: (data) => data.count,
    staleTime: 60_000,
  });
}

/**
 * Встречи, по которым счёт ещё можно разделить. Фильтры (явка отмечена, минимум двое пришедших,
 * не старше 30 дней, счёт ещё не делили) живут на бэкенде — фронт не дублирует правила сплита.
 */
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

/** Существующий сплит для события — управляет кнопкой EventPage «Разделить счёт». */
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
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.byClubActive(clubId) });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.actionRequiredCount });
      // Единый фид активностей тоже должен обновиться — новая складчина
      // должна появиться сверху во всех вариантах фильтра таба управления.
      qc.invalidateQueries({ queryKey: queryKeys.activities.byClubAll(clubId) });
    },
  });
}

interface MarkPaidArgs {
  id: string;
  // A-1: не передаётся для фиксированных режимов (сервер сам записывает долю); для voluntary — заявленная сумма.
  declaredAmountKopecks?: number | null;
}

export function useMarkPaidMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, declaredAmountKopecks }: MarkPaidArgs) =>
      markPaidSkladchina(id, declaredAmountKopecks),
    onSuccess: (_data, { id }) => {
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.detail(id) });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.actionRequiredCount });
    },
  });
}

interface OrganizerParticipantArgs {
  id: string;
  userId: string;
}

/** A-2: организатор отмечает участника оплатившим / отменяет отметку. Общая инвалидация для обоих случаев. */
function invalidateAfterOrganizerAction(qc: ReturnType<typeof useQueryClient>, id: string) {
  qc.invalidateQueries({ queryKey: queryKeys.skladchinas.detail(id) });
  qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
  // Обязательство «требуется действие» у отмеченного участника изменилось — обновляем счётчик бейджа.
  qc.invalidateQueries({ queryKey: queryKeys.skladchinas.actionRequiredCount });
}

export function useOrganizerMarkPaidMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, userId }: OrganizerParticipantArgs) => organizerMarkPaidParticipant(id, userId),
    onSuccess: (_data, { id }) => invalidateAfterOrganizerAction(qc, id),
  });
}

export function useOrganizerUnmarkMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, userId }: OrganizerParticipantArgs) => organizerUnmarkParticipant(id, userId),
    onSuccess: (_data, { id }) => invalidateAfterOrganizerAction(qc, id),
  });
}

/** V28: участник запрашивает отказ от счёта с указанием причины (split_bill). */
export function useRequestDeclineMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => requestDeclineSkladchina(id, reason),
    onSuccess: (_data, { id }) => {
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.detail(id) });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
    },
  });
}

/** V28/V29: организатор одобряет/отклоняет запрос участника на отказ (отклонение требует причины). */
export function useResolveDeclineMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, userId, approve, rejectReason }: { id: string; userId: string; approve: boolean; rejectReason?: string }) =>
      resolveDeclineSkladchina(id, userId, approve, rejectReason),
    onSuccess: (_data, { id }) => {
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.detail(id) });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.actionRequiredCount });
    },
  });
}

export function useDeclineSkladchinaMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => declineSkladchina(id),
    onSuccess: (_data, id) => {
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.detail(id) });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.actionRequiredCount });
    },
  });
}

/** V89: участник снимает свою отметку об оплате, пока сбор идёт. */
export function useUnmarkOwnPaymentMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => unmarkOwnPayment(id),
    onSuccess: (_data, id) => {
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.detail(id) });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.actionRequiredCount });
    },
  });
}

/** V89: организатор сверил деньги списком и закрыл сбор — меняются и статусы, и итог. */
export function useConfirmPaymentsMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, rejectedUserIds, rejectNotes }: {
      id: string;
      rejectedUserIds: string[];
      rejectNotes?: Record<string, string>;
    }) => confirmSkladchinaPayments(id, rejectedUserIds, rejectNotes),
    onSuccess: (_data, { id }) => {
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.detail(id) });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.all });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.actionRequiredCount });
    },
  });
}

/** V89: участник прикладывает чек к неподтверждённой оплате. */
export function useDisputePaymentMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, receiptUrl, note }: { id: string; receiptUrl: string; note?: string }) =>
      disputeSkladchinaPayment(id, receiptUrl, note),
    onSuccess: (_data, { id }) => {
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.detail(id) });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.actionRequiredCount });
    },
  });
}

/** V89: решение организатора по оплате участника (сверка по ходу сбора или разбор чека). */
export function useResolvePaymentMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, userId, accept, reason }: {
      id: string; userId: string; accept: boolean; reason?: string;
    }) => resolveSkladchinaPayment(id, userId, accept, reason),
    onSuccess: (_data, { id }) => {
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.detail(id) });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.actionRequiredCount });
    },
  });
}

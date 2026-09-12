import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  attachDebtReceipt,
  changeDebtAmount,
  claimDebt,
  confirmDebt,
  confirmSettlement,
  forgiveDebt,
  getDebtPair,
  getDebtsOverview,
  promiseDebt,
  rejectDebt,
  rejectSettlement,
  setDebtNote,
  settleWith,
  unclaimDebt,
} from '../api/debts';
import type { DebtDto, DebtPairDto } from '../types/api';
import { queryKeys } from './queryKeys';

export function useDebtsOverviewQuery(enabled = true) {
  return useQuery({
    queryKey: queryKeys.debts.overview,
    queryFn: getDebtsOverview,
    enabled,
    staleTime: 30_000,
  });
}

export function useDebtPairQuery(userId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.debts.pair(userId ?? ''),
    queryFn: () => getDebtPair(userId!),
    enabled: Boolean(userId),
  });
}

/** Переходы одиночного долга (§ 2.2). Кто может — проверяет сервер, кнопки лишь не показываются. */
export type DebtAction =
  | { type: 'claim' }
  | { type: 'unclaim' }
  | { type: 'confirm' }
  | { type: 'forgive' }
  | { type: 'promise'; date: string }
  | { type: 'reject'; note: string | null }
  | { type: 'amount'; amountKopecks: number }
  | { type: 'receipt'; url: string }
  | { type: 'note'; note: string };

function runDebtAction(debtId: string, action: DebtAction): Promise<DebtDto> {
  switch (action.type) {
    case 'claim': return claimDebt(debtId);
    case 'unclaim': return unclaimDebt(debtId);
    case 'confirm': return confirmDebt(debtId);
    case 'forgive': return forgiveDebt(debtId);
    case 'promise': return promiseDebt(debtId, action.date);
    case 'reject': return rejectDebt(debtId, action.note);
    case 'amount': return changeDebtAmount(debtId, action.amountKopecks);
    case 'receipt': return attachDebtReceipt(debtId, action.url);
    case 'note': return setDebtNote(debtId, action.note);
  }
}

// Долг живёт сразу на трёх экранах (сбор, пара, книга): после любого перехода перечитываем всё.
function invalidateDebtScreens(qc: ReturnType<typeof useQueryClient>) {
  qc.invalidateQueries({ queryKey: queryKeys.debts.all });
  qc.invalidateQueries({ queryKey: queryKeys.skladchinas.all });
}

export function useDebtActionMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ debtId, action }: { debtId: string; action: DebtAction }) => runDebtAction(debtId, action),
    onSuccess: () => invalidateDebtScreens(qc),
  });
}

export type SettlementAction =
  | { type: 'settle'; userId: string }
  | { type: 'confirm'; settlementId: string }
  | { type: 'reject'; settlementId: string };

function runSettlementAction(action: SettlementAction): Promise<DebtPairDto> {
  switch (action.type) {
    case 'settle': return settleWith(action.userId);
    case 'confirm': return confirmSettlement(action.settlementId);
    case 'reject': return rejectSettlement(action.settlementId);
  }
}

export function useSettlementMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (action: SettlementAction) => runSettlementAction(action),
    onSuccess: (data) => {
      qc.setQueryData(queryKeys.debts.pair(data.user.id), data);
      invalidateDebtScreens(qc);
    },
  });
}

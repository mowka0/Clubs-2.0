import { apiClient } from './apiClient';
import type { DebtDto, DebtPairDto, DebtsOverviewDto } from '../types/api';

// Эндпоинты долгов — docs/modules/skladchina-v3.md § 7 «Долги». Стороны долга проверяет сервер.

export function getDebtsOverview(): Promise<DebtsOverviewDto> {
  return apiClient.get<DebtsOverviewDto>('/api/debts');
}

export function getDebtPair(userId: string): Promise<DebtPairDto> {
  return apiClient.get<DebtPairDto>(`/api/debts/with/${userId}`);
}

/** «Оплачу позже» к дате (YYYY-MM-DD). */
export function promiseDebt(id: string, date: string): Promise<DebtDto> {
  return apiClient.post<DebtDto>(`/api/debts/${id}/promise`, { date });
}

/** «Отдал». */
export function claimDebt(id: string): Promise<DebtDto> {
  return apiClient.post<DebtDto>(`/api/debts/${id}/claim`);
}

/** «Отменить» своё «Отдал». */
export function unclaimDebt(id: string): Promise<DebtDto> {
  return apiClient.post<DebtDto>(`/api/debts/${id}/unclaim`);
}

/** «Получил» — получатель. */
export function confirmDebt(id: string): Promise<DebtDto> {
  return apiClient.post<DebtDto>(`/api/debts/${id}/confirm`);
}

/** «Не получил» с заметкой — получатель. */
export function rejectDebt(id: string, note?: string | null): Promise<DebtDto> {
  return apiClient.post<DebtDto>(`/api/debts/${id}/reject`, note ? { note } : {});
}

/** «Простить» — получатель. */
export function forgiveDebt(id: string): Promise<DebtDto> {
  return apiClient.post<DebtDto>(`/api/debts/${id}/forgive`);
}

/** Изменить сумму (получатель, только «Скинуться»). */
export function changeDebtAmount(id: string, amountKopecks: number): Promise<DebtDto> {
  return apiClient.patch<DebtDto>(`/api/debts/${id}`, { amountKopecks });
}

/** Чек должника — только URL нашей загрузки. */
export function attachDebtReceipt(id: string, url: string): Promise<DebtDto> {
  return apiClient.post<DebtDto>(`/api/debts/${id}/receipt`, { url });
}

/** Заметка должника («не согласен с суммой»). */
export function setDebtNote(id: string, note: string): Promise<DebtDto> {
  return apiClient.post<DebtDto>(`/api/debts/${id}/note`, { note });
}

/** Сальдо «Отдал Σ» — по всем открытым долгам пары разом. */
export function settleWith(userId: string): Promise<DebtPairDto> {
  return apiClient.post<DebtPairDto>(`/api/debts/with/${userId}/settle`);
}

export function confirmSettlement(id: string): Promise<DebtPairDto> {
  return apiClient.post<DebtPairDto>(`/api/debts/settlements/${id}/confirm`);
}

export function rejectSettlement(id: string): Promise<DebtPairDto> {
  return apiClient.post<DebtPairDto>(`/api/debts/settlements/${id}/reject`);
}

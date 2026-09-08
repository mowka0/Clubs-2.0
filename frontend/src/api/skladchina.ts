import { apiClient } from './apiClient';
import type {
  ActionRequiredCountDto,
  CreateSkladchinaRequest,
  EventSplitStateDto,
  MySkladchinaListItemDto,
  PageResponse,
  SkladchinaDetailDto,
  SplittableEventDto,
} from '../types/api';

export function getSkladchina(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.get<SkladchinaDetailDto>(`/api/skladchinas/${id}`);
}

// Кнопка «Разделить счёт» на EventPage: существующий сплит события (active → open, closed_success → collected).
export function getEventSplitState(eventId: string): Promise<EventSplitStateDto> {
  return apiClient.get<EventSplitStateDto>(`/api/events/${eventId}/skladchina`);
}

// Шаг «выберите встречу» формы сплита: бэкенд отдаёт только те встречи, по которым сбор создастся.
export function getSplittableEvents(clubId: string): Promise<SplittableEventDto[]> {
  return apiClient.get<SplittableEventDto[]>(`/api/clubs/${clubId}/skladchinas/splittable-events`);
}

export function getMySkladchinas(
  params?: { page?: number; size?: number },
): Promise<PageResponse<MySkladchinaListItemDto>> {
  const queryParams: Record<string, string> = {};
  if (params?.page !== undefined) queryParams.page = String(params.page);
  if (params?.size !== undefined) queryParams.size = String(params.size);
  return apiClient.get<PageResponse<MySkladchinaListItemDto>>(`/api/users/me/skladchinas`, queryParams);
}

export function getSkladchinaActionRequiredCount(): Promise<ActionRequiredCountDto> {
  return apiClient.get<ActionRequiredCountDto>('/api/users/me/skladchinas/action-required-count');
}

export function createSkladchina(
  clubId: string,
  body: CreateSkladchinaRequest,
): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/clubs/${clubId}/skladchinas`, body);
}

export function markPaidSkladchina(
  id: string,
  declaredAmountKopecks?: number | null,
): Promise<SkladchinaDetailDto> {
  // A-1: fixed-режимы сумму не шлют (сервер записывает назначенную долю);
  // voluntary шлёт сумму, заявленную пользователем.
  const body = declaredAmountKopecks != null ? { declaredAmountKopecks } : {};
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/mark-paid`, body);
}

export function declineSkladchina(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/decline`);
}

// V28: участник открывает запрос на отказ с указанием причины (шаблоны REQUIRES_APPROVAL).
export function requestDeclineSkladchina(id: string, reason: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/request-decline`, { reason });
}

// V28/V29: организатор одобряет/отклоняет запрос участника на отказ. Для отклонения нужна причина.
export function resolveDeclineSkladchina(
  id: string,
  userId: string,
  approve: boolean,
  rejectReason?: string,
): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(
    `/api/skladchinas/${id}/participants/${userId}/resolve-decline`,
    { approve, rejectReason },
  );
}

// V89: участник снимает СВОЮ отметку об оплате, пока сбор идёт («ошибся, платил не по этому сбору»).
export function unmarkOwnPayment(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/unmark-paid`);
}

// V89: «Засчитать всех» — организатор подтверждает разом все неразобранные заявки.
// Отдельного закрытия не нужно: сбор закроется сам, когда разбирать станет нечего.
export function confirmAllSkladchinaPayments(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/confirm-all`);
}

// Закрыть сбор «как есть»: неразобранные заявки останутся нейтральными.
export function closeSkladchina(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/close`);
}

// V89: участник оспаривает отклонение, приложив фото или скриншот чека.
export function disputeSkladchinaPayment(
  id: string,
  receiptUrl: string,
  note?: string,
): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/dispute-payment`, { receiptUrl, note });
}

// V89: решение организатора по оплате участника — и по ходу сбора, и при разборе чека.
export function resolveSkladchinaPayment(
  id: string,
  userId: string,
  accept: boolean,
  reason?: string,
): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(
    `/api/skladchinas/${id}/participants/${userId}/resolve-payment`,
    { accept, reason },
  );
}

// A-2: организатор отмечает участника оплатившим («получил наличкой») — только fixed-режимы.
export function organizerMarkPaidParticipant(id: string, userId: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/participants/${userId}/mark-paid`);
}

// A-2 (toggle): организатор возвращает оплату участника обратно в pending.
export function organizerUnmarkParticipant(id: string, userId: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/participants/${userId}/unmark`);
}

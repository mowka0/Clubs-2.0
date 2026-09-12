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

// Эндпоинты сбора — docs/modules/skladchina-v3.md § 7 «Сборы».

export function getSkladchina(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.get<SkladchinaDetailDto>(`/api/skladchinas/${id}`);
}

// Кнопка «Скинуться» на EventPage: существующий сбор по встрече (active → open, collected → собрано).
export function getEventSplitState(eventId: string): Promise<EventSplitStateDto> {
  return apiClient.get<EventSplitStateDto>(`/api/events/${eventId}/skladchina`);
}

// Шаг «выберите встречу»: бэкенд отдаёт только те встречи, по которым сбор создастся.
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

export function createSkladchina(clubId: string, body: CreateSkladchinaRequest): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/clubs/${clubId}/skladchinas`, body);
}

/** «В деле» (shared с этапом) / «Беру» (per_head). */
export function joinSkladchina(id: string, note?: string | null): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/join`, note ? { note } : {});
}

/** «Передумал» — до заморозки списка или до заказа. */
export function leaveSkladchina(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/leave`);
}

/** «Перевёл N ₽» (voluntary): долг рождается сразу claimed. */
export function contributeSkladchina(id: string, amountKopecks: number): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/contribute`, { amountKopecks });
}

/** «Закрыть запись» раньше срока. */
export function lockSkladchina(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/lock`);
}

/** «Заказываю» (per_head). */
export function orderSkladchina(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/order`);
}

/** «Закрыть сбор» (voluntary). */
export function closeSkladchina(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/close`);
}

/** «Отменить сбор» — создатель или владелец клуба. */
export function cancelSkladchina(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/cancel`);
}

export function addSkladchinaDebtor(
  id: string,
  userId: string,
  amountKopecks?: number | null,
): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/debts`, { userId, amountKopecks: amountKopecks ?? null });
}

export function replaceSkladchinaDebtor(id: string, debtId: string, userId: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/debts/${debtId}/replace`, { userId });
}

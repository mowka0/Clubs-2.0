import { apiClient } from './apiClient';
import type { ClubEventsTeaserDto, EventDetailDto, EventFormat, EventListItemDto, EventResponderDto, MyAttendanceDto, MyEventListItemDto, PageResponse } from '../types/api';

export interface CreateEventBody {
  title: string;
  description?: string;
  // Место опционально (V58), но точка ИЛИ уточнение обязательны (бэкенд отдаст 400 без
  // обоих). locationText — адрес из обратного геокодера по выбранной точке.
  locationText?: string;
  locationLat?: number;
  locationLon?: number;
  // Уточнение к месту (≤200 символов), отдельное от адреса.
  locationHint?: string;
  eventDatetime: string;
  // Число участников; смысл задаёт format. null = «сколько придёт».
  participantLimit: number | null;
  // Формат встречи — единственное поле, отвечающее на вопрос «сколько человек нужно» (V85).
  // Заявляется НАМЕРЕННО: бэкенд требует согласованности (any ⇔ participantLimit=null), чтобы
  // случайно пропущенный лимит давал 400, а не молча создавал встречу другого формата.
  format: EventFormat;
  votingOpensDaysBefore?: number;
  // За сколько минут до старта закрывается набор состава, 360..7200 (CHECK в БД шире, 60..7200).
  // Не задан = дефолт сервера (18 ч). Для формата «сколько придёт» не передаётся (400).
  stage2LeadMinutes?: number;
  photoUrl?: string;
}

export function getClubEvents(
  clubId: string,
  params?: { status?: string; page?: string; size?: string }
): Promise<PageResponse<EventListItemDto>> {
  const queryParams: Record<string, string> = {};
  if (params) {
    Object.entries(params).forEach(([k, v]) => { if (v !== undefined) queryParams[k] = v; });
  }
  return apiClient.get<PageResponse<EventListItemDto>>(`/api/clubs/${clubId}/events`, queryParams);
}

export function getMyEvents(
  params?: { page?: number; size?: number }
): Promise<PageResponse<MyEventListItemDto>> {
  const queryParams: Record<string, string> = {};
  if (params?.page !== undefined) queryParams.page = String(params.page);
  if (params?.size !== undefined) queryParams.size = String(params.size);
  return apiClient.get<PageResponse<MyEventListItemDto>>(`/api/users/me/events`, queryParams);
}

export function getEvent(id: string): Promise<EventDetailDto> {
  return apiClient.get<EventDetailDto>(`/api/events/${id}`);
}

/**
 * Тизер-афиша клуба — единственный событийный эндпоинт, доступный БЕЗ членства:
 * урезанная проекция (без места/фото/состава) для гостя и участника без взноса.
 */
export function getClubEventsTeaser(clubId: string): Promise<ClubEventsTeaserDto> {
  return apiClient.get<ClubEventsTeaserDto>(`/api/clubs/${clubId}/events/teaser`);
}

export function createEvent(clubId: string, body: CreateEventBody): Promise<EventDetailDto> {
  return apiClient.post<EventDetailDto>(`/api/clubs/${clubId}/events`, body);
}

/** F5-14: организатор отменяет ещё не начавшееся событие, с опциональной причиной (≤500 символов). */
export function cancelEvent(eventId: string, reason?: string): Promise<EventDetailDto> {
  return apiClient.post<EventDetailDto>(`/api/events/${eventId}/cancel`, reason ? { reason } : undefined);
}

/**
 * Полный набор редактируемых полей встречи. PUT-семантика: присылаем ВСЁ, что можно менять,
 * null = очистить поле. Формат встречи неизменяем и сюда не входит.
 */
export interface UpdateEventBody {
  title: string;
  description?: string | null;
  locationText?: string | null;
  locationLat?: number | null;
  locationLon?: number | null;
  locationHint?: string | null;
  /** ISO-строка (UTC). */
  eventDatetime: string;
  /** null у открытой встречи; для встречи с местами обязателен. */
  participantLimit?: number | null;
  stage2LeadMinutes?: number | null;
  photoUrl?: string | null;
}

/**
 * Редактирование встречи организатором, включая перенос даты. Бэкенд разрешает только на
 * Этапе 1 (status=upcoming): с началом подтверждения мест — 409. Уведомление участникам
 * уходит только при изменении места или времени — остальное правится молча.
 */
export function updateEvent(eventId: string, body: UpdateEventBody): Promise<EventDetailDto> {
  return apiClient.put<EventDetailDto>(`/api/events/${eventId}`, body);
}

export function castVote(eventId: string, vote: string): Promise<{ eventId: string; vote: string; goingCount: number; maybeCount: number; notGoingCount: number }> {
  return apiClient.post(`/api/events/${eventId}/vote`, { vote });
}

/**
 * `vote` — голос (going/maybe/not_going) или финальный статус Этапа 2; `seat` — место в составе
 * встречи с порогом набора, пока набор идёт («confirmed» / «waitlisted» / null). Разделены
 * намеренно: на наборе голос «Иду» при полном составе кладёт в очередь, и одно поле это скрыло бы.
 */
export function getMyVote(eventId: string): Promise<{ vote: string | null; seat: string | null }> {
  return apiClient.get(`/api/events/${eventId}/my-vote`);
}

export function getEventResponders(eventId: string): Promise<EventResponderDto[]> {
  return apiClient.get(`/api/events/${eventId}/responses`);
}

/**
 * F5-04: собственный статус посещения вызывающего. В отличие от /responses, доступ НЕ ограничен
 * членством в клубе, так что участник, покинувший клуб, всё ещё может открыть UI оспаривания.
 * 404, если у вызывающего нет строки response (организатор / не участник).
 */
export function getMyAttendance(eventId: string): Promise<MyAttendanceDto> {
  return apiClient.get(`/api/events/${eventId}/my-attendance`);
}

/**
 * Ответ подтверждения/отказа. `penaltyPoints` — сколько очков ФАКТИЧЕСКИ списал отказ (0 —
 * бесплатно): названная на экране цена могла разойтись с фактической, пока был открыт диалог
 * (очередь успела опустеть), поэтому итог берётся из ответа, а не пересчитывается клиентом.
 */
export interface ParticipationResultDto {
  eventId: string;
  status: string;
  confirmedCount: number;
  participantLimit: number | null;
  penaltyPoints: number;
}

export function confirmParticipation(eventId: string): Promise<ParticipationResultDto> {
  return apiClient.post(`/api/events/${eventId}/confirm`);
}

/** Участники, от которых ещё ждут ответа на Этапе 2 (только менеджеру клуба события). */
export function getEventPendingMembers(eventId: string): Promise<EventResponderDto[]> {
  return apiClient.get(`/api/events/${eventId}/pending`);
}

/**
 * Ручное напоминание ответить (только менеджеру клуба события).
 * `userId` — конкретный молчун; без него напоминание уходит всем, кому ещё можно.
 * Возвращает число реально отправленных: повторное напоминание тому же участнику даёт 0.
 */
export function remindToConfirm(eventId: string, userId?: string): Promise<{ remindedCount: number }> {
  return apiClient.post(`/api/events/${eventId}/remind`, userId ? { userId } : {});
}

export function declineParticipation(eventId: string): Promise<ParticipationResultDto> {
  return apiClient.post(`/api/events/${eventId}/decline`);
}

export function markAttendance(eventId: string, attendance: { userId: string; attended: boolean }[]): Promise<{ eventId: string; markedCount: number }> {
  return apiClient.post(`/api/events/${eventId}/attendance`, { attendance });
}

/** Участник оспаривает отметку «отсутствовал» (ATT-3), с опциональной заметкой. Бэкенд: absent → disputed. */
export function disputeAttendance(eventId: string, note?: string): Promise<{ eventId: string; markedCount: number }> {
  return apiClient.post(`/api/events/${eventId}/dispute`, note ? { note } : undefined);
}

/** Организатор разрешает спорную отметку в attended/absent. */
export function resolveDispute(eventId: string, userId: string, attended: boolean): Promise<{ eventId: string; markedCount: number }> {
  return apiClient.post(`/api/events/${eventId}/attendance/${userId}/resolve`, { attended });
}

export function getFinances(clubId: string): Promise<import('../types/api').FinancesDto> {
  return apiClient.get(`/api/clubs/${clubId}/finances`);
}

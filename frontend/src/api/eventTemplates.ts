import { apiClient } from './apiClient';

/**
 * Именованная заготовка формы создания встречи, принадлежащая клубу.
 * Хранит всё, кроме даты: вместо неё — день недели и время повторов.
 * Спека: docs/modules/event-templates.md.
 */
export interface EventTemplateDto {
  id: string;
  clubId: string;
  /** Имя клуба — список в «+» кросс-клубовый, пункт показывает, чей это шаблон. */
  clubName: string;
  /** Ярлык списка, отдельный от title. */
  name: string;
  title: string;
  description: string | null;
  locationText: string | null;
  locationLat: number | null;
  locationLon: number | null;
  locationHint: string | null;
  /** null = шаблон открытой встречи. */
  participantLimit: number | null;
  isOpenEvent: boolean;
  isUrgentEvent: boolean;
  stage2LeadMinutes: number | null;
  photoUrl: string | null;
  /** 1 = понедельник … 7 = воскресенье (ISO), в локальной зоне организатора. null = дата не угадывается. */
  defaultWeekday: number | null;
  /** "HH:mm:ss" в локальной зоне организатора. null = время не задано. */
  defaultTime: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

/**
 * Тело создания и полной замены. PUT-семантика как у встречи: присылаем ВЕСЬ набор полей,
 * null = очистить. Отдельного эндпоинта переименования нет — шлём DTO обратно с новым `name`.
 */
export interface SaveEventTemplateBody {
  name: string;
  title: string;
  description?: string | null;
  locationText?: string | null;
  locationLat?: number | null;
  locationLon?: number | null;
  locationHint?: string | null;
  participantLimit?: number | null;
  isOpenEvent?: boolean;
  isUrgentEvent?: boolean;
  stage2LeadMinutes?: number | null;
  photoUrl?: string | null;
  defaultWeekday?: number | null;
  defaultTime?: string | null;
}

/** Шаблоны всех клубов, где у вызывающего есть право вести встречи — источник списка в «+». */
export function getMyEventTemplates(): Promise<EventTemplateDto[]> {
  return apiClient.get<EventTemplateDto[]>('/api/me/event-templates');
}

export function getClubEventTemplates(clubId: string): Promise<EventTemplateDto[]> {
  return apiClient.get<EventTemplateDto[]>(`/api/clubs/${clubId}/event-templates`);
}

export function createEventTemplate(
  clubId: string,
  body: SaveEventTemplateBody,
): Promise<EventTemplateDto> {
  return apiClient.post<EventTemplateDto>(`/api/clubs/${clubId}/event-templates`, body);
}

export function updateEventTemplate(
  clubId: string,
  templateId: string,
  body: SaveEventTemplateBody,
): Promise<EventTemplateDto> {
  return apiClient.put<EventTemplateDto>(`/api/clubs/${clubId}/event-templates/${templateId}`, body);
}

export function deleteEventTemplate(clubId: string, templateId: string): Promise<void> {
  return apiClient.delete<void>(`/api/clubs/${clubId}/event-templates/${templateId}`);
}

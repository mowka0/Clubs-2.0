import { apiClient } from './apiClient';

/** Одно событие пачки — зеркало `TrackEventRequest` бэкенда (product-analytics.md § 4). */
export interface TrackEventBody {
  kind: string;
  clubId?: string;
  props?: Record<string, string | number | boolean>;
}

/**
 * Пачка событий в журнал. `keepalive` — для отправки при сворачивании приложения: такой запрос
 * доживает до ответа, даже если страница уже выгружена (`sendBeacon` не умеет заголовок Authorization).
 */
export function postTrack(events: TrackEventBody[], keepalive = false): Promise<void> {
  return apiClient.request<void>('POST', '/api/track', { events }, undefined, false, keepalive);
}

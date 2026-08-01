import { apiClient } from './apiClient';

/**
 * Словарь интересов — общий для тем клуба и интересов профиля (club-interests.md).
 * Одно пространство имён нужно, чтобы человека и клуб вообще было чем сопоставлять.
 */

/** Префиксное автодополнение по всему словарю: чипы профиля и поиск темы при разметке клуба. */
export function suggestInterests(query: string, limit = 10): Promise<string[]> {
  return apiClient.get<string[]>('/api/interests/suggest', {
    q: query,
    limit: String(limit),
  });
}

/** Топ-темы полки — чипы, которые видит организатор сразу после выбора категории. */
export function getCategoryInterests(category: string, limit = 24): Promise<string[]> {
  return apiClient.get<string[]>('/api/interests', {
    category,
    limit: String(limit),
  });
}

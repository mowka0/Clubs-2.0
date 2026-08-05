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

/**
 * То же автодополнение, но только по темам, которыми размечен хотя бы один клуб, — для поиска
 * каталога. Подсказка, по которой не найдётся ни одного клуба, хуже её отсутствия.
 */
export function suggestClubTopics(query: string, limit = 6): Promise<string[]> {
  return apiClient.get<string[]>('/api/interests/suggest', {
    q: query,
    limit: String(limit),
    clubsOnly: 'true',
  });
}

/** Топ-темы полки — чипы, которые видит организатор сразу после выбора категории. */
export function getCategoryInterests(category: string, limit = 24): Promise<string[]> {
  return apiClient.get<string[]>('/api/interests', {
    category,
    limit: String(limit),
  });
}

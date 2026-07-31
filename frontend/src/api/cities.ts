import { apiClient } from './apiClient';
import type { CityDto } from '../types/api';

/**
 * Справочник городов целиком (порядка 540 записей, ~40 КБ). Отдаётся одним запросом
 * намеренно: пикер ищет локально по загруженному списку, поэтому поиск идёт без сети.
 */
export function getCities(): Promise<CityDto[]> {
  return apiClient.get<CityDto[]>('/api/cities');
}

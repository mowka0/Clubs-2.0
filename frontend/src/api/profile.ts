import { apiClient } from './apiClient';
import type { OnboardingTour, UpdateProfileBody, UserDto } from '../types/api';

export function getMe(): Promise<UserDto> {
  return apiClient.get<UserDto>('/api/users/me');
}

export function updateMyProfile(body: UpdateProfileBody): Promise<UserDto> {
  return apiClient.patch<UserDto>('/api/users/me', body);
}

/**
 * Отмечает тур онбординга пройденным и возвращает обновлённый профиль.
 * Идемпотентно: повторный вызов — тоже 200, отдельной обработки «уже пройден» не нужно.
 */
export function completeTour(tour: OnboardingTour): Promise<UserDto> {
  return apiClient.post<UserDto>(`/api/users/me/onboarding/${tour}`, {});
}

export function getMyInterests(): Promise<string[]> {
  return apiClient.get<string[]>('/api/users/me/interests');
}

export function suggestInterests(query: string, limit = 10): Promise<string[]> {
  return apiClient.get<string[]>('/api/interests/suggest', {
    q: query,
    limit: String(limit),
  });
}

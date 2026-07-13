import { apiClient } from './apiClient';
import type { OnboardingDoor, UpdateProfileBody, UserDto } from '../types/api';

export function getMe(): Promise<UserDto> {
  return apiClient.get<UserDto>('/api/users/me');
}

export function updateMyProfile(body: UpdateProfileBody): Promise<UserDto> {
  return apiClient.patch<UserDto>('/api/users/me', body);
}

/**
 * Отмечает онбординг пройденным и возвращает обновлённый профиль.
 * Дверь — та кнопка, которой человек вышел из карусели; бэк пишет её в лог как метрику намерения.
 */
export function completeOnboarding(door: OnboardingDoor): Promise<UserDto> {
  return apiClient.post<UserDto>('/api/users/me/onboarding', { door });
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

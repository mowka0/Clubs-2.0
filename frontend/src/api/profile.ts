import { apiClient } from './apiClient';
import type { UpdateProfileBody, UserDto } from '../types/api';

export function updateMyProfile(body: UpdateProfileBody): Promise<UserDto> {
  return apiClient.patch<UserDto>('/api/users/me', body);
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

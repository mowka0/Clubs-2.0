import { apiClient } from './apiClient';
import type { ChatLinkStatusDto } from '../types/api';

// Владельческий API привязки телеграм-чата (таб «Чат» в «Управлении клубом»).
// Спека: docs/modules/club-chat-link.md

export function getChatLinkStatus(clubId: string): Promise<ChatLinkStatusDto> {
  return apiClient.get<ChatLinkStatusDto>(`/api/clubs/${clubId}/chat-link`);
}

/** «Проверить права ещё раз» — перечитать статус бота из Telegram. */
export function refreshChatLink(clubId: string): Promise<ChatLinkStatusDto> {
  return apiClient.post<ChatLinkStatusDto>(`/api/clubs/${clubId}/chat-link/refresh`);
}

/** Тумблер «Вход в чат через заявки» (дверь). */
export function updateChatLink(clubId: string, body: { doorEnabled: boolean }): Promise<ChatLinkStatusDto> {
  return apiClient.patch<ChatLinkStatusDto>(`/api/clubs/${clubId}/chat-link`, body);
}

export function unlinkChat(clubId: string): Promise<void> {
  return apiClient.delete<void>(`/api/clubs/${clubId}/chat-link`);
}

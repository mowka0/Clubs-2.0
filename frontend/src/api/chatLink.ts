import { apiClient } from './apiClient';
import type { ChatLinkStatusDto, NewClubChatLinkDto, UpdateChatLinkRequest } from '../types/api';

// Владельческий API привязки телеграм-чата (таб «Чат» в «Управлении клубом»).
// Спека: docs/modules/club-chat-link.md

export function getChatLinkStatus(clubId: string): Promise<ChatLinkStatusDto> {
  return apiClient.get<ChatLinkStatusDto>(`/api/clubs/${clubId}/chat-link`);
}

/** «Проверить права ещё раз» — перечитать статус бота из Telegram. */
export function refreshChatLink(clubId: string): Promise<ChatLinkStatusDto> {
  return apiClient.post<ChatLinkStatusDto>(`/api/clubs/${clubId}/chat-link/refresh`);
}

/** Тумблеры «Вход в чат через заявки» (дверь) и «Живой закреп» — частичный PATCH. */
export function updateChatLink(clubId: string, body: UpdateChatLinkRequest): Promise<ChatLinkStatusDto> {
  return apiClient.patch<ChatLinkStatusDto>(`/api/clubs/${clubId}/chat-link`, body);
}

/** Закрепить в чате ссылку на клуб. Повторный вызов заменяет прежний закреп новым. */
export function pinClubLink(clubId: string): Promise<ChatLinkStatusDto> {
  return apiClient.post<ChatLinkStatusDto>(`/api/clubs/${clubId}/chat-link/pin-club-link`);
}

export function unlinkChat(clubId: string): Promise<void> {
  return apiClient.delete<void>(`/api/clubs/${clubId}/chat-link`);
}

/**
 * Ссылка «подключить чат» для человека без клуба: `t.me/<bot>?startgroup=new`.
 * Клуб создаётся из самого чата, поэтому clubId в пути нет.
 */
export function getNewClubChatLinkUrl(): Promise<NewClubChatLinkDto> {
  return apiClient.get<NewClubChatLinkDto>('/api/chat-link/new-club-url');
}

/**
 * «Я иду добавлять бота в группу» — зовётся перед самым уходом в Telegram.
 *
 * Бот узнаёт о добавлении апдейтом `my_chat_member`, в котором payload ссылки отсутствует, а
 * команду `/start <payload>` Telegram не отправляет, когда ссылка просит права администратора.
 * Отложенное здесь намерение и подсказывает боту, что делать с чатом (club-chat-link.md).
 *
 * `clubId = null` — клуба ещё нет, чат станет новым клубом. `grantRightsOnly` — чат уже
 * привязан, человек идёт только за правами бота: Telegram выдаёт их переприглашением, и без
 * этого флага событие выглядело бы как новое добавление.
 */
export function rememberChatLinkIntent(clubId: string | null, grantRightsOnly = false): Promise<void> {
  return apiClient.post<void>('/api/chat-link/intent', { clubId, grantRightsOnly });
}

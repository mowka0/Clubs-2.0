import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getChatLinkStatus, refreshChatLink, unlinkChat, updateChatLink } from '../api/chatLink';
import type { ChatLinkStatusDto } from '../types/api';
import { queryKeys } from './queryKeys';

// Server state привязки чата (club-chat-link). Мутации кладут свежий статус в кэш сразу
// (бэкенд возвращает его в ответе) и инвалидируют детальку клуба — там живут
// chatLinked/chatDoorEnabled/chatInviteLink для чипа и кнопки «Чат клуба».

/**
 * Статус привязки для таба «Чат». refetchOnWindowFocus важен: привязка происходит
 * ВНЕ приложения (в Telegram-пикере групп), и по возвращении в Mini App таб должен
 * сам увидеть новую привязку без ручного «обновить».
 */
export function useChatLinkStatusQuery(clubId: string | undefined, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.clubs.chatLink(clubId ?? ''),
    queryFn: () => getChatLinkStatus(clubId!),
    enabled: Boolean(clubId) && (options.enabled ?? true),
    refetchOnWindowFocus: true,
  });
}

function useApplyChatLinkResult(clubId: string) {
  const queryClient = useQueryClient();
  return (status: ChatLinkStatusDto) => {
    queryClient.setQueryData(queryKeys.clubs.chatLink(clubId), status);
    void queryClient.invalidateQueries({ queryKey: queryKeys.clubs.detail(clubId) });
  };
}

export function useRefreshChatLinkMutation(clubId: string) {
  const apply = useApplyChatLinkResult(clubId);
  return useMutation({
    mutationFn: () => refreshChatLink(clubId),
    onSuccess: apply,
  });
}

export function useUpdateChatLinkMutation(clubId: string) {
  const apply = useApplyChatLinkResult(clubId);
  return useMutation({
    mutationFn: (body: { doorEnabled: boolean }) => updateChatLink(clubId, body),
    onSuccess: apply,
  });
}

export function useUnlinkChatMutation(clubId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => unlinkChat(clubId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.clubs.chatLink(clubId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.clubs.detail(clubId) });
    },
  });
}

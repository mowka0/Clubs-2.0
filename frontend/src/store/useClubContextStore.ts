import { useEffect } from 'react';
import { create } from 'zustand';
import { rememberClubShown } from '../telegram/chatOrigin';

interface ClubContextState {
  /**
   * clubId клуба, который пользователь сейчас просматривает. Устанавливается страницами,
   * привязанными к клубу (страница клуба, управление, детали события/складчины), чтобы
   * глобальный FAB "+" мог заранее выбрать этот клуб и пропустить пикер клуба. Везде
   * ещё — `null`.
   */
  clubId: string | null;
  setClubId: (id: string | null) => void;
}

export const useClubContextStore = create<ClubContextState>((set) => ({
  clubId: null,
  setClubId: (id) => set({ clubId: id }),
}));

/**
 * Отмечает текущий контекст клуба на время жизни страницы. Передайте id клуба
 * (или `null`/`undefined`, пока он ещё загружается). Очищается при размонтировании,
 * чтобы FAB возвращался к обычному flow «выбрать клуб» на не-клубных экранах.
 */
export function useSetClubContext(clubId: string | null | undefined): void {
  const setClubId = useClubContextStore((s) => s.setClubId);
  useEffect(() => {
    setClubId(clubId ?? null);
    // Заодно фиксируем клуб-хозяин чата, из которого открыто приложение: сюда сходятся все
    // клубные страницы, а первая из них — та, на которую привела кнопка в чате (chatOrigin).
    if (clubId) rememberClubShown(clubId);
    return () => setClubId(null);
  }, [clubId, setClubId]);
}

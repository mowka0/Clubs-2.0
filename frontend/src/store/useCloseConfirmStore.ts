import { create } from 'zustand';

interface CloseConfirmState {
  /** Показана ли шторка «Закрыть приложение?» — её рендерит Layout, спрашивает useBackButton. */
  asking: boolean;
  ask: () => void;
  settle: () => void;
}

/**
 * Вопрос «назад идти некуда — закрыть приложение?» живёт вне хука: хук UI не рендерит, а
 * нативный попап Telegram нельзя стилизовать под приложение (PO 2026-10-06), поэтому шторка
 * своя — ConfirmSheet в Layout.
 */
export const useCloseConfirmStore = create<CloseConfirmState>((set) => ({
  asking: false,
  ask: () => set({ asking: true }),
  settle: () => set({ asking: false }),
}));

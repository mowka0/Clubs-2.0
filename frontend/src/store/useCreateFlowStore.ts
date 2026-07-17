import { create } from 'zustand';

/**
 * Глобальный триггер единого флоу создания активности.
 *
 * Сам флоу (CreateActivityFlow) живёт в одном месте — AppDock — вместе со своими
 * guard'ами (создают только организаторы, preset текущего клуба). CTA из глубины
 * страниц (пустые состояния) не дублируют флоу, а открывают его через этот стор.
 */
interface CreateFlowStore {
  isOpen: boolean;
  open: () => void;
  close: () => void;
}

export const useCreateFlowStore = create<CreateFlowStore>((set) => ({
  isOpen: false,
  open: () => set({ isOpen: true }),
  close: () => set({ isOpen: false }),
}));

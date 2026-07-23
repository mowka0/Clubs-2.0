import { create } from 'zustand';

/**
 * Глобальный триггер единого флоу создания активности.
 *
 * Сам флоу (CreateActivityFlow) живёт в одном месте — AppDock — вместе со своими
 * guard'ами (пункты создания видят только организаторы, preset текущего клуба;
 * «Сообщить о проблеме» доступен всем). CTA из глубины страниц (пустые состояния)
 * не дублируют флоу, а открывают его через этот стор.
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

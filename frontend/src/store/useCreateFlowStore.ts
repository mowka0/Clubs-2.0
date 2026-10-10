import { create } from 'zustand';
import type { ActivityType } from '../api/activities';

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
  /**
   * С какого шага открыть: «Создать встречу» / «Создать сбор» из пустых состояний сразу ведут на
   * выбор формата встречи или вида сбора — вопрос «что создаём» уже отвечен кнопкой (PO 2026-10-10).
   * null — с выбора типа (кнопка «+» в доке).
   */
  initialType: ActivityType | null;
  open: (initialType?: ActivityType) => void;
  close: () => void;
}

export const useCreateFlowStore = create<CreateFlowStore>((set) => ({
  isOpen: false,
  initialType: null,
  open: (initialType) => set({ isOpen: true, initialType: initialType ?? null }),
  close: () => set({ isOpen: false, initialType: null }),
}));

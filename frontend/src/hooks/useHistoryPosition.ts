import { useEffect, useMemo, useRef } from 'react';
import { useLocation, useNavigationType } from 'react-router-dom';

/**
 * Позиция текущей записи в истории браузера.
 *
 * react-router сам хранит её в `history.state.idx` (см. `createBrowserHistory`):
 * при инициализации роутер дописывает `idx: 0`, если записи ещё нет, поэтому
 * значение всегда число. Вне браузерного роутера (MemoryRouter в тестах)
 * `history.state` наш, и значение читается оттуда же.
 */
function readHistoryIndex(): number {
  const state = window.history.state as { idx?: unknown } | null;
  return typeof state?.idx === 'number' ? state.idx : 0;
}

export interface HistoryPosition {
  /** Есть ли куда возвращаться: мы не на самой первой записи истории. */
  canGoBack: () => boolean;
  /**
   * Есть ли куда идти вперёд: мы ушли назад и с тех пор не начали новую ветку.
   * Браузер не даёт спросить об этом напрямую — считаем сами по индексам.
   */
  canGoForward: () => boolean;
}

/**
 * Следит за тем, где мы стоим в истории, чтобы жест-навигация не пыталась уйти
 * туда, где ничего нет (и не показывала анимацию перехода вхолостую).
 *
 * Правило одно: PUSH обрезает всё, что было впереди — браузер выбрасывает
 * forward-ветку, как только появляется новая запись. POP и REPLACE её сохраняют.
 *
 * Значения отдаются функциями, а не числами: их читают обработчики жеста, живущие
 * вне рендера, и им нужно актуальное состояние без пересоздания подписок.
 */
export function useHistoryPosition(): HistoryPosition {
  const location = useLocation();
  const navigationType = useNavigationType();
  const indexRef = useRef(readHistoryIndex());
  const furthestIndexRef = useRef(indexRef.current);

  useEffect(() => {
    const index = readHistoryIndex();
    indexRef.current = index;
    furthestIndexRef.current =
      navigationType === 'PUSH' ? index : Math.max(furthestIndexRef.current, index);
  }, [location.key, navigationType]);

  return useMemo<HistoryPosition>(
    () => ({
      canGoBack: () => indexRef.current > 0,
      canGoForward: () => indexRef.current < furthestIndexRef.current,
    }),
    [],
  );
}

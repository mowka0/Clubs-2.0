import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

/**
 * Что подсветить на странице, куда привела дверь онбординга. Тип живёт здесь, а не в фиче:
 * общий хук не должен зависеть от `components/onboarding` — иначе стрелка зависимостей
 * развёрнута, и удаление фичи роняет shared-код.
 */
export type HighlightTarget = 'city' | 'create-club';

/**
 * Сколько держится подсветка (мс). Парное значение с CSS-анимацией `.rd-highlight-pulse`
 * (1s × 5 итераций): числа обязаны совпадать, иначе кольцо перестаёт пульсировать,
 * но акцентная рамка ещё висит — выглядит как подвисшая подсветка.
 */
const HIGHLIGHT_DURATION_MS = 5000;

/**
 * «Показать пальцем» на элемент, ради которого человека сюда привели.
 *
 * Кнопка онбординга не открывает форму сама, а приводит на страницу, где та живёт,
 * и подсвечивает её: так человек с первого раза запоминает, где эта штука находится.
 * Цель приходит в `location.state.highlight` — страница-получатель спрашивает про свою.
 *
 * Метка из history снимается сразу (`replace`), иначе возврат назад на эту страницу
 * зажигал бы кольцо снова, уже без всякого повода.
 */
export function useHighlight(target: HighlightTarget): boolean {
  const location = useLocation();
  const navigate = useNavigate();
  const requested = (location.state as { highlight?: string } | null)?.highlight === target;

  const [active, setActive] = useState(requested);

  useEffect(() => {
    if (!requested) return;
    setActive(true);
    // Страницы живут в общем window-скролле, и позиция «переезжает» с прошлого экрана:
    // без сброса цель подсветки может остаться за кадром, и человек пульса не увидит.
    window.scrollTo({ top: 0, left: 0, behavior: 'auto' });
    // Снимаем ТОЛЬКО свой ключ, а путь сохраняем целиком: на «Моих клубах» рядом живут
    // `state.toast` и deep-link `?focus=inbox`, и стереть соседа заодно с меткой подсветки
    // значило бы тихо сломать чужую фичу.
    const { highlight: _highlight, ...rest } = (location.state ?? {}) as Record<string, unknown>;
    navigate(`${location.pathname}${location.search}${location.hash}`, {
      replace: true,
      state: Object.keys(rest).length > 0 ? rest : null,
    });
  }, [requested, navigate, location.pathname, location.search, location.hash, location.state]);

  useEffect(() => {
    if (!active) return;
    const timer = setTimeout(() => setActive(false), HIGHLIGHT_DURATION_MS);
    return () => clearTimeout(timer);
  }, [active]);

  return active;
}

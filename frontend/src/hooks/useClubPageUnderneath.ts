import { useEffect } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { useHistoryPosition } from './useHistoryPosition';

/**
 * «Управление клубом» бывает первой страницей сессии: возврат из браузера после оплаты
 * (`startapp=billing_…`) и кнопка «Оплатить» в DM открывают приложение прямо на ней. Позади в
 * истории тогда пусто, и «назад» молчит — не работают ни кнопка Telegram (`navigate(-1)` в
 * никуда), ни свайп от кромки (он тоже спрашивает `canGoBack`): человек остаётся заперт на
 * экране управления (баг PO 2026-09-16).
 *
 * Чиним источник, а не симптом: подкладываем под низ страницу клуба — ту самую, с которой
 * человек и попал бы сюда обычным путём. После этого «назад» и свайп работают сами собой.
 * Когда история уже есть, хук не делает ничего.
 */
export function useClubPageUnderneath(): void {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const { canGoBack } = useHistoryPosition();

  useEffect(() => {
    if (!id || canGoBack()) return;
    navigate(`/clubs/${id}`, { replace: true });
    navigate(`${location.pathname}${location.search}`);
    // Только при первом появлении экрана: дальше историей управляет обычная навигация.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
}

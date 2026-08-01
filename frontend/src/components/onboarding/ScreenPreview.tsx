import { FC, useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { SCREEN_PREVIEWS, PREVIEW_ACK } from './previews';
import { useHaptic } from '../../hooks/useHaptic';
import { useSheetDrag } from '../../hooks/useSheetDrag';
import { useCompleteTourMutation } from '../../queries/profile';
import { useAuthStore } from '../../store/useAuthStore';
import type { OnboardingTour } from '../../types/api';

/**
 * Пауза перед подъёмом шторки (мс). Экран под ней должен успеть отрисоваться: превью,
 * прилетевшее в тот же кадр, что и страница, читается как заставка приложения, а не как
 * рассказ про то, что человек уже видит.
 */
const RISE_DELAY_MS = 420;

interface ScreenPreviewProps {
  /** Экран, чьё превью показываем. Ключ тот же, что помечает показ на бэкенде. */
  screen: OnboardingTour;
  /**
   * Разрешено ли сейчас. Страница выставляет false, пока данные не приехали или пока
   * поверх идёт своя сцена (велком новичка): две шторки подряд человек закроет не читая.
   */
  ready?: boolean;
}

/**
 * Превью экрана — шторка снизу при первом заходе (решение PO 2026-07-31, заменило туры
 * коуч-марок).
 *
 * Показывается **ровно один раз за жизнь аккаунта** и только по этому экрану: закрытое
 * превью клуба ничего не говорит про превью профиля. Повторного вызова нет — кнопку «?»
 * PO снял осознанно, поэтому текст обязан читаться за один заход (см. `previews.ts`).
 *
 * Закрывается чем угодно: кнопкой, тапом мимо, Escape. Любой из способов засчитывает показ —
 * человек шторку видел, и второй раз она не имеет права появиться.
 */
export const ScreenPreview: FC<ScreenPreviewProps> = ({ screen, ready = true }) => {
  const haptic = useHaptic();
  const completeTour = useCompleteTourMutation();
  const user = useAuthStore((s) => s.user);
  const setUser = useAuthStore((s) => s.setUser);

  const preview = SCREEN_PREVIEWS[screen];
  // Fail-closed: профиля ещё нет или он пришёл без списка — считаем показанным и молчим.
  // Компонент висит на каждом экране; худшее последствие осторожности — непоказанное превью,
  // а не шторка, вылезающая посреди работы.
  const alreadySeen = user?.onboardingTours?.includes(screen) ?? true;

  const [open, setOpen] = useState(false);
  /** Показ уже засчитан — второй раз отметку не шлём (кнопка + тап мимо могут прийти подряд). */
  const [acked, setAcked] = useState(false);

  const shouldShow = !alreadySeen && ready && preview !== undefined && !acked;

  // Поднимаем шторку с задержкой. Пока открыта чужая модалка, не лезем: её слой выше,
  // и превью либо перекроет её, либо уйдёт под неё — оба варианта хуже, чем подождать
  // следующего захода на экран.
  useEffect(() => {
    if (!shouldShow) return;
    const timer = window.setTimeout(() => {
      if (document.querySelector('[role="dialog"]') !== null) return;
      setOpen(true);
      haptic.impact('light');
    }, RISE_DELAY_MS);
    return () => window.clearTimeout(timer);
    // haptic пересоздаётся каждый рендер и в зависимости не годится — таймер перезапускался бы вечно.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shouldShow]);

  // Страница под шторкой не прокручивается: превью — модальный момент, а не плашка сбоку.
  useEffect(() => {
    if (!open) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, [open]);

  // Шторку закрывают и протяжкой вниз за шапку — привычный жест боттом-шита.
  const { sheetRef, dragHandlers } = useSheetDrag(() => close());

  const close = () => {
    if (acked) return;
    haptic.impact('light');
    setAcked(true);
    setOpen(false);
    completeTour
      .mutateAsync(screen)
      .then(setUser)
      // Отметка не прошла — не беда: превью предложится ещё раз при следующем заходе.
      // Запирать человека в шторке из-за упавшей сети нельзя.
      .catch(() => undefined);
  };

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  if (!open || preview === undefined) return null;

  return createPortal(
    // `data-swipe-nav="off"` — под шторкой не должен срабатывать навигационный свайп «назад».
    <div data-swipe-nav="off">
      <div className="rd-sheet-overlay rd-overlay-in" onClick={close} aria-hidden="true" />
      <div className="rd-sheet sp-sheet rd-sheet-in" role="dialog" aria-modal="true" aria-labelledby="sp-title" ref={sheetRef}>
        {/* Зона захвата: грабер и арт. За неё шторку тянут вниз — грабер в 4px под палец
            не попадает, а арт всё равно не интерактивен. Текст ниже остаётся выделяемым. */}
        <div className="sp-grip" {...dragHandlers}>
          <div className="rd-sheet-grabber" aria-hidden="true" />
          <img className="sp-art" src={preview.artSrc} alt="" draggable={false} />
        </div>

        <h2 className="sp-title" id="sp-title">{preview.title}</h2>
        <p className="sp-lead">{preview.lead}</p>

        <ul className="sp-rules">
          {preview.rules.map((rule) => (
            <li key={rule}>{rule}</li>
          ))}
        </ul>

        <button type="button" className="rd-btn-primary sp-ack" onClick={close}>
          {PREVIEW_ACK}
        </button>
      </div>
    </div>,
    document.body,
  );
};

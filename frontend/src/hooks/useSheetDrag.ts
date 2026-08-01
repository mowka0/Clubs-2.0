import { useRef } from 'react';

/** Насколько нужно утянуть шторку вниз, чтобы отпускание её закрыло (px). */
const DISMISS_DISTANCE_PX = 90;
/** Скорость (px/ms), при которой короткий рывок вниз закрывает, не добрав дистанцию. */
const DISMISS_VELOCITY = 0.5;
/** Во сколько раз ослабляется тяга ВВЕРХ — шторка упирается в «потолок», а не летит за пальцем. */
const UP_RESISTANCE = 4;
/** Сколько шторка доезжает до места после отпускания (мс). */
const SETTLE_MS = 220;
/** Сколько длится уезд вниз при закрытии (мс). Парное значение с задержкой вызова onDismiss. */
const DISMISS_MS = 200;

/**
 * Протяжка шторки вниз за шапку — «смахнуть, чтобы закрыть».
 *
 * Жест висит на шапке (грабер + заголовок), а не на всей шторке: внутри у форм есть
 * прокручиваемое тело, и перехват вертикальных движений там отнял бы у него скролл.
 *
 * Возвращает `sheetRef` (вешается на саму шторку) и `dragHandlers` (на её шапку).
 */
export function useSheetDrag(onDismiss: () => void) {
  const sheetRef = useRef<HTMLDivElement>(null);
  // Жест держим в ref, а не в состоянии: он меняется на каждый кадр касания, и ререндер
  // на каждое движение пальца шторку бы дёргал.
  const gestureRef = useRef<{ startY: number; lastY: number; lastTime: number; velocity: number } | null>(null);

  const paint = (shift: number, animated: boolean) => {
    const sheet = sheetRef.current;
    if (sheet === null) return;
    // CSS-анимация подъёма перебивает инлайновый transform (анимации приоритетнее inline-стилей),
    // поэтому на время жеста её снимаем — иначе шторка не поедет за пальцем.
    sheet.style.animation = 'none';
    sheet.style.transition = animated ? `transform ${SETTLE_MS}ms cubic-bezier(.22,.61,.36,1)` : '';
    sheet.style.transform = `translate3d(0, ${shift}px, 0)`;
  };

  const onTouchStart = (e: React.TouchEvent) => {
    const y = e.changedTouches[0]?.clientY;
    if (y === undefined) return;
    gestureRef.current = { startY: y, lastY: y, lastTime: Date.now(), velocity: 0 };
  };

  const onTouchMove = (e: React.TouchEvent) => {
    const gesture = gestureRef.current;
    const y = e.changedTouches[0]?.clientY;
    if (gesture === null || y === undefined) return;
    const now = Date.now();
    const elapsed = now - gesture.lastTime;
    if (elapsed > 0) gesture.velocity = (y - gesture.lastY) / elapsed;
    gesture.lastY = y;
    gesture.lastTime = now;

    const shift = y - gesture.startY;
    paint(shift > 0 ? shift : shift / UP_RESISTANCE, false);
  };

  const onTouchEnd = (e: React.TouchEvent) => {
    const gesture = gestureRef.current;
    gestureRef.current = null;
    const y = e.changedTouches[0]?.clientY;
    // Координаты нет — значит, и жеста не было. Подставлять 0 нельзя: это прочиталось бы
    // как рывок к верхней кромке экрана.
    if (gesture === null || y === undefined) return;

    const shift = y - gesture.startY;
    if (shift > DISMISS_DISTANCE_PX || gesture.velocity > DISMISS_VELOCITY) {
      // Сначала доигрываем уезд вниз, и только потом снимаем шторку: закрытие в тот же кадр
      // читается как исчезновение, а не как «смахнул».
      const sheet = sheetRef.current;
      if (sheet !== null) {
        sheet.style.transition = `transform ${DISMISS_MS}ms ease-in`;
        sheet.style.transform = 'translate3d(0, 100%, 0)';
      }
      window.setTimeout(onDismiss, DISMISS_MS);
      return;
    }
    paint(0, true);
  };

  return { sheetRef, dragHandlers: { onTouchStart, onTouchMove, onTouchEnd } };
}

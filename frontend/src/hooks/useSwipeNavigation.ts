import { RefObject, useCallback, useEffect, useLayoutEffect, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useHaptic } from './useHaptic';
import { useHistoryPosition } from './useHistoryPosition';

/** Куда уводит жест: к предыдущей записи истории или к следующей. */
export type SwipeDirection = 'back' | 'forward';

/** Ширина «живой» кромки экрана, с которой начинается жест (px). Уже — не поймать, шире — мешает контенту. */
const EDGE_ZONE_PX = 28;
/** Сдвиг пальца, после которого решаем: это горизонтальный жест, а не вертикальная прокрутка (px). */
const DIRECTION_LOCK_PX = 12;
/** Во сколько раз горизонталь должна опережать вертикаль, чтобы жест считался «назад/вперёд». */
const HORIZONTAL_DOMINANCE = 1.2;
/** Доля ширины экрана, после которой отпускание пальца засчитывается как переход. */
const COMMIT_DISTANCE_RATIO = 0.3;
/** Скорость (px/ms), при которой короткий флик засчитывается как переход, не добрав дистанцию. */
const COMMIT_VELOCITY = 0.5;
/** Сколько страница доигрывает за край экрана после отпускания (мс). */
const COMMIT_MS = 190;
/** Сколько страница возвращается на место, если жест не добрал порог (мс). */
const CANCEL_MS = 170;
/** Страховка: если после доигранного жеста роут так и не сменился — вернуть страницу на место (мс). */
const NAVIGATION_TIMEOUT_MS = 700;
/** Плотность затемнения под уезжающей страницей в начале жеста; к концу гаснет до нуля. */
const SCRIM_MAX_OPACITY = 0.28;

/**
 * Элементы, внутри которых жест не начинается. Шторки и модалки живут в порталах
 * (вне обёртки, слушатели до них не доходят), но лайтбокс и любые будущие
 * полноэкранные слои рендерятся внутри страницы — их отсекаем по атрибуту/роли.
 */
const BLOCKING_SELECTOR = '[role="dialog"], [data-swipe-nav="off"]';

export interface SwipeNavigationRefs {
  /** Обёртка, на которой висят слушатели касаний. */
  hostRef: RefObject<HTMLDivElement | null>;
  /** Слой со страницей — он едет за пальцем. */
  pageRef: RefObject<HTMLDivElement | null>;
  /** Затемнение под страницей — проявляет «глубину» открывающегося экрана. */
  scrimRef: RefObject<HTMLDivElement | null>;
}

interface ActiveGesture {
  direction: SwipeDirection;
  /** identifier касания — чтобы не перепутать пальцы при мультитаче. */
  touchId: number;
  startX: number;
  startY: number;
  lastX: number;
  lastTime: number;
  /** Скорость последнего отрезка движения, px/ms — по ней ловим быстрый флик. */
  velocity: number;
  /** Направление подтверждено: дальше страница едет за пальцем, прокрутка отменена. */
  isLocked: boolean;
}

/** Горизонтально прокручиваемый предок (ряд чипов, полка) забирает жест себе. */
function hasHorizontalScrollAncestor(target: Element, host: Element): boolean {
  let node: Element | null = target;
  while (node !== null && node !== host) {
    if (node.scrollWidth > node.clientWidth) {
      const overflowX = window.getComputedStyle(node).overflowX;
      if (overflowX === 'auto' || overflowX === 'scroll') return true;
    }
    node = node.parentElement;
  }
  return false;
}

/**
 * Свайпы «назад/вперёд» от кромок экрана — единый жест на всё приложение.
 *
 * Левая кромка тянет к предыдущей записи истории, правая — к следующей; обе
 * работают ровно там, где есть куда идти (см. [[useHistoryPosition]]), поэтому
 * с первого экрана приложение жестом не закрыть.
 *
 * Работает на touch-событиях, а не на Pointer Events, намеренно: прокрутку в
 * WebView отменяет только `preventDefault` у неpassive `touchmove`, а глобальный
 * `touch-action` наследуется вниз и сломал бы горизонтальные ряды внутри страниц.
 */
export function useSwipeNavigation({ hostRef, pageRef, scrimRef }: SwipeNavigationRefs): void {
  const navigate = useNavigate();
  const location = useLocation();
  const haptic = useHaptic();
  const { canGoBack, canGoForward } = useHistoryPosition();

  const gestureRef = useRef<ActiveGesture | null>(null);
  /** Жест доигран, навигация запрошена — ждём смену роута, новые жесты не принимаем. */
  const pendingDirectionRef = useRef<SwipeDirection | null>(null);
  const timersRef = useRef<number[]>([]);

  const clearTimers = useCallback(() => {
    timersRef.current.forEach((id) => window.clearTimeout(id));
    timersRef.current = [];
  }, []);

  const later = useCallback((fn: () => void, delayMs: number) => {
    const id = window.setTimeout(() => {
      timersRef.current = timersRef.current.filter((pending) => pending !== id);
      fn();
    }, delayMs);
    timersRef.current.push(id);
  }, []);

  /** Возвращает слои в исходное состояние: без трансформаций, затемнение выключено. */
  const resetLayers = useCallback(() => {
    const page = pageRef.current;
    const scrim = scrimRef.current;
    if (page !== null) {
      page.style.transition = '';
      page.style.transform = '';
      page.classList.remove('rd-swipe-back', 'rd-swipe-forward');
    }
    if (scrim !== null) {
      scrim.style.transition = '';
      scrim.style.opacity = '';
      scrim.classList.remove('rd-swipe-scrim-on');
    }
  }, [pageRef, scrimRef]);

  /** Рисует кадр жеста: страница сдвинута на `distance`, затемнение тает по мере ухода. */
  const paint = useCallback(
    (direction: SwipeDirection, distance: number, width: number) => {
      const page = pageRef.current;
      const scrim = scrimRef.current;
      if (page === null || scrim === null) return;
      const progress = width > 0 ? Math.min(distance / width, 1) : 0;
      const offset = direction === 'back' ? distance : -distance;
      page.style.transform = `translate3d(${offset}px, 0, 0)`;
      scrim.style.opacity = String(SCRIM_MAX_OPACITY * (1 - progress));
    },
    [pageRef, scrimRef],
  );

  /** Доигрывает уход страницы за край и запрашивает переход по истории. */
  const commit = useCallback(
    (direction: SwipeDirection, width: number) => {
      const page = pageRef.current;
      const scrim = scrimRef.current;
      if (page === null || scrim === null) return;

      haptic.impact('light');
      pendingDirectionRef.current = direction;
      page.style.transition = `transform ${COMMIT_MS}ms cubic-bezier(0.22, 0.61, 0.36, 1)`;
      scrim.style.transition = `opacity ${COMMIT_MS}ms linear`;
      page.style.transform = `translate3d(${direction === 'back' ? width : -width}px, 0, 0)`;
      scrim.style.opacity = '0';

      later(() => navigate(direction === 'back' ? -1 : 1), COMMIT_MS);
      // Если роут не сменился (история оказалась пустой) — не оставляем экран пустым.
      later(() => {
        if (pendingDirectionRef.current === null) return;
        pendingDirectionRef.current = null;
        resetLayers();
      }, NAVIGATION_TIMEOUT_MS);
    },
    [haptic, later, navigate, pageRef, resetLayers, scrimRef],
  );

  /** Возвращает страницу на место: порог не добран, перехода не будет. */
  const cancel = useCallback(() => {
    const page = pageRef.current;
    const scrim = scrimRef.current;
    if (page === null || scrim === null) return;
    page.style.transition = `transform ${CANCEL_MS}ms ease-out`;
    scrim.style.transition = `opacity ${CANCEL_MS}ms linear`;
    page.style.transform = 'translate3d(0, 0, 0)';
    scrim.style.opacity = '0';
    later(resetLayers, CANCEL_MS);
  }, [later, pageRef, resetLayers, scrimRef]);

  useEffect(() => {
    const host = hostRef.current;
    if (host === null) return;

    const startGesture = (event: TouchEvent) => {
      if (pendingDirectionRef.current !== null || gestureRef.current !== null) return;
      if (event.touches.length !== 1) return;
      const touch = event.touches[0];
      if (touch === undefined) return;

      const target = event.target;
      if (target instanceof Element && target.closest(BLOCKING_SELECTOR) !== null) return;

      const width = host.getBoundingClientRect().width;
      const fromLeftEdge = touch.clientX <= EDGE_ZONE_PX;
      const fromRightEdge = touch.clientX >= width - EDGE_ZONE_PX;
      const direction: SwipeDirection | null =
        fromLeftEdge && canGoBack() ? 'back' : fromRightEdge && canGoForward() ? 'forward' : null;
      if (direction === null) return;

      gestureRef.current = {
        direction,
        touchId: touch.identifier,
        startX: touch.clientX,
        startY: touch.clientY,
        lastX: touch.clientX,
        lastTime: event.timeStamp,
        velocity: 0,
        isLocked: false,
      };
    };

    const moveGesture = (event: TouchEvent) => {
      const gesture = gestureRef.current;
      if (gesture === null) return;
      const touch = Array.from(event.touches).find((t) => t.identifier === gesture.touchId);
      if (touch === undefined) return;

      const width = host.getBoundingClientRect().width;
      const travelled =
        gesture.direction === 'back'
          ? touch.clientX - gesture.startX
          : gesture.startX - touch.clientX;
      const verticalDrift = Math.abs(touch.clientY - gesture.startY);

      if (!gesture.isLocked) {
        // Палец ушёл вверх/вниз или в сторону от кромки — это прокрутка, отдаём жест странице.
        if (verticalDrift > DIRECTION_LOCK_PX || travelled < -DIRECTION_LOCK_PX) {
          gestureRef.current = null;
          return;
        }
        if (travelled < DIRECTION_LOCK_PX) return;
        if (travelled < verticalDrift * HORIZONTAL_DOMINANCE) return;
        if (event.target instanceof Element && hasHorizontalScrollAncestor(event.target, host)) {
          gestureRef.current = null;
          return;
        }
        gesture.isLocked = true;
        const page = pageRef.current;
        if (page !== null) {
          // Ввод предыдущего перехода мог не доиграть: CSS-анимация перебивает inline-transform,
          // и страница застыла бы на месте вместо того, чтобы ехать за пальцем.
          page.classList.remove('rd-swipe-enter-back', 'rd-swipe-enter-forward');
          page.classList.add(gesture.direction === 'back' ? 'rd-swipe-back' : 'rd-swipe-forward');
        }
        scrimRef.current?.classList.add('rd-swipe-scrim-on');
      }

      const elapsed = event.timeStamp - gesture.lastTime;
      if (elapsed > 0) {
        // Скорость знаковая: флик обратно к кромке — это отмена, а не быстрый переход.
        const step =
          gesture.direction === 'back'
            ? touch.clientX - gesture.lastX
            : gesture.lastX - touch.clientX;
        gesture.velocity = step / elapsed;
        gesture.lastX = touch.clientX;
        gesture.lastTime = event.timeStamp;
      }

      // Прокрутка отменяется только здесь и только после подтверждения направления.
      if (event.cancelable) event.preventDefault();
      paint(gesture.direction, Math.max(0, Math.min(travelled, width)), width);
    };

    const endGesture = (event: TouchEvent) => {
      const gesture = gestureRef.current;
      if (gesture === null) return;
      const stillTouching = Array.from(event.touches).some((t) => t.identifier === gesture.touchId);
      if (stillTouching) return;

      gestureRef.current = null;
      if (!gesture.isLocked) return;

      const width = host.getBoundingClientRect().width;
      const travelled =
        gesture.direction === 'back' ? gesture.lastX - gesture.startX : gesture.startX - gesture.lastX;
      const isCommitted =
        travelled >= width * COMMIT_DISTANCE_RATIO || gesture.velocity >= COMMIT_VELOCITY;
      if (isCommitted) commit(gesture.direction, width);
      else cancel();
    };

    // Систему, забравшую жест себе (звонок, шторка ОС), не считаем намерением уйти со страницы.
    const abortGesture = () => {
      const gesture = gestureRef.current;
      gestureRef.current = null;
      if (gesture !== null && gesture.isLocked) cancel();
    };

    host.addEventListener('touchstart', startGesture, { passive: true });
    host.addEventListener('touchmove', moveGesture, { passive: false });
    host.addEventListener('touchend', endGesture);
    host.addEventListener('touchcancel', abortGesture);
    return () => {
      host.removeEventListener('touchstart', startGesture);
      host.removeEventListener('touchmove', moveGesture);
      host.removeEventListener('touchend', endGesture);
      host.removeEventListener('touchcancel', abortGesture);
    };
  }, [cancel, canGoBack, canGoForward, commit, hostRef, pageRef, paint, scrimRef]);

  // Роут сменился после доигранного жеста — снимаем сдвиг и вводим новый экран
  // встречным движением, чтобы переход читался как одно непрерывное действие.
  useLayoutEffect(() => {
    const direction = pendingDirectionRef.current;
    if (direction === null) return;
    pendingDirectionRef.current = null;
    clearTimers();
    resetLayers();

    const page = pageRef.current;
    if (page === null) return;
    const enterClass = direction === 'back' ? 'rd-swipe-enter-back' : 'rd-swipe-enter-forward';
    page.classList.add(enterClass);
    later(() => page.classList.remove(enterClass), COMMIT_MS);
  }, [clearTimers, later, location.key, pageRef, resetLayers]);

  useEffect(
    () => () => {
      clearTimers();
    },
    [clearTimers],
  );
}

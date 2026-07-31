import { FC, useCallback, useEffect, useRef, useState } from 'react';
import { COACH_TOURS, type CoachStep } from './tours';
import { useHaptic } from '../../hooks/useHaptic';
import { useCompleteTourMutation } from '../../queries/profile';
import { useAuthStore } from '../../store/useAuthStore';
import type { OnboardingTour } from '../../types/api';

/** Сколько ждём появления цели, прежде чем счесть шаг непоказуемым (мс). */
const TARGET_WAIT_MS = 1800;
/** Как часто перепроверяем, не появилась ли цель (мс). */
const TARGET_POLL_MS = 150;
/** Воздух вокруг подсвеченного элемента — «дырка» чуть больше самой цели (px). */
const HOLE_PADDING_PX = 6;
/** Сколько места нужно под пузырь, чтобы поставить его ПОД целью, а не над (px). */
const BUBBLE_SPACE_PX = 190;
/** Отступ пузыря от кромок экрана (px). */
const BUBBLE_MARGIN_PX = 12;
/** Ширина пузыря (px). Парное значение с `.ct-bubble` в CSS. */
const BUBBLE_WIDTH_PX = 262;
/** Половина клина-хвостика (px). Парное значение с `.ct-tail` в CSS (13px). */
const TAIL_HALF_PX = 6.5;
/** Насколько близко к кромке пузыря пускаем хвостик — дальше начинается скругление угла (px). */
const TAIL_EDGE_INSET_PX = 16;

interface Rect { top: number; left: number; width: number; height: number }

interface Placement {
  hole: Rect;
  /** Область внутри подсветки, которая ОСТАЁТСЯ затемнённой (вырез). */
  cutout: Rect | null;
  bubbleTop: number;
  bubbleLeft: number;
  /** Смещение хвостика ВНУТРИ пузыря: он обязан смотреть на центр цели, а не в свой угол. */
  tailLeft: number;
  /** Пузырь под целью — хвостик смотрит вверх; иначе вниз. */
  below: boolean;
}

interface CoachTourProps {
  /** Какой тур запускать на этом экране. */
  tour: OnboardingTour;
  /**
   * Разрешено ли сейчас. Страница выставляет false, пока данные не приехали: подсказка,
   * прилетевшая на пустой экран, показывает пальцем в никуда.
   */
  ready?: boolean;
  /**
   * Выполнено ли условие шага-задания (у шага задан `gateHint`). Считает СТРАНИЦА: данные,
   * от которых зависит условие, у неё уже есть, и компонент, висящий на каждом экране,
   * не должен ради этого заводить собственные запросы.
   */
  gateSatisfied?: boolean;
}

/**
 * Подсказки-коучмарки одного экрана: затемнение с «дыркой» вокруг реального элемента,
 * одна фраза и кнопка подтверждения. Крестика нет — каждый шаг закрывается кнопкой
 * (решение PO 2026-07-31), поэтому шагов не больше трёх.
 *
 * Тур запускается сам, когда человек впервые попал на экран, и живёт ровно один визит:
 * компонент монтируется вместе со страницей. Пройденным считается ПО ЭТОМУ экрану —
 * закрытый тур клуба ничего не говорит про тур профиля.
 */
export const CoachTour: FC<CoachTourProps> = ({ tour, ready = true, gateSatisfied = false }) => {
  const haptic = useHaptic();
  const completeTour = useCompleteTourMutation();
  const user = useAuthStore((s) => s.user);
  const setUser = useAuthStore((s) => s.setUser);

  const steps = COACH_TOURS[tour] ?? [];
  // Fail-closed: не знаем состояния (профиль ещё не приехал или пришёл без списка туров) —
  // считаем тур пройденным и молчим. Компонент висит на каждом экране, и уронить страницу
  // белым экраном он не имеет права; худшее последствие осторожности — непоказанная подсказка.
  const alreadyDone = user?.onboardingTours?.includes(tour) ?? true;

  const [stepIndex, setStepIndex] = useState(0);
  const [placement, setPlacement] = useState<Placement | null>(null);
  /** Хоть один шаг реально показали — только тогда есть что засчитывать. */
  const shownAnyRef = useRef(false);
  /** Тур доигран (или оказался непоказуемым) — больше не рисуем. */
  const [finished, setFinished] = useState(false);

  const step: CoachStep | undefined = steps[stepIndex];
  const active = !alreadyDone && ready && !finished && step !== undefined;

  // Шаг-задание («заполни профиль»): пока условие не выполнено, кнопки «Далее» нет.
  // Условие считает страница и передаёт готовым флагом — по умолчанию НЕ выполнено:
  // показать «Далее» раньше времени хуже, чем показать позже.
  const locked = step?.gateHint !== undefined && !gateSatisfied;

  /** Считает, где нарисовать дырку, вырез и пузырь для цели. */
  const measure = useCallback((target: Element, cutoutTarget: Element | null): Placement => {
    const box = target.getBoundingClientRect();
    const hole = {
      top: box.top - HOLE_PADDING_PX,
      left: box.left - HOLE_PADDING_PX,
      width: box.width + HOLE_PADDING_PX * 2,
      height: box.height + HOLE_PADDING_PX * 2,
    };
    // Вырез рисуется ВПРИТЫК к элементу, без воздуха: он должен читаться как аккуратная
    // выемка в подсвеченном блоке, а не как вторая дырка рядом.
    const cutoutBox = cutoutTarget?.getBoundingClientRect() ?? null;
    const cutout: Rect | null = cutoutBox === null ? null : {
      top: cutoutBox.top - 4,
      left: cutoutBox.left - 4,
      width: cutoutBox.width + 8,
      height: cutoutBox.height + 8,
    };
    const below = window.innerHeight - box.bottom > BUBBLE_SPACE_PX;
    const maxLeft = window.innerWidth - BUBBLE_WIDTH_PX - BUBBLE_MARGIN_PX;
    const bubbleLeft = Math.min(Math.max(box.left, BUBBLE_MARGIN_PX), Math.max(maxLeft, BUBBLE_MARGIN_PX));
    // Хвостик считается ОТ ЦЕЛИ, а не от угла пузыря: у цели справа экрана пузырь упирается
    // в кромку и уезжает влево от неё — прибитый к углу хвостик показывал бы в пустоту.
    // По краям пузыря оставляем запас, иначе клин вылезает за его скругление.
    const targetCenter = box.left + box.width / 2;
    const tailLeft = Math.min(
      Math.max(targetCenter - bubbleLeft - TAIL_HALF_PX, TAIL_EDGE_INSET_PX),
      BUBBLE_WIDTH_PX - TAIL_EDGE_INSET_PX - TAIL_HALF_PX * 2,
    );
    return {
      hole,
      cutout,
      below,
      bubbleTop: below ? hole.top + hole.height + 12 : hole.top - 12,
      bubbleLeft,
      tailLeft,
    };
  }, []);

  // Ищем цель текущего шага. Страницы приезжают асинхронно, поэтому не «нет элемента —
  // пропустили», а короткое ожидание: цель может дорисоваться через кадр-другой.
  useEffect(() => {
    if (!active || step === undefined) return;
    // Пока открыта шторка или модалка, подсказка не лезет: их слои выше, и «дырка»
    // подсветила бы то, что человек сейчас всё равно не видит.
    if (document.querySelector('[role="dialog"]') !== null) {
      setFinished(true);
      return;
    }

    let waited = 0;
    let cancelled = false;

    const tick = () => {
      if (cancelled) return;
      const target = document.querySelector(step.target);
      if (target !== null) {
        target.scrollIntoView({ block: 'center', behavior: 'auto' });
        // Вырез необязателен: нет его на экране — просто подсвечиваем блок целиком.
        const cutoutTarget = step.cutout === undefined ? null : document.querySelector(step.cutout);
        setPlacement(measure(target, cutoutTarget));
        shownAnyRef.current = true;
        haptic.impact('light');
        return;
      }
      waited += TARGET_POLL_MS;
      if (waited >= TARGET_WAIT_MS) {
        // Цели на экране нет — шаг молча пропускаем, тур не имеет права зависнуть.
        setStepIndex((i) => i + 1);
        return;
      }
      window.setTimeout(tick, TARGET_POLL_MS);
    };
    // Первый замер — после кадра отрисовки: сразу после монтирования раскладка ещё не устоялась.
    const raf = window.requestAnimationFrame(tick);

    return () => {
      cancelled = true;
      window.cancelAnimationFrame(raf);
    };
    // haptic пересоздаётся каждый рендер и в зависимости не годится — шаг перезапускался бы вечно.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, step, measure]);

  // Шаги кончились. Засчитываем тур, только если человек реально что-то увидел: иначе
  // экран без целей молча сжёг бы тур, ничего не показав.
  useEffect(() => {
    if (alreadyDone || finished || stepIndex < steps.length || steps.length === 0) return;
    setFinished(true);
    if (!shownAnyRef.current) return;
    completeTour
      .mutateAsync(tour)
      .then(setUser)
      // Отметка не прошла — не беда: тур просто предложится ещё раз. Запирать человека
      // в подсказках из-за упавшей сети нельзя.
      .catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stepIndex, steps.length, alreadyDone, finished, tour]);

  // Пока тур идёт — страница под ним не прокручивается: «дырка» посчитана от текущей
  // раскладки, и уехавший скролл увёл бы подсветку с цели. Исключение — шаг-задание:
  // там человек обязан дотянуться до элемента, и запирать страницу нельзя.
  useEffect(() => {
    if (!active || locked) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, [active, locked]);

  if (!active || placement === null || step === undefined) return null;

  const isLastStep = stepIndex === steps.length - 1;

  return (
    // `data-swipe-nav="off"` — под затемнением не должен срабатывать навигационный свайп.
    // На шаге-задании затемнение пропускает тапы насквозь (`ct-root-open`): человеку нужно
    // дотянуться до самого элемента, иначе условие «сначала заполни» невыполнимо.
    <div className={locked ? 'ct-root ct-root-open' : 'ct-root'} data-swipe-nav="off">
      {/* Затемнение с дыркой — SVG-маска, а не box-shadow: тень умеет ровно одну область,
          а шагу нужен ещё и ВЫРЕЗ внутри подсветки (белый прямоугольник поверх чёрного
          возвращает затемнение). Скруглённые углы выреза и дают тот самый изгиб. */}
      <svg className="ct-scrim" aria-hidden="true">
        <defs>
          <mask id="ct-mask">
            <rect x="0" y="0" width="100%" height="100%" fill="white" />
            <rect
              x={placement.hole.left}
              y={placement.hole.top}
              width={placement.hole.width}
              height={placement.hole.height}
              rx="18"
              fill="black"
            />
            {placement.cutout !== null && (
              <rect
                x={placement.cutout.left}
                y={placement.cutout.top}
                width={placement.cutout.width}
                height={placement.cutout.height}
                rx={placement.cutout.height / 2}
                fill="white"
              />
            )}
          </mask>
        </defs>
        <rect x="0" y="0" width="100%" height="100%" fill="rgba(6, 6, 9, 0.8)" mask="url(#ct-mask)" />
        <rect
          className="ct-ring"
          x={placement.hole.left}
          y={placement.hole.top}
          width={placement.hole.width}
          height={placement.hole.height}
          rx="18"
        />
        {placement.cutout !== null && (
          <rect
            className="ct-ring"
            x={placement.cutout.left}
            y={placement.cutout.top}
            width={placement.cutout.width}
            height={placement.cutout.height}
            rx={placement.cutout.height / 2}
          />
        )}
      </svg>
      <div
        className={placement.below ? 'ct-bubble' : 'ct-bubble ct-bubble-above'}
        style={{ top: placement.bubbleTop, left: placement.bubbleLeft }}
      >
        <span
          className={placement.below ? 'ct-tail ct-tail-up' : 'ct-tail ct-tail-down'}
          style={{ left: placement.tailLeft }}
        />
        <div className="ct-text">{renderWithAccent(step)}</div>
        <div className="ct-foot">
          <div className="ct-prog">
            {steps.map((s, i) => (
              <i key={`${s.target}-${i}`} className={i === stepIndex ? 'ct-prog-on' : undefined} />
            ))}
          </div>
          {locked ? (
            <span className="ct-gate">{step.gateHint}</span>
          ) : (
            <button
              type="button"
              className="ct-next"
              onClick={() => {
                haptic.impact('light');
                setPlacement(null);
                setStepIndex((i) => i + 1);
              }}
            >
              {isLastStep ? 'Понятно' : 'Далее'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
};

/** Подсвечивает акцентный кусок фразы, не разрывая её на данные в двух полях. */
function renderWithAccent(step: CoachStep) {
  if (step.accent === undefined) return step.text;
  const at = step.text.indexOf(step.accent);
  if (at < 0) return step.text;
  return (
    <>
      {step.text.slice(0, at)}
      <em>{step.accent}</em>
      {step.text.slice(at + step.accent.length)}
    </>
  );
}

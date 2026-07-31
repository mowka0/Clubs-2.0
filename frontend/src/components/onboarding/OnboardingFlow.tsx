import { FC, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Toast } from '../Toast';
import { OnboardingSlide } from './OnboardingSlide';
import { ONBOARDING_FINAL_CTA, ONBOARDING_SLIDES } from './slides';
import { useHaptic } from '../../hooks/useHaptic';
import { useCompleteTourMutation } from '../../queries/profile';
import { useAuthStore } from '../../store/useAuthStore';

/** Доля ширины экрана, после которой отпускание пальца засчитывается как листание. */
const SWIPE_COMMIT_RATIO = 0.22;
/** Скорость (px/ms), при которой короткий флик листает, не добрав дистанцию. */
const SWIPE_COMMIT_VELOCITY = 0.4;
/** Во сколько раз ослабляется сдвиг за крайними слайдами — упор пальцем в «стенку». */
const EDGE_RESISTANCE = 3;
/** Сколько лента доезжает до места после отпускания (мс). */
const SETTLE_MS = 260;
/** Сколько ждём бездействия, прежде чем показать жест призрачной рукой (мс). */
const HAND_HINT_DELAY_MS = 2500;

/**
 * Интро первого входа: три слайда, по одной мысли на каждом. Показывается вместо всего
 * приложения, пока человек не прошёл ни одного тура (гейт в Layout) — не роут, иначе его
 * обошли бы навигацией.
 *
 * Свайпу здесь НЕ учат словами: лента едет за пальцем, и это тот же самый жест, которым
 * человек будет ходить назад-вперёд по всему приложению. Если он две с половиной секунды
 * ничего не делает, движение показывает призрачная рука.
 *
 * Пройденным интро считается только по тапу «Погнали!» на последнем слайде. Кнопки
 * «Пропустить» нет: бросивший на середине увидит интро снова — он ведь так и не узнал,
 * что здесь можно.
 */
export const OnboardingFlow: FC = () => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  const completeTour = useCompleteTourMutation();
  const setUser = useAuthStore((s) => s.setUser);

  const viewportRef = useRef<HTMLDivElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);

  const [index, setIndex] = useState(0);
  const [error, setError] = useState<string | null>(null);
  /** Человек уже листал сам — подсказку рукой больше не показываем. */
  const [hasSwiped, setHasSwiped] = useState(false);
  const [handHintOn, setHandHintOn] = useState(false);

  const isLast = index === ONBOARDING_SLIDES.length - 1;

  // Жест держим в ref, а не в состоянии: он меняется на каждый кадр касания, и ререндер
  // на каждое движение пальца ленту бы дёргал.
  const gestureRef = useRef<{ startX: number; lastX: number; lastTime: number; velocity: number } | null>(null);

  /** Ставит ленту в положение слайда `at`, сдвинутое на `shift` пикселей за пальцем. */
  const paint = (at: number, shift: number, animated: boolean) => {
    const track = trackRef.current;
    const width = viewportRef.current?.clientWidth ?? 0;
    if (track === null) return;
    track.style.transition = animated ? `transform ${SETTLE_MS}ms cubic-bezier(.22,.61,.36,1)` : '';
    track.style.transform = `translate3d(${-at * width + shift}px, 0, 0)`;
  };

  // Держим ленту на месте при смене слайда и при повороте экрана: положение считается
  // от ширины вьюпорта, а она переживает ресайз.
  useEffect(() => {
    paint(index, 0, true);
    const onResize = () => paint(index, 0, false);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [index]);

  // Подсказка рукой — только пока человек ни разу не листал сам.
  useEffect(() => {
    if (hasSwiped) return;
    const timer = window.setTimeout(() => setHandHintOn(true), HAND_HINT_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [hasSwiped, index]);

  const goTo = (next: number) => {
    if (next < 0 || next >= ONBOARDING_SLIDES.length) return;
    haptic.impact('light');
    setIndex(next);
  };

  const handleTouchStart = (clientX: number) => {
    gestureRef.current = { startX: clientX, lastX: clientX, lastTime: Date.now(), velocity: 0 };
  };

  const handleTouchMove = (clientX: number) => {
    const gesture = gestureRef.current;
    if (gesture === null) return;
    const now = Date.now();
    const elapsed = now - gesture.lastTime;
    if (elapsed > 0) gesture.velocity = (clientX - gesture.lastX) / elapsed;
    gesture.lastX = clientX;
    gesture.lastTime = now;

    let shift = clientX - gesture.startX;
    // За первым и последним слайдом лента идёт туго: видно, что дальше ничего нет.
    const pullsBeyondStart = index === 0 && shift > 0;
    const pullsBeyondEnd = isLast && shift < 0;
    if (pullsBeyondStart || pullsBeyondEnd) shift /= EDGE_RESISTANCE;
    paint(index, shift, false);
  };

  const handleTouchEnd = (clientX: number | undefined) => {
    const gesture = gestureRef.current;
    gestureRef.current = null;
    // Координаты нет — значит, и жеста не было. Подставлять 0 нельзя: это прочиталось бы
    // как «палец уехал в левый край» и безусловно листало бы вперёд.
    if (gesture === null || clientX === undefined) return;

    const shift = clientX - gesture.startX;
    const width = viewportRef.current?.clientWidth ?? 0;
    const farEnough = width > 0 && Math.abs(shift) > width * SWIPE_COMMIT_RATIO;
    const fastEnough = Math.abs(gesture.velocity) > SWIPE_COMMIT_VELOCITY;

    if (!farEnough && !fastEnough) {
      paint(index, 0, true);
      return;
    }

    const next = index + (shift < 0 ? 1 : -1);
    if (next < 0 || next >= ONBOARDING_SLIDES.length) {
      paint(index, 0, true);
      return;
    }
    setHasSwiped(true);
    setHandHintOn(false);
    goTo(next);
  };

  /**
   * «Погнали!» — единственный выход из интро, ведёт всех в профиль. Порядок шагов здесь
   * не стилистика, а единственный рабочий:
   *
   * 1. Ждём подтверждения сервера. Оптимистично гасить интро нельзя: упавший запрос
   *    оставил бы человека без отметки, но уже внутри приложения.
   * 2. Навигируем. Гейт ещё закрыт (в сторе туров всё ещё нет), поэтому Layout продолжает
   *    рисовать интро — смены экрана не видно, мигания нет.
   * 3. И только теперь кладём профиль в стор: гейт открывается уже на нужной странице.
   *
   * Обратный порядок ломался: `setUser` открывал гейт, интро размонтировалось, и навигация
   * не выполнялась вовсе — человек оставался на главной.
   */
  const enter = async () => {
    if (completeTour.isPending) return;
    haptic.impact('medium');
    try {
      const user = await completeTour.mutateAsync('INTRO');
      navigate('/profile');
      setUser(user);
    } catch {
      haptic.notify('error');
      setError('Не удалось продолжить. Проверьте связь и попробуйте ещё раз.');
    }
  };

  return (
    <div className="ob-root">
      {/* Градиентный меш: четыре размытых пятна, дрейфуют через transform. */}
      <div className="ob-mesh" aria-hidden="true">
        <i className="ob-mesh-1" />
        <i className="ob-mesh-2" />
        <i className="ob-mesh-3" />
        <i className="ob-mesh-4" />
      </div>
      <div className="ob-veil" aria-hidden="true" />

      <div className="ob-dots">
        {ONBOARDING_SLIDES.map((slide, i) => (
          <span key={slide.micro} className={i === index ? 'ob-dot ob-dot-on' : 'ob-dot'} />
        ))}
      </div>

      <div
        className="ob-viewport"
        ref={viewportRef}
        onTouchStart={(e) => handleTouchStart(e.changedTouches[0]?.clientX ?? 0)}
        onTouchMove={(e) => handleTouchMove(e.changedTouches[0]?.clientX ?? 0)}
        onTouchEnd={(e) => handleTouchEnd(e.changedTouches[0]?.clientX)}
        onTouchCancel={() => {
          gestureRef.current = null;
          paint(index, 0, true);
        }}
      >
        <div className="ob-track" ref={trackRef}>
          {ONBOARDING_SLIDES.map((slide) => (
            <div className="ob-cell" key={slide.micro}>
              <OnboardingSlide slide={slide} />
            </div>
          ))}
        </div>
        {handHintOn && !isLast && <span className="ob-hand" aria-hidden="true" />}
      </div>

      <div className="ob-cta">
        {isLast ? (
          <button type="button" className="rd-btn-primary" onClick={enter} disabled={completeTour.isPending}>
            {completeTour.isPending ? 'Секунду…' : ONBOARDING_FINAL_CTA}
          </button>
        ) : (
          <button type="button" className="rd-btn-primary" onClick={() => goTo(index + 1)}>
            Дальше
          </button>
        )}
      </div>

      {error !== null && <Toast message={error} onClose={() => setError(null)} />}
    </div>
  );
};

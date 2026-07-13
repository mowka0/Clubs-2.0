import { FC, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Toast } from '../Toast';
import { OnboardingSlide } from './OnboardingSlide';
import { ONBOARDING_SLIDES, type OnboardingDoorCta } from './slides';
import { useHaptic } from '../../hooks/useHaptic';
import { useCompleteOnboardingMutation } from '../../queries/profile';
import { useAuthStore } from '../../store/useAuthStore';

/** Насколько далеко нужно смахнуть, чтобы это считалось листанием, а не дрожанием пальца (px). */
const SWIPE_THRESHOLD_PX = 50;

/**
 * Карусель первого входа: три слайда, вопросов нет. Показывается вместо всего приложения,
 * пока `user.onboardedAt == null` (гейт в Layout) — не роут, иначе её обошли бы навигацией.
 *
 * Пройденным онбординг считается только по тапу главной кнопки слайда — «двери».
 * Кнопки «Пропустить» нет: бросивший на середине увидит карусель снова, он ведь так
 * и не узнал, что здесь можно. Дверь не открывает форму, а приводит на страницу, где та
 * живёт, и подсвечивает её — чтобы во второй раз человек нашёл её сам.
 */
export const OnboardingFlow: FC = () => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  const completeOnboarding = useCompleteOnboardingMutation();
  const setUser = useAuthStore((s) => s.setUser);

  const [index, setIndex] = useState(0);
  const [touchStartX, setTouchStartX] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const slide = ONBOARDING_SLIDES[index];
  const isFirst = index === 0;
  const isLast = index === ONBOARDING_SLIDES.length - 1;

  const goTo = (next: number) => {
    if (next < 0 || next >= ONBOARDING_SLIDES.length) return;
    haptic.impact('light');
    setIndex(next);
  };

  const handleTouchEnd = (endX: number | undefined) => {
    const startX = touchStartX;
    setTouchStartX(null);
    // Координаты нет — значит, свайпа и не было. Подставлять сюда 0 нельзя: это прочиталось бы
    // как «палец уехал в левый край» и безусловно листало бы вперёд.
    if (startX === null || endX === undefined) return;
    const shift = endX - startX;
    if (Math.abs(shift) < SWIPE_THRESHOLD_PX) return;
    goTo(shift < 0 ? index + 1 : index - 1);
  };

  if (!slide) return null;
  const { doorCta, secondary } = slide;

  /**
   * Дверь. Порядок шагов здесь — не стилистика, а единственный рабочий:
   *
   * 1. Ждём подтверждения сервера. Оптимистично гасить карусель нельзя: упавший запрос
   *    оставил бы человека с пустым `onboardedAt`, но уже внутри приложения.
   * 2. Навигируем. Гейт ещё закрыт (`onboardedAt` в сторе всё ещё null), поэтому Layout
   *    продолжает рисовать карусель — смены экрана не видно, мигания нет.
   * 3. И только теперь кладём профиль в стор: гейт открывается уже на нужной странице,
   *    вместе с меткой подсветки в `location.state`.
   *
   * Обратный порядок (профиль → навигация) ломался: `setUser` открывал гейт, карусель
   * размонтировалась, и навигация не выполнялась вовсе — человек оставался на главной,
   * а подсветка не приезжала никуда.
   */
  const enterDoor = async (cta: OnboardingDoorCta) => {
    if (completeOnboarding.isPending) return;
    haptic.impact('medium');
    try {
      const user = await completeOnboarding.mutateAsync(cta.door);
      navigate(cta.to, { state: { highlight: cta.highlight } });
      setUser(user);
    } catch {
      haptic.notify('error');
      setError('Не удалось продолжить. Проверьте связь и попробуйте ещё раз.');
    }
  };

  return (
    <div
      className="ob-root"
      onTouchStart={(e) => setTouchStartX(e.changedTouches[0]?.clientX ?? null)}
      onTouchEnd={(e) => handleTouchEnd(e.changedTouches[0]?.clientX)}
    >
      {!isFirst && (
        <button
          type="button"
          className="ob-arrow ob-arrow-l"
          onClick={() => goTo(index - 1)}
          aria-label="Предыдущий слайд"
        >
          ‹
        </button>
      )}
      {!isLast && (
        <button
          type="button"
          className="ob-arrow ob-arrow-r ob-arrow-pulse"
          onClick={() => goTo(index + 1)}
          aria-label="Следующий слайд"
        >
          ›
        </button>
      )}

      <div className="ob-dots">
        {ONBOARDING_SLIDES.map((_, i) => (
          <span key={i} className={i === index ? 'ob-dot ob-dot-on' : 'ob-dot'} />
        ))}
      </div>

      <div className="ob-body">
        <OnboardingSlide slide={slide} />
      </div>

      <div className="ob-cta">
        {doorCta ? (
          <button
            type="button"
            className="rd-btn-primary"
            onClick={() => enterDoor(doorCta)}
            disabled={completeOnboarding.isPending}
          >
            {completeOnboarding.isPending ? 'Секунду…' : doorCta.label}
          </button>
        ) : (
          <button type="button" className="rd-btn-primary" onClick={() => goTo(index + 1)}>
            Дальше
          </button>
        )}

        {secondary && (
          <button
            type="button"
            className="ob-ghost"
            onClick={() => goTo(index + secondary.step)}
            disabled={completeOnboarding.isPending}
          >
            {secondary.label}
          </button>
        )}
      </div>

      {error && <Toast message={error} onClose={() => setError(null)} />}
    </div>
  );
};

import { FC } from 'react';
import { useHaptic } from '../../hooks/useHaptic';
import { readClubSetupProgress } from '../../utils/clubSetupProgress';

interface ClubSetupBannerProps {
  clubId: string;
  /** Открыть мастер. Навигацию делает страница — баннер не знает про роуты. */
  onOpen: () => void;
}

/**
 * Приглашение наполнить клуб, рождённый из чата, — на его же странице.
 *
 * Мастер человеку не подсовывается: приложение открывается на странице клуба, и он сам решает,
 * когда сесть за описание и обложку (решение PO 2026-08-17). Раз дверь в мастер единственная,
 * она обязана быть заметной — акцентная рамка со свечением и полоска пройденных шагов. Без
 * пульсации и мигания: блок не один на странице, а вечная анимация быстро начинает раздражать.
 *
 * Прогресс читается при рендере, а не хранится в состоянии: страница перемонтируется при каждом
 * возврате из мастера, и значение всегда свежее.
 */
export const ClubSetupBanner: FC<ClubSetupBannerProps> = ({ clubId, onOpen }) => {
  const haptic = useHaptic();
  // Шкала рисуется по числу шагов ИЗ ПРОГРЕССА: последний шаг (права бота) появляется не
  // всегда, и фиксированные пять сегментов врали бы на один (баг PO 2026-08-19).
  const { step: savedStep, totalSteps } = readClubSetupProgress(clubId);
  const isStarted = savedStep > 1;

  return (
    <div className="rd-glass rd-setup-cta">
      <div className="rd-setup-cta-head">
        <span className="rd-setup-cta-dot" aria-hidden="true" />
        Клуб ещё не заполнен
      </div>
      <p className="rd-setup-cta-sub">
        {isStarted
          ? 'Осталось немного — участники увидят страницу такой, какой вы её оставите.'
          : 'Добавьте город, описание и обложку — участникам будет что смотреть.'}
      </p>

      {isStarted && (
        <div className="rd-setup-cta-progress">
          <div className="rd-setup-cta-bar" aria-hidden="true">
            {Array.from({ length: totalSteps }, (_, i) => (
              <span key={i} className={i < savedStep - 1 ? 'rd-setup-cta-seg rd-done' : 'rd-setup-cta-seg'} />
            ))}
          </div>
          <span className="rd-setup-cta-step">Шаг {savedStep} из {totalSteps}</span>
        </div>
      )}

      <button
        type="button"
        className="rd-btn-primary rd-setup-cta-btn"
        onClick={() => { haptic.impact('medium'); onOpen(); }}
      >
        {isStarted ? 'Продолжить заполнение' : 'Заполнить клуб'}
      </button>
    </div>
  );
};

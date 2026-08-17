import { FC } from 'react';
import { useHaptic } from '../../hooks/useHaptic';
import {
  CLUB_SETUP_TOTAL_STEPS,
  readClubSetupStep,
} from '../../utils/clubSetupProgress';

interface ClubSetupBannerProps {
  clubId: string;
  /** Открыть мастер. Навигацию делает страница — баннер не знает про роуты. */
  onOpen: () => void;
}

/**
 * Приглашение наполнить клуб, рождённый из чата, — на его же странице.
 *
 * Мастер человеку не подсовывается: приложение открывается на странице клуба, и он сам решает,
 * когда сесть за описание и обложку (решение PO 2026-08-17). Значит на странице должна быть
 * заметная дверь внутрь, и она обязана помнить, где заполнение прервалось — иначе «продолжить»
 * читалось бы как «начать заново».
 *
 * Прогресс читается при рендере, а не хранится в состоянии: страница перемонтируется при каждом
 * возврате из мастера, и значение всегда свежее.
 */
export const ClubSetupBanner: FC<ClubSetupBannerProps> = ({ clubId, onOpen }) => {
  const haptic = useHaptic();
  const savedStep = readClubSetupStep(clubId);
  const isStarted = savedStep > 1;

  return (
    <div className="rd-glass rd-empty" style={{ marginBottom: 14 }}>
      <div className="rd-ttl">Клуб ещё не заполнен</div>
      <div className="rd-sub">
        {isStarted
          ? `Вы остановились на шаге ${savedStep} из ${CLUB_SETUP_TOTAL_STEPS}.`
          : 'Добавьте город, описание и обложку — участникам будет что смотреть.'}
      </div>
      <button
        type="button"
        className="rd-btn-primary"
        style={{ width: '100%', marginTop: 10 }}
        onClick={() => { haptic.impact('light'); onOpen(); }}
      >
        {isStarted ? 'Продолжить заполнение' : 'Заполнить клуб'}
      </button>
    </div>
  );
};

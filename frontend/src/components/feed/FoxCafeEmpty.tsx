import { FC, useEffect, useRef, useState } from 'react';
import { useHaptic } from '../../hooks/useHaptic';
import foxCafeUrl from '../../assets/mascot/fox-cafe.png';

interface FoxCafeEmptyProps {
  title: string;
  description: string;
  ctaLabel?: string;
  onCta?: () => void;
}

/**
 * Сколько держится «залп» после тапа по лису (мс). Парное значение с CSS:
 * за это время успевают отыграть ускоренные циклы пара и блика (rd-boost).
 */
const BOOST_DURATION_MS = 1600;

/**
 * Анимированная пустая заставка «лис ждёт за столиком кафе».
 *
 * Вся жизнь сцены — чистый CSS (styles/redesign.css, блок `.rd-foxcafe*`):
 * дыхание, пар над чашкой, блик по очкам, пылинки, дышащая тень. JS здесь
 * только вешает класс rd-boost на тап — лис «приседает», пар бьёт залпом.
 */
export const FoxCafeEmpty: FC<FoxCafeEmptyProps> = ({ title, description, ctaLabel, onCta }) => {
  const haptic = useHaptic();
  const [boosted, setBoosted] = useState(false);
  const boostTimer = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(boostTimer.current), []);

  const handleFoxTap = () => {
    haptic.impact('light');
    setBoosted(true);
    window.clearTimeout(boostTimer.current);
    boostTimer.current = window.setTimeout(() => setBoosted(false), BOOST_DURATION_MS);
  };

  return (
    <div className="rd-foxcafe">
      <div className={boosted ? 'rd-foxcafe-stage rd-boost' : 'rd-foxcafe-stage'}>
        <div className="rd-foxcafe-dust" aria-hidden="true">
          <i /><i /><i /><i /><i /><i />
        </div>
        <div className="rd-foxcafe-tilt">
          <button
            type="button"
            className="rd-foxcafe-fox"
            onClick={handleFoxTap}
            aria-label="Лис ждёт за столиком"
          >
            <img src={foxCafeUrl} alt="" draggable={false} />
            <svg className="rd-foxcafe-steam" viewBox="0 0 64 96" aria-hidden="true">
              <path d="M22 88 C 16 72, 30 62, 22 46 C 15 32, 26 24, 22 10" />
              <path d="M36 90 C 30 76, 44 66, 36 50 C 29 36, 40 28, 36 14" />
              <path d="M48 86 C 43 74, 54 64, 47 50 C 41 38, 50 30, 46 18" />
            </svg>
            <span className="rd-foxcafe-glint" aria-hidden="true"><i /></span>
          </button>
          <div className="rd-foxcafe-shadow" aria-hidden="true" />
        </div>
      </div>

      <div className="rd-foxcafe-title">{title}</div>
      <div className="rd-foxcafe-sub">{description}</div>
      {ctaLabel && onCta && (
        <button type="button" className="rd-ghost-btn" onClick={onCta}>{ctaLabel}</button>
      )}

      <div className="rd-glass rd-foxcafe-soon" aria-hidden="true">
        <span className="rd-foxcafe-soon-cap">скоро здесь</span>
        <span className="rd-foxcafe-soon-cover">📅</span>
        <span className="rd-foxcafe-soon-lines"><i /><i /></span>
      </div>
    </div>
  );
};

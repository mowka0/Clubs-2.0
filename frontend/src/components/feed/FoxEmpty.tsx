import { FC, useEffect, useRef, useState } from 'react';
import { useHaptic } from '../../hooks/useHaptic';

interface FoxEmptyCta {
  label: string;
  onClick: () => void;
}

interface FoxEmptyProps {
  /** URL арта сцены (import из assets/mascot). */
  art: string;
  /** Подпись лиса для скринридера. */
  artLabel?: string;
  /** error → role="alert": сцена та же, но семантика ошибки для скринридеров. */
  variant?: 'empty' | 'error';
  title: string;
  description: string;
  /** Акцентная кнопка действия. */
  primary?: FoxEmptyCta;
  /** Вторая, ghost-кнопка (например «Сменить город»). */
  secondary?: FoxEmptyCta;
  /** Пар над чашкой и блик по очкам: якоря в % подогнаны ТОЛЬКО под арт кафе. */
  cafeEffects?: boolean;
  /** Эмодзи карточки «скоро здесь»; не задан — карточка не рендерится. */
  soonIcon?: string;
}

/**
 * Сколько держится «залп» после тапа по лису (мс). Парное значение с CSS:
 * за это время успевают отыграть ускоренные циклы пара и блика (rd-boost).
 */
const BOOST_DURATION_MS = 1600;

/**
 * Единый примитив пустых состояний с лисом-маскотом (аудит empty-states §4).
 *
 * Сцена живёт на CSS (styles/redesign.css, блок `.rd-foxcafe*`): вход-пружина,
 * дыхание + микронаклон, пылинки, дышащая тень. JS вешает только rd-boost на тап
 * (лис «приседает», хаптика). Роль-развилка текстов — на стороне вызывающего,
 * компонент про роли не знает.
 */
export const FoxEmpty: FC<FoxEmptyProps> = ({
  art,
  artLabel = 'Лис-маскот',
  variant = 'empty',
  title,
  description,
  primary,
  secondary,
  cafeEffects = false,
  soonIcon,
}) => {
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
    <div className="rd-foxcafe" role={variant === 'error' ? 'alert' : undefined}>
      <div className={boosted ? 'rd-foxcafe-stage rd-boost' : 'rd-foxcafe-stage'}>
        <div className="rd-foxcafe-dust" aria-hidden="true">
          <i /><i /><i /><i /><i /><i />
        </div>
        <div className="rd-foxcafe-tilt">
          <button type="button" className="rd-foxcafe-fox" onClick={handleFoxTap} aria-label={artLabel}>
            <img src={art} alt="" draggable={false} />
            {cafeEffects && (
              <>
                <svg className="rd-foxcafe-steam" viewBox="0 0 64 96" aria-hidden="true">
                  <path d="M22 88 C 16 72, 30 62, 22 46 C 15 32, 26 24, 22 10" />
                  <path d="M36 90 C 30 76, 44 66, 36 50 C 29 36, 40 28, 36 14" />
                  <path d="M48 86 C 43 74, 54 64, 47 50 C 41 38, 50 30, 46 18" />
                </svg>
                <span className="rd-foxcafe-glint" aria-hidden="true"><i /></span>
              </>
            )}
          </button>
          <div className="rd-foxcafe-shadow" aria-hidden="true" />
        </div>
      </div>

      <div className="rd-foxcafe-title">{title}</div>
      <div className="rd-foxcafe-sub">{description}</div>
      {(primary || secondary) && (
        <div className="rd-fox-actions">
          {primary && (
            <button type="button" className="rd-fox-btn" onClick={primary.onClick}>{primary.label}</button>
          )}
          {secondary && (
            <button type="button" className="rd-ghost-btn" onClick={secondary.onClick}>{secondary.label}</button>
          )}
        </div>
      )}

      {soonIcon && (
        <div className="rd-glass rd-foxcafe-soon" aria-hidden="true">
          <span className="rd-foxcafe-soon-cap">скоро здесь</span>
          <span className="rd-foxcafe-soon-cover">{soonIcon}</span>
          <span className="rd-foxcafe-soon-lines"><i /><i /></span>
        </div>
      )}
    </div>
  );
};

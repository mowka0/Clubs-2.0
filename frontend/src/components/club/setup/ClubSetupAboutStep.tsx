import { FC } from 'react';
import { ClubInterestsPicker } from '../ClubInterestsPicker';
import type { ClubSetupStepProps } from './types';

/** Потолок описания, совпадает с VARCHAR(500). */
const DESCRIPTION_MAX = 500;

type ClubSetupAboutStepProps = ClubSetupStepProps;

/**
 * Шаг 3: описание и темы. Одна мысль «о чём клуб», просто вторая её половина записана
 * словами общего словаря — по ним работает поиск.
 */
export const ClubSetupAboutStep: FC<ClubSetupAboutStepProps> = ({
  club,
  draft,
  onDraftChange,
  saving,
  error,
  onSaveAndNext,
  onGoToStep,
}) => {
  const description = draft.description ?? club.description;
  const interests = draft.interests ?? club.interests;

  return (
    <>
      <h1 className="rd-wz-q">О чём ваш клуб?</h1>
      <p className="rd-wz-qsub">Это первое, что прочитают участники, открыв страницу.</p>

      <div className="rd-wz-lbl">Описание</div>
      <textarea
        className="rd-textarea rd-wz-area"
        value={description}
        maxLength={DESCRIPTION_MAX}
        placeholder="Бегаем по вторникам и субботам в Сокольниках. Темп разный, догоняем всех."
        onChange={(e) => onDraftChange({ description: e.target.value })}
        aria-label="Описание клуба"
      />

      {/* Темы — продолжение описания: «о чём клуб» словами общего словаря. По ним же
          работает поиск, поэтому свободный ввод здесь только последним шагом
          (club-interests.md). Полка чипов подставляется по категории клуба. */}
      <div className="rd-wz-lbl">Темы</div>
      <ClubInterestsPicker
        category={club.category}
        value={interests}
        onChange={(next) => onDraftChange({ interests: next })}
      />

      <button
        type="button"
        className="rd-btn-primary rd-wz-next"
        disabled={saving}
        onClick={() => onSaveAndNext({ description: description.trim(), interests }, 4)}
      >
        Дальше
      </button>
      <button type="button" className="rd-ghost-btn rd-wz-skip" onClick={() => onGoToStep(4)}>
        Пропустить
      </button>
      {error}
    </>
  );
};

import { FC } from 'react';
import { ClubCoverButton } from '../ClubCoverButton';
import type { ClubSetupStepProps } from './types';

type ClubSetupCoverStepProps = Pick<ClubSetupStepProps, 'club' | 'draft' | 'onGoToStep'> & {
  /** Впереди ещё шаг прав бота — тогда это не финал мастера. */
  hasRightsStep: boolean;
  onFinish: () => void;
};

/**
 * Шаг 4: обложка и превью страницы.
 *
 * Обложка грузится сразу на сервер (`ClubCoverButton`), поэтому сохранять шагу нечего —
 * кнопки только ведут дальше. Превью здесь единственное место, где орг видит клуб глазами
 * участника до того, как сам его презентует.
 */
export const ClubSetupCoverStep: FC<ClubSetupCoverStepProps> = ({
  club,
  draft,
  hasRightsStep,
  onGoToStep,
  onFinish,
}) => {
  const name = draft.name ?? club.name;
  const cityLabel = draft.city?.name ?? (club.cityId ? club.city : null);
  const interests = draft.interests ?? club.interests;
  const goNext = () => (hasRightsStep ? onGoToStep(5) : onFinish());

  return (
    <>
      <h1 className="rd-wz-q">Обложка клуба</h1>
      <p className="rd-wz-qsub">Так страницу увидят участники.</p>

      <div className="rd-wz-cover-slot">
        {club.coverUrl
          ? <img className="rd-wz-cover-img" src={club.coverUrl} alt="" />
          : <span className="rd-wz-cover-empty">Обложка не выбрана</span>}
        <div className="rd-wz-cover-btn">
          <ClubCoverButton clubId={club.id} hasCover={!!club.coverUrl} />
        </div>
      </div>

      {/* Превью — единственное место, где орг видит клуб глазами участника до презентации. */}
      <div className="rd-wz-lbl">Как это выглядит</div>
      <div className="rd-wz-preview">
        <div className="rd-wz-pv-cover">
          {club.coverUrl && <img src={club.coverUrl} alt="" />}
        </div>
        <div className="rd-wz-pv-ava">
          {club.avatarUrl
            ? <img src={club.avatarUrl} alt="" />
            : <span>{name.trim().charAt(0).toUpperCase()}</span>}
        </div>
        <div className="rd-wz-pv-name">{name}</div>
        <div className="rd-wz-pv-meta">
          {[cityLabel, ...interests.slice(0, 2)].filter(Boolean).join(' · ') || 'Клуб чата'}
        </div>
      </div>

      {/* Права уже выданы при добавлении бота — четвёртый шаг последний. */}
      <button type="button" className="rd-btn-primary rd-wz-next" onClick={goNext}>
        {hasRightsStep ? 'Дальше' : 'Готово'}
      </button>
      <button type="button" className="rd-ghost-btn rd-wz-skip" onClick={goNext}>
        Пропустить обложку
      </button>
    </>
  );
};

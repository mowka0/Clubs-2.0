import { FC } from 'react';
import { ClubAvatarButton } from '../ClubAvatarButton';
import type { ClubSetupStepProps } from './types';

/** Потолок названия клуба, совпадает с VARCHAR(60) в схеме. */
const NAME_MAX = 60;

type ClubSetupNameStepProps =
  Pick<ClubSetupStepProps, 'club' | 'draft' | 'onDraftChange' | 'saving' | 'error' | 'onSaveAndNext'>;

/**
 * Шаг 1: название и аватар клуба.
 *
 * Идёт первым намеренно — это то, что человек знает про свой чат наизусть, и отвечается
 * не думая. Название уже подставлено из группы, ему остаётся согласиться.
 *
 * Размера клуба здесь больше нет (этап 1, stage-1-scope.md): клуб из чата рождается с лимитом
 * 500 — потолком схемы, и спрашивать число, которое ни на что не влияет, незачем. Поправить
 * лимит можно в «Управлении → Настройки».
 */
export const ClubSetupNameStep: FC<ClubSetupNameStepProps> = ({
  club,
  draft,
  onDraftChange,
  saving,
  error,
  onSaveAndNext,
}) => {
  const name = draft.name ?? club.name;

  return (
    <>
      <h1 className="rd-wz-q">Как назовём клуб?</h1>
      <p className="rd-wz-qsub">Взяли название чата — поменяйте, если хочется.</p>

      <div className="rd-wz-lbl">Аватар</div>
      <div className="rd-wz-ava-row">
        <ClubAvatarButton clubId={club.id} clubName={name} avatarUrl={club.avatarUrl} editable />
        <span className="rd-wz-hint">Кружок клуба. Видно в списках и в шапке.</span>
      </div>

      <div className="rd-wz-lbl">Название</div>
      <input
        className="rd-input"
        value={name}
        maxLength={NAME_MAX}
        onChange={(e) => onDraftChange({ name: e.target.value })}
        aria-label="Название клуба"
      />

      <button
        type="button"
        className="rd-btn-primary rd-wz-next"
        disabled={!name.trim() || saving}
        onClick={() => onSaveAndNext({ name: name.trim() }, 2)}
      >
        Дальше
      </button>
      {error}
      <div className="rd-wz-note">Аватар можно добавить потом</div>
    </>
  );
};

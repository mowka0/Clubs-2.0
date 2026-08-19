import { FC } from 'react';
import { ClubAvatarButton } from '../ClubAvatarButton';
import type { ClubSetupStepProps } from './types';

/** Потолок названия клуба, совпадает с VARCHAR(60) в схеме. */
const NAME_MAX = 60;
/** Границы размера клуба — те же, что в CHECK-констрейнте схемы (V81) и в валидации DTO. */
const MEMBER_LIMIT_MIN = 1;
const MEMBER_LIMIT_MAX = 500;

type ClubSetupNameStepProps =
  Pick<ClubSetupStepProps, 'club' | 'draft' | 'onDraftChange' | 'saving' | 'error' | 'onSaveAndNext'>;

/**
 * Шаг 1: название, аватар и размер клуба.
 *
 * Идёт первым намеренно — это то, что человек знает про свой чат наизусть, и отвечается
 * не думая. Название и размер уже подставлены из группы, ему остаётся согласиться.
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
  const memberLimit = draft.memberLimit ?? String(club.memberLimit);
  // null = введено не число или значение вне границ схемы: кнопка «Дальше» тогда заблокирована,
  // и мы не отправляем заведомо отбиваемый бэком PATCH.
  const memberLimitNumber = /^\d+$/.test(memberLimit.trim())
    ? Number(memberLimit) >= MEMBER_LIMIT_MIN && Number(memberLimit) <= MEMBER_LIMIT_MAX
      ? Number(memberLimit)
      : null
    : null;

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

      {/* Размер подставлен из чата (getChatMemberCount при рождении клуба) — Telegram считает
          вместе с ботами, поэтому число приблизительное и правится руками. */}
      <div className="rd-wz-lbl">Сколько человек в клубе</div>
      <input
        className="rd-input"
        type="number"
        inputMode="numeric"
        min={MEMBER_LIMIT_MIN}
        max={MEMBER_LIMIT_MAX}
        value={memberLimit}
        onChange={(e) => onDraftChange({ memberLimit: e.target.value })}
        aria-label="Размер клуба"
      />
      <span className="rd-wz-hint">Взяли из чата. Потолок — {MEMBER_LIMIT_MAX}, потом можно поменять.</span>

      <button
        type="button"
        className="rd-btn-primary rd-wz-next"
        disabled={!name.trim() || memberLimitNumber === null || saving}
        onClick={() =>
          memberLimitNumber !== null &&
          onSaveAndNext({ name: name.trim(), memberLimit: memberLimitNumber }, 2)
        }
      >
        Дальше
      </button>
      {error}
      <div className="rd-wz-note">Аватар можно добавить потом</div>
    </>
  );
};

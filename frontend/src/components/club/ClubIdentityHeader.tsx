import { FC, ReactNode } from 'react';
import { ClubAvatarButton } from './ClubAvatarButton';
import { formatPrice } from '../../utils/formatters';
import type { ClubDetailDto } from '../../types/api';

/** Тип доступа в первом чипе параметров. */
const ACCESS_LABELS: Record<string, string> = {
  open: 'Открытый', closed: 'По заявке', private: 'Приватный',
};

interface ClubIdentityHeaderProps {
  club: ClubDetailDto;
  /** Правый верхний угол обложки: странице клуба — кнопки роли, приглашению — метка «Приглашение». */
  coverActions?: ReactNode;
  /** Аватар кликабелен (смена картинки) — только менеджеру на странице клуба. */
  avatarEditable: boolean;
}

/**
 * Шапка клуба: обложка → аватар на стыке → название → чипы параметров.
 * Общая для страницы клуба и посадочной приглашения (решение PO 2026-07-30): приглашение
 * показывает тот же самый клуб, и две разные вёрстки означали бы, что человек до и после
 * перехода видит как будто разные продукты. Держать их врозь и вручную синхронизировать —
 * гарантированное расхождение при следующей правке шапки.
 */
export const ClubIdentityHeader: FC<ClubIdentityHeaderProps> = ({ club, coverActions, avatarEditable }) => {
  const isPaid = club.subscriptionPrice > 0;

  return (
    <>
      {/* Обложка (hero) — чистая: название и параметры клуба живут на странице, не на картинке.
          Картинка берётся из coverUrl (V70): аватар клуба — отдельное поле, и смена одного
          больше не меняет другое. Нет обложки — рисуется градиент по категории. */}
      <div className="rd-hero rd-compact rd-club-cover">
        <div
          className="rd-hero-bg"
          data-cat={club.category}
          style={club.coverUrl ? { backgroundImage: `url(${club.coverUrl})` } : undefined}
        />
        {coverActions && <div className="rd-hero-acts">{coverActions}</div>}
      </div>

      {/* Аватар наезжает на стык обложки и страницы. */}
      <ClubAvatarButton
        clubId={club.id}
        clubName={club.name}
        avatarUrl={club.avatarUrl ?? null}
        editable={avatarEditable}
      />

      <div className="rd-club-name">{club.name}</div>

      {/* Параметры клуба одной строкой чипов: доступ · город · состав · взнос. */}
      <div className="rd-club-facts">
        <span className="rd-club-fact">
          <b>{ACCESS_LABELS[club.accessType] ?? club.accessType}</b>
        </span>
        <span className="rd-club-fact rd-shrink">
          <span aria-hidden="true">📍</span>
          <b>{club.city}</b>
        </span>
        <span className="rd-club-fact">
          <b>{club.memberCount} / {club.memberLimit}</b>
        </span>
        <span className={`rd-club-fact ${isPaid ? 'rd-pay' : 'rd-free'}`}>
          <b>{formatPrice(club.subscriptionPrice)}</b>
        </span>
      </div>
    </>
  );
};

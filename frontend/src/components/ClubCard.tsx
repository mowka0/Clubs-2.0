import { FC } from 'react';
import { useNavigate } from 'react-router-dom';
import { Cell, Text, Badge } from '@telegram-apps/telegram-ui';
import type { ClubListItemDto } from '../types/api';

const CATEGORY_LABELS: Record<string, string> = {
  sport: 'Спорт',
  creative: 'Творчество',
  food: 'Еда',
  board_games: 'Настолки',
  cinema: 'Кино',
  education: 'Образование',
  travel: 'Путешествия',
  other: 'Другое',
};

const ACCESS_LABELS: Record<string, string> = {
  open: 'Открытый',
  closed: 'По заявке',
};

function formatPrice(price: number): string {
  if (price === 0) return 'Бесплатно';
  return `${price} ⭐ / мес`;
}

function formatDate(iso: string): string {
  const date = new Date(iso);
  return date.toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });
}

interface ClubCardProps {
  club: ClubListItemDto;
}

export const ClubCard: FC<ClubCardProps> = ({ club }) => {
  const navigate = useNavigate();

  return (
    <Cell
      onClick={() => navigate(`/clubs/${club.id}`)}
      subtitle={
        <span>
          {club.city} · {club.memberCount}/{club.memberLimit} участников
          {club.nearestEvent && ` · ${formatDate(club.nearestEvent.eventDatetime)}`}
        </span>
      }
      after={<Text weight="2">{formatPrice(club.subscriptionPrice)}</Text>}
      before={
        club.avatarUrl
          ? <img src={club.avatarUrl} alt="" style={{ width: 48, height: 48, borderRadius: 12, objectFit: 'cover' }} />
          : <div style={{ width: 48, height: 48, borderRadius: 12, background: 'var(--tgui--secondary_bg_color)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 24 }}>🏠</div>
      }
    >
      <span style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        {club.name}
        <Badge type="number" style={{ fontSize: 11 }}>{CATEGORY_LABELS[club.category] ?? club.category}</Badge>
        {club.accessType !== 'open' && (
          <Badge type="number" style={{ fontSize: 11, background: 'var(--tgui--secondary_bg_color)', color: 'var(--tgui--text_color)' }}>
            {ACCESS_LABELS[club.accessType] ?? club.accessType}
          </Badge>
        )}
      </span>
    </Cell>
  );
};

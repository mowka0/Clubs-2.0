import { FC, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useHaptic } from '../hooks/useHaptic';
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

const KNOWN_CATEGORIES = new Set(Object.keys(CATEGORY_LABELS));

/** Russian ₽ — brand uses real currency, not Telegram Stars symbol */
function formatPrice(price: number): { amount: string; per: string; free: boolean } {
  if (price === 0) return { amount: 'Бесплатно', per: '', free: true };
  // Use non-breaking space between groups so "1 200 ₽" never wraps
  const formatted = new Intl.NumberFormat('ru-RU').format(price).replace(/\s/g, ' ');
  return { amount: `${formatted} ₽`, per: '/мес', free: false };
}

/** Cyrillic-friendly monogram from the club name (max 2 chars) */
function monogram(name: string): string {
  const parts = name.replace(/[«»"']/g, '').split(/\s+/).filter(Boolean);
  const first = parts[0]?.[0] ?? '';
  const second = parts[1]?.[0] ?? '';
  return (first + second).toUpperCase();
}

/** Detects whether nearestEvent is "today" (< 24h ahead) — featured-card trigger */
function isHappeningSoon(iso: string | undefined | null): boolean {
  if (!iso) return false;
  const eventTime = new Date(iso).getTime();
  if (Number.isNaN(eventTime)) return false;
  const diff = eventTime - Date.now();
  return diff > 0 && diff < 24 * 60 * 60 * 1000;
}

function formatEventTime(iso: string): string {
  const date = new Date(iso);
  const today = new Date();
  const sameDay = date.toDateString() === today.toDateString();
  const hh = date.getHours().toString().padStart(2, '0');
  const mm = date.getMinutes().toString().padStart(2, '0');
  return sameDay ? `сегодня · ${hh}:${mm}` : `${hh}:${mm}`;
}

const LOCK_ICON = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="4" y="10" width="16" height="11" rx="2" />
    <path d="M8 10V7a4 4 0 0 1 8 0v3" />
  </svg>
);

interface ClubCardProps {
  club: ClubListItemDto;
}

export const ClubCard: FC<ClubCardProps> = ({ club }) => {
  const navigate = useNavigate();
  const haptic = useHaptic();

  const featured = useMemo(() => isHappeningSoon(club.nearestEvent?.eventDatetime), [club.nearestEvent]);
  const cat = KNOWN_CATEGORIES.has(club.category) ? club.category : 'other';
  const price = formatPrice(club.subscriptionPrice);
  // memberLimit shouldn't be 0 from the API but guard against division-by-zero anyway
  const fillPct = club.memberLimit > 0
    ? Math.min(100, Math.round((club.memberCount / club.memberLimit) * 100))
    : 0;
  const almostFull = fillPct >= 80;

  return (
    <button
      type="button"
      className={featured ? 'club-card featured' : 'club-card'}
      onClick={() => {
        haptic.impact('light');
        navigate(`/clubs/${club.id}`);
      }}
    >
      <div className="avt" data-cat={cat}>
        {club.avatarUrl ? <img src={club.avatarUrl} alt="" /> : monogram(club.name)}
      </div>

      <div className="body">
        <div className="top">
          <span className="name">{club.name}</span>
          <span className={price.free ? 'price free' : 'price'}>
            {price.amount}
            {price.per && <span className="per">{' '}{price.per}</span>}
          </span>
        </div>

        <div className="meta">
          <span className="cat">{CATEGORY_LABELS[cat]}</span>
          {featured && club.nearestEvent && (
            <span className="signal">встреча {formatEventTime(club.nearestEvent.eventDatetime)}</span>
          )}
          {!featured && club.accessType !== 'open' && (
            <span className="closed">{LOCK_ICON} по заявке</span>
          )}
        </div>

        <div className="capacity">
          <div className="capacity-bar">
            <div
              className={featured ? 'fill live' : 'fill'}
              style={{ width: `${fillPct}%` }}
            />
          </div>
          <span className={almostFull ? 'capacity-num almost-full' : 'capacity-num'}>
            {club.memberCount} / {club.memberLimit}
          </span>
        </div>
      </div>
    </button>
  );
};

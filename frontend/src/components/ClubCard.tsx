import { FC, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useHaptic } from '../hooks/useHaptic';
import { ageBadge, counters } from './club/clubMilestones';
import type { ClubCardFactsDto, ClubListItemDto } from '../types/api';

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
function formatPrice(price: number): string {
  if (price === 0) return 'бесплатно';
  const formatted = new Intl.NumberFormat('ru-RU').format(price).replace(/\s/g, ' ');
  return `${formatted} ₽/мес`;
}

function pluralizeMembers(n: number): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return 'участник';
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return 'участника';
  return 'участников';
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
  const hh = date.getHours().toString().padStart(2, '0');
  const mm = date.getMinutes().toString().padStart(2, '0');
  return `${hh}:${mm}`;
}

/** «2.3» → "2,3"; «3.0» → "3" — Russian decimal, drop trailing .0 */
function formatPerMonth(value: number): string {
  return (Number.isInteger(value) ? String(value) : value.toFixed(1)).replace('.', ',');
}

interface ClubCardProps {
  club: ClubListItemDto;
  /** Quality facts (встреч/мес · вовлечённость · age/achievements). Absent until the batch loads. */
  facts?: ClubCardFactsDto;
}

export const ClubCard: FC<ClubCardProps> = ({ club, facts }) => {
  const navigate = useNavigate();
  const haptic = useHaptic();

  const featured = useMemo(() => isHappeningSoon(club.nearestEvent?.eventDatetime), [club.nearestEvent]);
  const cat = KNOWN_CATEGORIES.has(club.category) ? club.category : 'other';

  const meta = [
    club.city,
    `${club.memberCount} ${pluralizeMembers(club.memberCount)}`,
    formatPrice(club.subscriptionPrice),
  ]
    .filter(Boolean)
    .join(' · ');

  const achievements = facts ? [ageBadge(facts.ageMonths), ...counters(facts)] : [];

  return (
    <button
      type="button"
      className="rd-club-card"
      onClick={() => {
        haptic.impact('light');
        navigate(`/clubs/${club.id}`);
      }}
    >
      <div
        className="rd-cover"
        data-cat={cat}
        style={club.avatarUrl ? { backgroundImage: `url(${club.avatarUrl})`, backgroundSize: 'cover', backgroundPosition: 'center' } : undefined}
      >
        {featured && club.nearestEvent && (
          <span className="rd-date-badge" style={{ position: 'absolute', top: 10, right: 10 }}>
            встреча {formatEventTime(club.nearestEvent.eventDatetime)}
          </span>
        )}
      </div>
      <div className="rd-body">
        <div className="rd-ttl">{club.name}</div>
        <div className="rd-meta">{meta}</div>
        {facts && (facts.meetingsPerMonth > 0 || facts.engagementPercent > 0) && (
          <div className="rd-quality">
            {facts.meetingsPerMonth > 0 && (
              <span>📅 <b>{formatPerMonth(facts.meetingsPerMonth)}</b>/мес</span>
            )}
            {facts.engagementPercent > 0 && (
              <span>⚡ <b>{facts.engagementPercent}%</b> вовлечённость</span>
            )}
          </div>
        )}
        {achievements.length > 0 && (
          <div className="rd-badges">
            {achievements.map((a) => (
              <span key={a.label} className="rd-badge-chip">{a.icon} {a.label}</span>
            ))}
          </div>
        )}
      </div>
    </button>
  );
};

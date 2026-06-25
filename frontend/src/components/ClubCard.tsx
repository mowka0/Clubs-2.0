import { FC, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useHaptic } from '../hooks/useHaptic';
import { pluralRu } from '../utils/formatters';
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

interface ClubCardProps {
  club: ClubListItemDto;
  /** Quality facts (возраст + вовлечённость) for the metric trio. Absent until the batch loads. */
  facts?: ClubCardFactsDto;
}

export const ClubCard: FC<ClubCardProps> = ({ club, facts }) => {
  const navigate = useNavigate();
  const haptic = useHaptic();

  const featured = useMemo(() => isHappeningSoon(club.nearestEvent?.eventDatetime), [club.nearestEvent]);
  const cat = KNOWN_CATEGORIES.has(club.category) ? club.category : 'other';
  const memberLabel = pluralizeMembers(club.memberCount);

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
        <span className="rd-price-chip">{formatPrice(club.subscriptionPrice)}</span>
        {featured && club.nearestEvent && (
          <span className="rd-date-badge" style={{ position: 'absolute', top: 10, left: 12, zIndex: 2 }}>
            встреча {formatEventTime(club.nearestEvent.eventDatetime)}
          </span>
        )}
      </div>
      <div className="rd-body">
        {/* Soft-rank L3 badge — the only externally-visible rank signal (boolean; никогда не число).
            Над названием клуба. */}
        {facts?.topInCategory && <div><span className="rd-rankpill">★ Топ-5 в категории</span></div>}
        <div className="rd-ttl">{club.name}</div>
        <div className="rd-meta">
          {facts ? club.city : `${club.city} · ${club.memberCount} ${memberLabel}`}
        </div>
        {/* Trio: возраст · участники · вовлечённость — намеренно НЕ встреч/мес и НЕ ядро
            (это кольца на странице клуба), чтобы карточка не дублировала страницу. */}
        {facts && (
          <div className="rd-mrow">
            <div className="rd-mstat">
              <div className="rd-mv">{facts.ageDays}</div>
              <div className="rd-ml">{pluralRu(facts.ageDays, ['день', 'дня', 'дней'])}</div>
            </div>
            <div className="rd-mstat">
              <div className="rd-mv">{club.memberCount}</div>
              <div className="rd-ml">{memberLabel}</div>
            </div>
            <div className="rd-mstat">
              <div className="rd-mv ok">{facts.engagementPercent}%</div>
              <div className="rd-ml">вовлечены</div>
            </div>
          </div>
        )}
      </div>
    </button>
  );
};

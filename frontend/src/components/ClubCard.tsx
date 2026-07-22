import { FC, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useHaptic } from '../hooks/useHaptic';
import { formatTimeHM } from '../utils/formatters';
import { KNOWN_CATEGORIES } from '../utils/categoryLabels';
import type { ClubCardFactsDto, ClubListItemDto } from '../types/api';

/** Российский ₽ — бренд использует настоящую валюту, а не символ Telegram Stars */
function formatPrice(price: number): string {
  if (price === 0) return 'бесплатно';
  const formatted = new Intl.NumberFormat('ru-RU').format(price).replace(/\s/g, ' ');
  return `${formatted} ₽/мес`;
}

/** Определяет, наступит ли nearestEvent «скоро» (< 24ч) — триггер бейджа «встреча HH:MM».
    Намеренно окно 24ч, а не календарное «сегодня» (это критерий полки TodayShelf). */
function isHappeningSoon(iso: string | undefined | null): boolean {
  if (!iso) return false;
  const eventTime = new Date(iso).getTime();
  if (Number.isNaN(eventTime)) return false;
  const diff = eventTime - Date.now();
  return diff > 0 && diff < 24 * 60 * 60 * 1000;
}

/* Иконки полки метрик и пина города (мокап 11-chip-bare): stroke: currentColor,
   цвет задаёт CSS (.rd-m svg / .rd-meta svg). */
const ICON_CLOCK = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7v5l3 2" />
  </svg>
);

const ICON_PEOPLE = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
    <circle cx="9" cy="8" r="3.2" />
    <path d="M3 20c0-3 2.7-5 6-5s6 2 6 5" />
    <path d="M16 6a3 3 0 0 1 0 6" />
  </svg>
);

const ICON_BOLT = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M13 2 4 14h6l-1 8 9-12h-6z" />
  </svg>
);

const ICON_PIN = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
    <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z" />
    <circle cx="12" cy="10" r="3" />
  </svg>
);

interface ClubCardProps {
  club: ClubListItemDto;
  /** Факты о качестве (возраст + вовлечённость) для полки метрик. Отсутствуют, пока не загрузится пакет. */
  facts?: ClubCardFactsDto;
}

export const ClubCard: FC<ClubCardProps> = ({ club, facts }) => {
  const navigate = useNavigate();
  const haptic = useHaptic();

  const featured = useMemo(() => isHappeningSoon(club.nearestEvent?.eventDatetime), [club.nearestEvent]);
  const cat = KNOWN_CATEGORIES.has(club.category) ? club.category : 'other';

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
            встреча {formatTimeHM(club.nearestEvent.eventDatetime)}
          </span>
        )}
        {/* Полка метрик: возраст · участники · вовлечённость — уголок из материала карточки
            в нижнем-левом углу обложки. Намеренно НЕ встреч/мес и НЕ ядро (это кольца на
            странице клуба), чтобы карточка не дублировала страницу. */}
        {facts && (
          <div className="rd-shelf">
            <span className="rd-m">{ICON_CLOCK}{facts.ageDays} дн</span>
            <span className="rd-m">{ICON_PEOPLE}{club.memberCount}</span>
            <span className="rd-m ok">{ICON_BOLT}{facts.engagementPercent}%</span>
          </div>
        )}
      </div>
      <div className="rd-body">
        {/* Soft-rank L3 бейдж — единственный внешне видимый сигнал ранга (boolean; никогда не число).
            Над названием клуба. */}
        {facts?.topInCategory && <div><span className="rd-rankpill">★ Топ-5 в категории</span></div>}
        <div className="rd-ttl">{club.name}</div>
        <div className="rd-meta">{ICON_PIN}{club.city}</div>
      </div>
    </button>
  );
};

import { FC, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useHaptic } from '../hooks/useHaptic';
import { formatTimeHM, isToday, isTomorrow } from '../utils/formatters';
import { KNOWN_CATEGORIES } from '../utils/categoryLabels';
import type { ClubCardFactsDto, ClubListItemDto } from '../types/api';

/** Российский ₽ — бренд использует настоящую валюту, а не символ Telegram Stars */
function formatPrice(price: number): string {
  if (price === 0) return 'бесплатно';
  const formatted = new Intl.NumberFormat('ru-RU').format(price).replace(/\s/g, ' ');
  return `${formatted} ₽/мес`;
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

  // Слово дня для колонки расписания: «сегодня»/«завтра» по локальному календарю,
  // послезавтра и дальше — колонки нет. Раньше был верхний бейдж на обложке с окном
  // «<24ч»: он сталкивался с ценником на узких экранах. Теперь — колонка расписания
  // в теле справа (вариант 17 мокапа 13-meeting-corner).
  const meetingDay = useMemo(() => {
    const iso = club.nearestEvent?.eventDatetime;
    if (!iso) return null;
    if (isToday(iso)) return 'сегодня';
    if (isTomorrow(iso)) return 'завтра';
    return null;
  }, [club.nearestEvent]);
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
        {/* Левая колонка (название + город) ужимается многоточием — запас под будущий
            район гарантирован конструкцией: колонка времени справа не двигается. */}
        <div className="rd-brow">
          <div className="rd-bl">
            <div className="rd-ttl">{club.name}</div>
            <div className="rd-meta">{ICON_PIN}<span className="rd-meta-city">{club.city}</span></div>
          </div>
          {meetingDay && club.nearestEvent && (
            <div className="rd-meet">
              <span className="rd-meet-bar" aria-hidden="true" />
              <span className="rd-meet-tx">
                <span className="rd-meet-d">{meetingDay}</span>
                <span className="rd-meet-t">{formatTimeHM(club.nearestEvent.eventDatetime)}</span>
              </span>
            </div>
          )}
        </div>
      </div>
    </button>
  );
};

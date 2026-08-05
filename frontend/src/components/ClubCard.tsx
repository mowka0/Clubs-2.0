import { FC, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useHaptic } from '../hooks/useHaptic';
import { formatTimeHM, isToday, isTomorrow } from '../utils/formatters';
import { KNOWN_CATEGORIES } from '../utils/categoryLabels';
import type { ClubCardFactsDto, ClubListItemDto } from '../types/api';

// Сколько тем помещается в строку карточки до сворачивания остатка в «+N».
const MAX_CARD_TOPICS = 3;

/** Российский ₽ — бренд использует настоящую валюту, а не символ Telegram Stars */
function formatPrice(price: number): string {
  if (price === 0) return 'бесплатно';
  const formatted = new Intl.NumberFormat('ru-RU').format(price).replace(/\s/g, ' ');
  return `${formatted} ₽/мес`;
}

/* Иконка пина города: stroke: currentColor, цвет задаёт CSS (.rd-meta svg).
   Часы/люди/молния удалены вместе с полкой метрик (PO 2026-08-05). */
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
  // Первые темы в порядке разметки (первая = главная); остаток сворачивается в «+N»,
  // чтобы строка не переносилась и не растила карточку.
  const topics = useMemo(() => {
    const shown = club.interests.slice(0, MAX_CARD_TOPICS);
    const rest = club.interests.length - shown.length;
    return rest > 0 ? [...shown, `+${rest}`] : shown;
  }, [club.interests]);
  // Обложка карточки: с V70 у клуба своё поле обложки, аватар остаётся кружком. Фолбэк на аватар
  // нужен клубам, созданным до разделения полей — иначе их карточки разом потеряли бы картинку.
  const cardCover = club.coverUrl ?? club.avatarUrl;

  return (
    <button
      type="button"
      className="rd-club-card"
      onClick={() => {
        haptic.impact('light');
        navigate(`/clubs/${club.id}`);
      }}
    >
      {/* Обложка чистая: единственное наложение — чип цены. Полка метрик, съедавшая нижние
          ~30px фотографии, снята (PO 2026-08-05, вариант G мокапа 16-topics-and-cover),
          и эти пиксели отданы самой картинке — она стала выше, чем была с полкой. */}
      <div
        className="rd-cover"
        data-cat={cat}
        style={cardCover ? { backgroundImage: `url(${cardCover})`, backgroundSize: 'cover', backgroundPosition: 'center' } : undefined}
      >
        <span className="rd-price-chip">{formatPrice(club.subscriptionPrice)}</span>
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
            {/* Одна строка вместо полки: город, размер и активность клуба. Возраст («145 дн»)
                убран совсем — при выборе клуба это самый слабый сигнал, а место дороже. */}
            <div className="rd-meta">
              {ICON_PIN}
              <span className="rd-meta-city">{club.city}</span>
              <span className="rd-meta-sep" aria-hidden="true">·</span>
              <span className="rd-meta-num">{club.memberCount} чел</span>
              {facts && (
                <>
                  <span className="rd-meta-sep" aria-hidden="true">·</span>
                  <span className="rd-meta-live">{facts.engagementPercent}% актив</span>
                </>
              )}
            </div>
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
        {/* Темы плашками: со снятием полки метрик место освободилось, и «бег · марафон» больше
            не приходится жать в серый хвост под городом. Плашка читается как самостоятельная
            сущность — это ответ на «о чём клуб», ради которого человек и открывает каталог. */}
        {topics.length > 0 && (
          <div className="rd-topics">
            {topics.map((topic) => (
              <span key={topic} className="rd-topic">{topic}</span>
            ))}
          </div>
        )}
      </div>
    </button>
  );
};

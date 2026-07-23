import { FC } from 'react';
import type { MyEventListItemDto } from '../../types/api';

interface EventCardProps {
  event: MyEventListItemDto;
  onClick: () => void;
}

interface Badge {
  text: string;
  accent: boolean;
}

const WEEKDAY_FORMATTER = new Intl.DateTimeFormat('ru-RU', { weekday: 'short' });

const TIME_FORMATTER = new Intl.DateTimeFormat('ru-RU', {
  hour: '2-digit',
  minute: '2-digit',
});

function formatDateBadge(iso: string): string {
  const d = new Date(iso);
  return `${WEEKDAY_FORMATTER.format(d).toUpperCase()} · ${TIME_FORMATTER.format(d)}`;
}

function getInitials(name: string): string {
  return name
    .replace(/[«»"']/g, '')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase())
    .join('');
}

function pickBadge(event: MyEventListItemDto): Badge | null {
  if (event.actionRequired) {
    if (event.status === 'stage_2') return { text: 'Подтверди участие', accent: true };
    return { text: 'Проголосуй', accent: true };
  }
  switch (event.myParticipationStatus) {
    case 'confirmed':          return { text: 'Подтверждён',   accent: false };
    case 'waitlisted':         return { text: 'Лист ожидания', accent: false };
    case 'declined':           return { text: 'Отказался',     accent: false };
    // Бронь сгорела — проголосовал going/maybe, но так и не подтвердил. Должно перебивать
    // устаревший голос Этапа 1 ниже, иначе no-confirm показался бы как "Иду"/"Возможно".
    case 'expired_no_confirm': return { text: 'Не подтвердил', accent: false };
  }
  switch (event.myVote) {
    case 'going':     return { text: 'Иду',      accent: false };
    case 'maybe':     return { text: 'Возможно', accent: false };
    case 'not_going': return { text: 'Не иду',   accent: false };
  }
  return null;
}

/** Текущее число участников: confirmed после закрытия голосования (stage_2/completed, F5-21), иначе — "going". */
function currentCount(event: MyEventListItemDto): number {
  return event.status === 'stage_2' || event.status === 'completed'
    ? event.confirmedCount
    : event.goingCount;
}

export const EventCard: FC<EventCardProps> = ({ event, onClick }) => {
  const badge = pickBadge(event);
  const clubInitials = getInitials(event.clubName);
  // Счётчик идущих переехал из meta-строки в градиентную цифру справа (PO 2026-07-21) —
  // тот же вид, что на карточке активностей страницы клуба (rd-ft-stat). Фаза как там (F5-21):
  // после закрытия голосования — подтверждённые, у открытой встречи знаменателя нет.
  const finalComposition = event.status === 'stage_2' || event.status === 'completed';
  const countCaption = finalComposition ? 'подтв.' : 'идёт';
  const meta = event.locationText ?? '';
  // Обложка: фото события (PO 2026-07-11), фолбэк — аватар клуба; с картинкой — тёмный
  // скрим сверху вниз (rd-act-photo), как у клубных карточек.
  const coverImage = event.photoUrl ?? event.clubAvatarUrl;

  return (
    <button type="button" className="rd-activity-card" onClick={onClick}>
      <div
        className={coverImage ? 'rd-act-cover rd-act-photo' : 'rd-act-cover'}
        style={coverImage ? { backgroundImage: `url(${coverImage})`, backgroundSize: 'cover', backgroundPosition: 'center' } : undefined}
      >
        {/* Тип встречи вместо родового «СОБЫТИЕ» (PO 2026-07-21, ярлыки PO 2026-07-23):
            формат виден прямо с карточки — срочная/обычная/открытая, эмодзи как в пикере. */}
        <span className="rd-type-badge">
          {event.isUrgent ? '⚡ СРОЧНАЯ' : event.participantLimit == null ? '🌊 ОТКРЫТАЯ' : '🎟 ОБЫЧНАЯ'}
        </span>
        <span className="rd-date-badge">{formatDateBadge(event.eventDatetime)}</span>
      </div>
      <div className="rd-act-body rd-act-body-split">
        <div className="rd-act-main">
        <div className="rd-act-club-row">
          <span className="rd-club-avt">
            {event.clubAvatarUrl ? <img src={event.clubAvatarUrl} alt="" /> : clubInitials}
          </span>
          <span>{event.clubName}</span>
        </div>
        <div className="rd-act-ttl">{event.title}</div>
        {meta && <div className="rd-act-meta">{meta}</div>}
        {/* Показываем только accent-бейджи (call-to-action). Остальные
            нейтральные статусы (Подтверждён/Иду/…) карточка намеренно НЕ показывает ради
            снижения визуальной плотности — их поведение не трогаем. */}
        {badge && badge.accent && (
          <div className="rd-badges-row">
            <span className="rd-badge rd-warn">{badge.text}</span>
          </div>
        )}
        </div>
        {/* Градиентная цифра справа — как rd-ft-stat на карточке страницы клуба. */}
        <div className="rd-ft-stat">
          <div className="rd-ft-stat-num">
            {event.participantLimit == null
              ? currentCount(event)
              : `${currentCount(event)}/${event.participantLimit}`}
          </div>
          <div className="rd-ft-stat-cap">{countCaption}</div>
        </div>
      </div>
    </button>
  );
};

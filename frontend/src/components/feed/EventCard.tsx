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
  // История — ПЕРВАЯ ветка, и бейджа у неё НЕТ (решение PO 2026-07-20): в секцию попадают
  // только посещённые события, любой бейдж дублировал бы заголовок секции. Ранний return
  // обязателен: у каждой строки истории myParticipationStatus === 'confirmed' по конструкции
  // (setAttendance требует final_status = 'confirmed'), без него бейдж выдал бы «Подтверждён» —
  // обещание на будущее, на прошедшей встрече читается неверно.
  if (event.isHistory) return null;
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

/** Текущее число участников: confirmed после закрытия голосования (stage_2), иначе — "going". */
function currentCount(event: MyEventListItemDto): number {
  return event.status === 'stage_2' ? event.confirmedCount : event.goingCount;
}

export const EventCard: FC<EventCardProps> = ({ event, onClick }) => {
  const badge = pickBadge(event);
  const clubInitials = getInitials(event.clubName);
  // На прошедшем событии честного числа пришедших в DTO нет (goingCount — голоса Этапа 1,
  // confirmedCount — ростер, ни то ни другое не явка), поэтому в истории мета = только место,
  // без «N идут» в настоящем времени. Точный attendedCount — в бэклоге.
  const meta = event.isHistory
    ? (event.locationText ?? '')
    : [event.locationText, `${currentCount(event)} идут`].filter(Boolean).join(' · ');
  // Обложка: фото события (PO 2026-07-11), фолбэк — аватар клуба; с картинкой — тёмный
  // скрим сверху вниз (rd-act-photo), как у клубных карточек.
  const coverImage = event.photoUrl ?? event.clubAvatarUrl;

  return (
    <button type="button" className="rd-activity-card" onClick={onClick}>
      <div
        className={coverImage ? 'rd-act-cover rd-act-photo' : 'rd-act-cover'}
        style={coverImage ? { backgroundImage: `url(${coverImage})`, backgroundSize: 'cover', backgroundPosition: 'center' } : undefined}
      >
        <span className="rd-type-badge">СОБЫТИЕ</span>
        <span className="rd-date-badge">{formatDateBadge(event.eventDatetime)}</span>
      </div>
      <div className="rd-act-body">
        <div className="rd-act-club-row">
          <span className="rd-club-avt">
            {event.clubAvatarUrl ? <img src={event.clubAvatarUrl} alt="" /> : clubInitials}
          </span>
          <span>{event.clubName}</span>
        </div>
        <div className="rd-act-ttl">{event.title}</div>
        <div className="rd-act-meta">{meta}</div>
        {/* Показываем только accent-бейджи (call-to-action). Остальные
            нейтральные статусы (Подтверждён/Иду/…) карточка намеренно НЕ показывает ради
            снижения визуальной плотности — их поведение не трогаем. */}
        {badge && badge.accent && (
          <div className="rd-badges-row">
            <span className="rd-badge rd-warn">{badge.text}</span>
          </div>
        )}
      </div>
    </button>
  );
};

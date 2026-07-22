import { FC, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useHaptic } from '../hooks/useHaptic';
import { calendarDayDiff, formatNearDay, formatTimeHM, pluralRu } from '../utils/formatters';
import { categoryLabel } from '../utils/categoryLabels';
import type { ClubListItemDto, NearestEventDto } from '../types/api';

/** Горизонт полки: сегодня + 6 дней вперёд (скользящая неделя, не календарная —
    иначе в воскресенье полка пустела бы). */
const WEEK_AHEAD_DAYS = 7;

interface WeekShelfProps {
  /** Уже загруженные (и отфильтрованные запросом) клубы выдачи Discovery. */
  clubs: ClubListItemDto[];
}

type ClubWithEvent = ClubListItemDto & { nearestEvent: NearestEventDto };

/**
 * Полка «Встречаются на неделе»: горизонтальный ряд клубов из выдачи Discovery,
 * у которых nearestEvent выпадает на ближайшие 7 локальных календарных дней
 * (сегодня..+6). Бейдж — время + «сегодня»/«завтра»/день недели. Скрыта целиком,
 * когда таких клубов нет. Видит только загруженные страницы списка (первая = 20
 * клубов) — для MVP достаточно; настоящий разрез по городу — потом, эндпоинтом.
 */
export const WeekShelf: FC<WeekShelfProps> = ({ clubs }) => {
  const navigate = useNavigate();
  const haptic = useHaptic();

  const weekClubs = useMemo(
    () =>
      clubs
        .filter((club): club is ClubWithEvent => {
          if (club.nearestEvent == null) return false;
          const diff = calendarDayDiff(club.nearestEvent.eventDatetime);
          return diff !== null && diff >= 0 && diff < WEEK_AHEAD_DAYS;
        })
        .sort(
          (a, b) =>
            new Date(a.nearestEvent.eventDatetime).getTime() -
            new Date(b.nearestEvent.eventDatetime).getTime(),
        ),
    [clubs],
  );

  if (weekClubs.length === 0) return null;

  return (
    <section className="rd-week-shelf" aria-label="Встречаются на неделе">
      <div className="rd-week-lbl">Встречаются на неделе</div>
      <div className="rd-week-rail">
        {weekClubs.map((club) => (
          <button
            key={club.id}
            type="button"
            className="rd-week-mini"
            onClick={() => {
              haptic.impact('light');
              navigate(`/clubs/${club.id}`);
            }}
          >
            <span className="rd-week-time">
              {formatTimeHM(club.nearestEvent.eventDatetime)}
              <small>{formatNearDay(club.nearestEvent.eventDatetime)}</small>
            </span>
            <span className="rd-week-inf">
              <span className="rd-week-n">{club.name}</span>
              <span className="rd-week-s">
                {categoryLabel(club.category)} · {club.memberCount}{' '}
                {pluralRu(club.memberCount, ['участник', 'участника', 'участников'])}
              </span>
            </span>
          </button>
        ))}
      </div>
    </section>
  );
};

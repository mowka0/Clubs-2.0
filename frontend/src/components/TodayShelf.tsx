import { FC, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useHaptic } from '../hooks/useHaptic';
import { formatTimeHM, isToday, pluralRu } from '../utils/formatters';
import { categoryLabel } from '../utils/categoryLabels';
import type { ClubListItemDto, NearestEventDto } from '../types/api';

interface TodayShelfProps {
  /** Уже загруженные (и отфильтрованные запросом) клубы выдачи Discovery. */
  clubs: ClubListItemDto[];
}

type ClubWithEvent = ClubListItemDto & { nearestEvent: NearestEventDto };

/**
 * Полка «Встречаются сегодня»: горизонтальный ряд клубов из выдачи Discovery,
 * у которых nearestEvent выпадает на сегодняшнюю локальную календарную дату.
 * Скрыта целиком, когда таких клубов нет. Видит только загруженные страницы
 * списка (первая = 20 клубов) — для MVP достаточно; настоящий разрез «события
 * сегодня по городу» — потом, отдельным эндпоинтом.
 */
export const TodayShelf: FC<TodayShelfProps> = ({ clubs }) => {
  const navigate = useNavigate();
  const haptic = useHaptic();

  const todayClubs = useMemo(
    () =>
      clubs
        .filter(
          (club): club is ClubWithEvent =>
            club.nearestEvent != null && isToday(club.nearestEvent.eventDatetime),
        )
        .sort(
          (a, b) =>
            new Date(a.nearestEvent.eventDatetime).getTime() -
            new Date(b.nearestEvent.eventDatetime).getTime(),
        ),
    [clubs],
  );

  if (todayClubs.length === 0) return null;

  return (
    <section className="rd-today-shelf" aria-label="Встречаются сегодня">
      <div className="rd-today-lbl">Встречаются сегодня</div>
      <div className="rd-today-rail">
        {todayClubs.map((club) => (
          <button
            key={club.id}
            type="button"
            className="rd-today-mini"
            onClick={() => {
              haptic.impact('light');
              navigate(`/clubs/${club.id}`);
            }}
          >
            <span className="rd-today-time">
              {formatTimeHM(club.nearestEvent.eventDatetime)}
              <small>сегодня</small>
            </span>
            <span className="rd-today-inf">
              <span className="rd-today-n">{club.name}</span>
              <span className="rd-today-s">
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

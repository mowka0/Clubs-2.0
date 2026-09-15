import { FC, useState } from 'react';
import { useHaptic } from '../../hooks/useHaptic';
import { ActivityCard } from './ActivityCard';
import { ActivityCompactRow } from './ActivityCompactRow';
import type { ActivityItemDto, ClubActivityFeed } from '../../api/activities';

/** Сколько прошедших активностей видно без раскрытия шторки (PO 2026-09-14). */
const PAST_PREVIEW_COUNT = 3;

interface ActivityFeedListProps {
  feed: ClubActivityFeed;
  onActivityClick: (activity: ActivityItemDto) => void;
}

export const ActivityFeedList: FC<ActivityFeedListProps> = ({
  feed,
  onActivityClick,
}) => {
  const haptic = useHaptic();
  const [pastExpanded, setPastExpanded] = useState(false);

  const togglePast = () => {
    haptic.impact('light');
    setPastExpanded((prev) => !prev);
  };

  // Прошедшие смотрят часто, поэтому три последних видны сразу, а шторка прячет только хвост
  // (PO 2026-09-14). Заголовок — обычный ярлык секции, как «Предстоящие»: кнопка рисовалась
  // шрифтом браузера и выглядела чужой.
  const visiblePast = pastExpanded ? feed.past : feed.past.slice(0, PAST_PREVIEW_COUNT);

  return (
    <>
      {feed.upcoming.length > 0 && (
        <section>
          <div className="rd-section-sub-h">Предстоящие</div>
          {feed.upcoming.map((item) => (
            <ActivityCard
              key={item.id}
              activity={item}
              onClick={() => onActivityClick(item)}
            />
          ))}
        </section>
      )}

      {feed.past.length > 0 && (
        <section>
          <div className="rd-section-sub-h">
            Прошедшие <span className="rd-count">· {feed.past.length}</span>
          </div>
          <div className="rd-glass rd-rep-panel">
            {visiblePast.map((item) => (
              <ActivityCompactRow
                key={item.id}
                activity={item}
                onClick={() => onActivityClick(item)}
              />
            ))}
            {feed.past.length > PAST_PREVIEW_COUNT && (
              <button
                type="button"
                className="rd-resp-more"
                aria-expanded={pastExpanded}
                onClick={togglePast}
              >
                {pastExpanded ? 'Свернуть' : `Показать все · ${feed.past.length}`}
              </button>
            )}
          </div>
        </section>
      )}
    </>
  );
};

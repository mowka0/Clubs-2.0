import { FC } from 'react';
import { useClubQualityQuery } from '../../queries/clubQuality';
import { ageBadge, counters } from './clubMilestones';

interface ClubAchievementsProps {
  clubId: string;
}

/**
 * «Достижения» — публичные итоги клуба (соц-пруф): возраст-бейдж (всегда) + живые счётчики
 * «N встреч» / «N сборов» (без порогов и замков). Fail-soft: при загрузке/ошибке блок не рендерится.
 * Дизайн-контракт: docs/modules/club-quality.md §6, docs/backlog/club-quality-gamification.md §11.2.
 */
export const ClubAchievements: FC<ClubAchievementsProps> = ({ clubId }) => {
  const { data } = useClubQualityQuery(clubId);
  if (!data) return null;

  const age = ageBadge(data.ageMonths);
  const items = counters(data);

  return (
    <>
      <div className="rd-section-sub-h">Достижения</div>
      <div className="miles">
        <span className="mile gold">
          <span>{age.icon}</span>
          {age.label}
        </span>
        {items.map((a) => (
          <span key={a.label} className="mile">
            <span>{a.icon}</span>
            {a.label}
          </span>
        ))}
      </div>
    </>
  );
};

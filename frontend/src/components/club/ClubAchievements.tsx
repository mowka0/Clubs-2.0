import { FC } from 'react';
import { useClubQualityQuery } from '../../queries/clubQuality';
import { ageBadge, milestones } from './clubMilestones';

interface ClubAchievementsProps {
  clubId: string;
}

/**
 * «Достижения» — публичные майлстоны клуба (соц-пруф), всегда с возраст-бейджем + взятыми/растущими
 * трофеями. Всё на фактах, без очков. Fail-soft: при загрузке/ошибке блок не рендерится.
 * Дизайн-контракт: docs/modules/club-quality.md §6, docs/backlog/club-quality-gamification.md §11.2.
 */
export const ClubAchievements: FC<ClubAchievementsProps> = ({ clubId }) => {
  const { data } = useClubQualityQuery(clubId);
  if (!data) return null;

  const age = ageBadge(data.ageMonths);
  const miles = milestones(data);

  return (
    <>
      <div className="rd-section-sub-h">Достижения</div>
      <div className="miles">
        <span className="mile gold">
          <span>{age.icon}</span>
          {age.label}
        </span>
        {miles.map((m) =>
          m.earned ? (
            <span key={m.label} className="mile">
              <span>{m.icon}</span>
              {m.label}
            </span>
          ) : (
            <span key={m.label} className="mile lock">
              <span>🔒</span>
              {m.label} · {m.current}/{m.target}
            </span>
          ),
        )}
      </div>
    </>
  );
};

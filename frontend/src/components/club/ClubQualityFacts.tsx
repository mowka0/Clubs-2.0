import { FC } from 'react';
import { useClubQualityQuery } from '../../queries/clubQuality';
import { pluralRu } from '../../utils/formatters';

/** Drops a trailing `.0` so 8.0 → "8" while 1.3 stays "1.3". */
function formatMeetings(n: number): string {
  return Number.isInteger(n) ? String(n) : n.toFixed(1);
}

function ageParts(ageMonths: number): { value: string; foot: string } {
  if (ageMonths >= 12) {
    const years = Math.floor(ageMonths / 12);
    return { value: String(years), foot: pluralRu(years, ['год', 'года', 'лет']) };
  }
  if (ageMonths >= 1) {
    return { value: String(ageMonths), foot: pluralRu(ageMonths, ['месяц', 'месяца', 'месяцев']) };
  }
  return { value: '<1', foot: 'месяца' };
}

interface ClubQualityFactsProps {
  clubId: string;
}

/**
 * «Качество клуба» — публичный соц-пруф (L1-факты) на странице клуба. Видят все зрители.
 * Fail-soft: вторичный блок, при загрузке/ошибке просто не рендерится и не ломает страницу.
 * Дизайн-контракт: docs/modules/club-quality.md, docs/backlog/club-quality-gamification.md §11.4.
 */
export const ClubQualityFacts: FC<ClubQualityFactsProps> = ({ clubId }) => {
  const { data } = useClubQualityQuery(clubId);
  if (!data) return null;

  const { meetingsPerMonth, avgAttendance, coreSize, ageMonths } = data;
  const hasActivity = meetingsPerMonth > 0 || avgAttendance > 0 || coreSize > 0;
  const age = ageParts(ageMonths);
  const ageLine = age.value === '<1' ? 'Клубу меньше месяца.' : `Клубу ${age.value} ${age.foot}.`;

  return (
    <>
      <div className="rd-section-sub-h">Качество клуба</div>
      {hasActivity ? (
        <div className="rd-stats">
          <div className="rd-stat rd-glass">
            <div className="rd-stat-label">Встреч в месяц</div>
            <div className="rd-stat-value">{formatMeetings(meetingsPerMonth)}</div>
            <div className="rd-stat-foot">за 90 дней</div>
          </div>
          <div className="rd-stat rd-glass">
            <div className="rd-stat-label">Обычно приходит</div>
            <div className="rd-stat-value rd-plain">{avgAttendance > 0 ? avgAttendance : '—'}</div>
            <div className="rd-stat-foot">на встречу</div>
          </div>
          <div className="rd-stat rd-glass">
            <div className="rd-stat-label">Ядро клуба</div>
            <div className="rd-stat-value rd-plain">{coreSize > 0 ? coreSize : '—'}</div>
            <div className="rd-stat-foot">ходят регулярно</div>
          </div>
          <div className="rd-stat rd-glass">
            <div className="rd-stat-label">Возраст</div>
            <div className="rd-stat-value rd-plain">{age.value}</div>
            <div className="rd-stat-foot">{age.foot}</div>
          </div>
        </div>
      ) : (
        <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
          <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>
            Пока нет данных о встречах. {ageLine}
          </div>
        </div>
      )}
    </>
  );
};

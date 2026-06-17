import { FC } from 'react';
import { useClubQualityQuery } from '../../queries/clubQuality';
import { pluralRu } from '../../utils/formatters';
import { QualityRing } from './QualityRing';
import { activityLevel, attendanceLevel, cohesionLevel } from './qualityLevels';

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
  /** Active members — denominator of the «Приходит» ring («N из M»). */
  memberCount: number;
}

/**
 * «Качество клуба» — публичный соц-пруф (L2-кольца поверх L1-фактов) на странице клуба. Видят все.
 * Три кольца: Сплочённость (ядро, зелёное) · Активность (встреч/мес) · Приходит (среднее из M).
 * Возраст здесь НЕ показываем — он уедет в «Достижения» (следующий срез); в empty-state остаётся строкой.
 * Fail-soft: вторичный блок, при загрузке/ошибке просто не рендерится.
 * Дизайн-контракт: docs/modules/club-quality.md, docs/backlog/club-quality-gamification.md §11.2.
 */
export const ClubQualityFacts: FC<ClubQualityFactsProps> = ({ clubId, memberCount }) => {
  const { data } = useClubQualityQuery(clubId);
  if (!data) return null;

  const { meetingsPerMonth, avgAttendance, coreSize, ageMonths } = data;
  const hasActivity = meetingsPerMonth > 0 || avgAttendance > 0 || coreSize > 0;

  if (!hasActivity) {
    const age = ageParts(ageMonths);
    const ageLine = age.value === '<1' ? 'Клубу меньше месяца.' : `Клубу ${age.value} ${age.foot}.`;
    return (
      <>
        <div className="rd-section-sub-h">Качество клуба</div>
        <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
          <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>
            Пока нет данных о встречах. {ageLine}
          </div>
        </div>
      </>
    );
  }

  return (
    <>
      <div className="rd-section-sub-h">Качество клуба</div>
      <div className="qrings">
        <div className="qring">
          <QualityRing
            level={cohesionLevel(coreSize)}
            color="var(--live)"
            ariaLabel={`Основа клуба: ${coreSize} ${pluralRu(coreSize, ['человек', 'человека', 'человек'])} ходят постоянно`}
          >
            <span className="qr-v">{coreSize}</span>
            <span className="qr-u">чел.</span>
          </QualityRing>
          <span className="qr-l">основа клуба</span>
        </div>
        <div className="qring">
          <QualityRing
            level={activityLevel(meetingsPerMonth)}
            color="var(--accent)"
            ariaLabel={`Частота встреч: ${formatMeetings(meetingsPerMonth)} в месяц`}
          >
            <span className="qr-v">{formatMeetings(meetingsPerMonth)}</span>
            <span className="qr-u">/мес</span>
          </QualityRing>
          <span className="qr-l">частота встреч</span>
        </div>
        <div className="qring">
          <QualityRing
            level={attendanceLevel(avgAttendance, memberCount)}
            color="var(--accent)"
            ariaLabel={`Обычно приходит ${avgAttendance} из ${memberCount}`}
          >
            <span className="qr-v">{avgAttendance}</span>
            <span className="qr-u">из {memberCount}</span>
          </QualityRing>
          <span className="qr-l">обычно приходит</span>
        </div>
      </div>
    </>
  );
};

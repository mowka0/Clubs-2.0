import { FC, Fragment } from 'react';
import { useClubQualityQuery } from '../../queries/clubQuality';
import { QualityRing } from './QualityRing';
import { activityLevel, attendanceLevel, cohesionLevel } from './qualityLevels';
import { ageBadge, counters } from './clubMilestones';

/** Убирает хвостовой `.0`: 8.0 → "8", а 1.3 остаётся "1.3". */
function formatMeetings(n: number): string {
  return Number.isInteger(n) ? String(n) : n.toFixed(1);
}

/** Двухсловная подпись кольца → принудительно две строки (перенос по первому пробелу) для ровной высоты. */
function twoLineLabel(text: string): string {
  return text.replace(' ', '\n');
}

interface ClubQualityFactsProps {
  clubId: string;
  /** Активные участники — знаменатель кольца «Приходит» («N из M»). */
  memberCount: number;
}

/**
 * Единый публичный блок качества клуба (соц-пруф), виден всем зрителям. Без заголовков-секций:
 * три кольца (основа клуба · частота встреч · обычно приходит) + лёгкая строка-капшн (возраст-бейдж
 * + живые счётчики «N встреч»/«N сборов», через точку). Молодой клуб без событий → только строка.
 * Fail-soft: при загрузке/ошибке блок не рендерится.
 * Дизайн-контракт: docs/modules/club-quality.md §6, docs/backlog/club-quality-gamification.md §11.2.
 */
export const ClubQualityFacts: FC<ClubQualityFactsProps> = ({ clubId, memberCount }) => {
  const { data } = useClubQualityQuery(clubId);
  if (!data) return null;

  const { meetingsPerMonth, avgAttendance, coreSize } = data;
  const hasActivity = meetingsPerMonth > 0 || avgAttendance > 0 || coreSize > 0;
  const age = ageBadge(data.ageMonths);
  const tail = counters(data).map((a) => ({ key: a.label, icon: a.icon, text: a.label, muted: false }));
  if (!hasActivity) tail.push({ key: 'no-meetings', icon: '', text: 'пока нет встреч', muted: true });

  return (
    <div className="rd-glass" style={{ padding: '18px 16px', marginBottom: 14 }}>
      {hasActivity && (
        <>
          <div className="qrings">
            <div className="qring">
              <QualityRing level={cohesionLevel(coreSize)} color="var(--live)" ariaLabel={`Основа клуба: ${coreSize}`}>
                <span className="qr-v">{coreSize}</span>
                <span className="qr-u">чел.</span>
              </QualityRing>
              <span className="qr-l">{twoLineLabel('основа клуба')}</span>
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
              <span className="qr-l">{twoLineLabel('частота встреч')}</span>
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
              <span className="qr-l">{twoLineLabel('обычно приходит')}</span>
            </div>
          </div>
          <div className="q-divider" />
        </>
      )}

      <div className="qstat-line">
        <span className="qstat gold">
          <span>{age.icon}</span>
          {age.label}
        </span>
        {tail.map((t) => (
          <Fragment key={t.key}>
            <span className="dot">·</span>
            <span className={t.muted ? 'qstat muted' : 'qstat'}>
              {t.icon && <span>{t.icon}</span>}
              {t.text}
            </span>
          </Fragment>
        ))}
      </div>
    </div>
  );
};

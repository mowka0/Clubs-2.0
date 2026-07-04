import { FC } from 'react';
import { Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useClubStatsQuery } from '../../queries/clubStats';
import { buildAttention, buildLevers, buildNudges, type LeverVM } from './clubStats';
import { WinBackNudge } from './WinBackNudge';
import type { TrendDto } from '../../types/api';

const TrendBadge: FC<{ trend: TrendDto | null }> = ({ trend }) => {
  if (!trend || trend.direction === 'flat') return null;
  const up = trend.direction === 'up';
  return (
    <span className={`rd-lever-trend ${up ? 'rd-up' : 'rd-down'}`}>
      {up ? '↑' : '↓'} {Math.abs(trend.delta)}%
    </span>
  );
};

const Lever: FC<{ lever: LeverVM }> = ({ lever }) => (
  <div className="rd-lever">
    <span className="rd-lever-l">{lever.label}</span>
    <span className={`rd-lever-v rd-tone-${lever.tone}`}>
      {lever.value}
      <TrendBadge trend={lever.trend} />
    </span>
  </div>
);

/**
 * Панель «Статистика», видна только владельцу — рычаги роста, действенные подсказки и приватная
 * «зона внимания» (§9 docs/modules/club-quality.md). Fail-soft: спиннер во время загрузки, плейсхолдер при ошибке.
 */
export const ClubStatsTab: FC<{ clubId: string }> = ({ clubId }) => {
  const statsQuery = useClubStatsQuery(clubId);
  const stats = statsQuery.data;

  if (statsQuery.isPending) {
    return (
      <div className="rd-spinner-row">
        <Spinner size="m" />
      </div>
    );
  }

  if (!stats) {
    return <Placeholder description="Не удалось загрузить статистику" />;
  }

  const levers = buildLevers(stats);
  const nudges = buildNudges(stats);
  const attention = buildAttention(stats);

  return (
    <>
      <div className="rd-section-sub-h">Рычаги роста</div>
      <div className="rd-glass rd-lever-group">
        {levers.map((lever) => (
          <Lever key={lever.key} lever={lever} />
        ))}
      </div>

      <div className="rd-section-sub-h">Что сделать сейчас</div>
      {nudges.length > 0 ? (
        <div className="rd-nudges">
          {nudges.map((nudge) =>
            nudge.key === 'win_back' ? (
              <WinBackNudge key={nudge.key} nudge={nudge} clubId={clubId} />
            ) : (
              <div key={nudge.key} className="rd-nudge">
                <span className={`rd-nudge-ico${nudge.severity === 'red' ? ' rd-red' : ''}`}>{nudge.icon}</span>
                <span className="rd-nudge-t">
                  <b>{nudge.lead}</b>
                  {nudge.rest}
                </span>
              </div>
            ),
          )}
        </div>
      ) : (
        <div className="rd-glass rd-nudge-empty">Сейчас всё под контролем — срочных действий нет.</div>
      )}

      <div className="rd-section-sub-h">
        Зона внимания <span className="rd-owner-badge">👁 только вам</span>
      </div>
      <div className="rd-glass rd-lever-group">
        {attention.map((item) => (
          <div key={item.key} className="rd-lever">
            <span className="rd-lever-l">{item.label}</span>
            <span className={`rd-lever-v rd-tone-${item.tone}`}>{item.value}</span>
          </div>
        ))}
      </div>
    </>
  );
};

import { FC } from 'react';
import { DonutRing } from '../reputation/DonutRing';
import {
  formatPeerSignal,
  formatReliabilityHeadline,
} from '../../features/applications-inbox/lib/peer-signal-format';
import type { PeerStatsDto } from '../../types/api';

/**
 * "Активность на платформе" block for the application review card: the cross-club
 * «надёжен в N из M клубов» donut + a participation line. The heading (rendered by the
 * caller) frames this as PARTICIPANT activity — the global signal is owner-blind by design.
 */
export const PlatformActivity: FC<{ stats: PeerStatsDto }> = ({ stats }) => {
  const { memberClubCount, reliableClubs, trackRecordClubs } = stats;
  const detail = formatPeerSignal(stats);

  // No clubs, or no club with a shown track record yet → can't chart "N из M"; show the line only.
  if (memberClubCount === 0 || trackRecordClubs === 0) {
    return <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{detail}</div>;
  }

  return (
    <div className="rd-glass rd-activity">
      <DonutRing
        size={92}
        fraction={reliableClubs / trackRecordClubs}
        color="var(--live)"
        ariaLabel={`Надёжен в ${reliableClubs} из ${trackRecordClubs} клубов`}
      >
        <span className="rd-ring-frac" style={{ fontSize: 18 }}>{reliableClubs}/{trackRecordClubs}</span>
        <span className="rd-ring-cap">КЛУБОВ</span>
      </DonutRing>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text)' }}>
          {formatReliabilityHeadline(reliableClubs, trackRecordClubs)}
        </div>
        <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 6, lineHeight: 1.4 }}>{detail}</div>
      </div>
    </div>
  );
};

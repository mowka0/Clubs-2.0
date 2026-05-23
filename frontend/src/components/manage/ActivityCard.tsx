import { FC } from 'react';
import type {
  ActivityItemDto,
  EventActivityDto,
  SkladchinaActivityDto,
} from '../../api/activities';

interface ActivityCardProps {
  activity: ActivityItemDto;
  onClick: () => void;
}

const DATETIME_FMT = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
});

function formatDatetime(iso: string): string {
  return DATETIME_FMT.format(new Date(iso));
}

function formatRub(kopecks: number): string {
  const rub = Math.floor(kopecks / 100);
  return `${rub.toLocaleString('ru-RU')} ₽`;
}

const cardWrapperStyle = (isCompleted: boolean): React.CSSProperties => ({
  position: 'relative',
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  width: '100%',
  padding: '14px 16px',
  borderRadius: 14,
  background: 'var(--tgui--secondary_bg_color, rgba(255,255,255,0.04))',
  border: '1px solid var(--tgui--divider, rgba(255,255,255,0.08))',
  textAlign: 'left',
  cursor: 'pointer',
  color: 'var(--tgui--text_color, #fff)',
  opacity: isCompleted ? 0.55 : 1,
});

const iconBadgeStyle: React.CSSProperties = {
  position: 'absolute',
  top: 10,
  left: 12,
  fontSize: 14,
  lineHeight: 1,
};

const completedBadgeStyle: React.CSSProperties = {
  position: 'absolute',
  top: 10,
  right: 12,
  fontSize: 11,
  padding: '2px 8px',
  borderRadius: 999,
  background: 'var(--tgui--hint_color, rgba(255,255,255,0.12))',
  color: 'var(--tgui--bg_color, #fff)',
  fontWeight: 600,
  letterSpacing: 0.3,
};

const titleStyle: React.CSSProperties = {
  fontSize: 15,
  fontWeight: 600,
  lineHeight: 1.3,
  marginTop: 2,
  paddingLeft: 24,
  paddingRight: 64,
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
};

const subtitleStyle: React.CSSProperties = {
  fontSize: 13,
  color: 'var(--tgui--hint_color, rgba(255,255,255,0.6))',
  lineHeight: 1.35,
  paddingLeft: 24,
};

const dimSubtitleStyle: React.CSSProperties = {
  ...subtitleStyle,
  color: 'var(--tgui--hint_color, rgba(255,255,255,0.5))',
};

const rightBadgeStyle: React.CSSProperties = {
  position: 'absolute',
  bottom: 12,
  right: 14,
  fontSize: 12,
  fontWeight: 600,
  padding: '3px 8px',
  borderRadius: 999,
  background: 'var(--tgui--divider, rgba(255,255,255,0.10))',
};

const progressTrackStyle: React.CSSProperties = {
  position: 'relative',
  height: 4,
  borderRadius: 4,
  background: 'var(--tgui--divider, rgba(255,255,255,0.10))',
  marginLeft: 24,
  marginTop: 4,
  overflow: 'hidden',
};

const progressFillStyle = (pct: number): React.CSSProperties => ({
  position: 'absolute',
  top: 0,
  left: 0,
  height: '100%',
  width: `${pct}%`,
  background: 'var(--brand-brass, #C9A063)',
  borderRadius: 4,
});

const reputationBadgeStyle: React.CSSProperties = {
  display: 'inline-block',
  fontSize: 11,
  padding: '2px 7px',
  marginLeft: 8,
  borderRadius: 999,
  background: 'rgba(255,170,80,0.15)',
  color: '#FFA850',
};

const EventCardBody: FC<{ event: EventActivityDto }> = ({ event }) => {
  const subtitle = `${formatDatetime(event.eventDatetime)} · ${event.locationText}`;
  return (
    <>
      <span style={iconBadgeStyle} aria-hidden="true">🗓</span>
      <span style={titleStyle}>{event.title}</span>
      <span style={subtitleStyle}>{subtitle}</span>
      {event.descriptionPreview !== null && (
        <span style={dimSubtitleStyle}>{event.descriptionPreview}</span>
      )}
      <span style={rightBadgeStyle}>
        {event.goingCount}/{event.participantLimit}
      </span>
    </>
  );
};

const SkladchinaCardBody: FC<{ skladchina: SkladchinaActivityDto }> = ({ skladchina }) => {
  const hasGoal =
    skladchina.totalGoalKopecks !== null && skladchina.totalGoalKopecks > 0;
  const pct = hasGoal
    ? Math.min(
        100,
        Math.max(
          0,
          Math.round(
            (skladchina.collectedKopecks / skladchina.totalGoalKopecks!) * 100,
          ),
        ),
      )
    : 0;

  return (
    <>
      <span style={iconBadgeStyle} aria-hidden="true">💰</span>
      <span style={titleStyle}>{skladchina.title}</span>
      <span style={subtitleStyle}>
        {hasGoal ? (
          <>
            {formatRub(skladchina.collectedKopecks)} /{' '}
            {formatRub(skladchina.totalGoalKopecks!)}
          </>
        ) : (
          <>{formatRub(skladchina.collectedKopecks)} собрано</>
        )}
        {skladchina.affectsReputation && (
          <span style={reputationBadgeStyle} title="Влияет на репутацию">
            ⚠️ Репутация
          </span>
        )}
      </span>
      {hasGoal && (
        <div style={progressTrackStyle} aria-hidden="true">
          <div style={progressFillStyle(pct)} />
        </div>
      )}
      <span style={rightBadgeStyle}>
        {skladchina.paidCount}/{skladchina.participantCount}
      </span>
    </>
  );
};

export const ActivityCard: FC<ActivityCardProps> = ({ activity, onClick }) => {
  const ariaLabel = activity.isCompleted
    ? `${activity.title}. Завершено`
    : activity.title;

  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={ariaLabel}
      style={cardWrapperStyle(activity.isCompleted)}
    >
      {activity.isCompleted && <span style={completedBadgeStyle}>Завершено</span>}
      {renderActivityBody(activity)}
    </button>
  );
};

function renderActivityBody(activity: ActivityItemDto) {
  switch (activity.type) {
    case 'event':
      return <EventCardBody event={activity} />;
    case 'skladchina':
      return <SkladchinaCardBody skladchina={activity} />;
  }
}

import { FC } from 'react';
import { useClubEventsTeaserQuery } from '../../queries/events';
import type { TeaserEventDto } from '../../types/api';

function formatTeaserDate(iso: string): string {
  return new Date(iso).toLocaleString('ru-RU', {
    day: 'numeric',
    month: 'long',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** Эмодзи формата — словарь бейджей карточек ленты («⚡ срочная / 🎟 обычная / 🌊 открытая»). */
function formatEmoji(event: TeaserEventDto): string {
  return event.isUrgent ? '⚡' : event.isOpenEvent ? '🌊' : '🎟';
}

/** Счётчик по фазе (F5-21): до Этапа 2 — голоса «идут», после — подтверждённый состав. */
function countLabel(event: TeaserEventDto): string {
  const finalComposition = event.status === 'stage_2' || event.status === 'completed';
  return finalComposition ? `подтвердили ${event.confirmedCount}` : `идут ${event.goingCount}`;
}

interface ClubEventsTeaserProps {
  clubId: string;
  /** Строка под афишей: что именно откроет доступ (вступление / взнос) — текст зависит от зрителя. */
  lockHint: string;
}

/**
 * Тизер-афиша клуба для смотрящего БЕЗ доступа к контенту (гость / frozen / expired):
 * ближайшие и прошедшие встречи в урезанной проекции — название, дата, формат, счётчик.
 * Места, фото и состава в данных нет по построению (бэкенд, ClubEventsTeaserDto) — блок
 * показывает, что клуб живой, не раскрывая приватного. Fail-soft: при загрузке/ошибке/пустой
 * афише не рендерится вовсе (как ClubQualityFacts).
 */
export const ClubEventsTeaser: FC<ClubEventsTeaserProps> = ({ clubId, lockHint }) => {
  const { data } = useClubEventsTeaserQuery(clubId, true);
  if (!data) return null;
  if (data.upcoming.length === 0 && data.past.length === 0) return null;

  return (
    <>
      <div className="rd-section-sub-h">Афиша клуба</div>
      <div className="rd-glass" style={{ padding: '8px 16px', marginBottom: 8 }}>
        {data.upcoming.map((event) => (
          <div
            key={event.id}
            style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '9px 0' }}
          >
            <span aria-hidden="true">{formatEmoji(event)}</span>
            <div style={{ minWidth: 0, flex: 1 }}>
              <div style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {event.title}
              </div>
              <div style={{ fontSize: 12, color: 'var(--text-dim)' }}>{formatTeaserDate(event.eventDatetime)}</div>
            </div>
            <span style={{ fontSize: 12, color: 'var(--text-dim)', flexShrink: 0 }}>{countLabel(event)}</span>
          </div>
        ))}

        {data.past.length > 0 && (
          <>
            <div style={{ fontSize: 11, color: 'var(--text-dim)', padding: '8px 0 2px', borderTop: data.upcoming.length > 0 ? '1px solid var(--hairline, rgba(128,128,128,.15))' : 'none' }}>
              Уже прошло — {data.totalPastCount}
            </div>
            {data.past.map((event) => (
              <div
                key={event.id}
                style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '7px 0', opacity: 0.55 }}
              >
                <span aria-hidden="true">{formatEmoji(event)}</span>
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {event.title}
                  </div>
                  <div style={{ fontSize: 12, color: 'var(--text-dim)' }}>{formatTeaserDate(event.eventDatetime)}</div>
                </div>
                <span style={{ fontSize: 12, color: 'var(--text-dim)', flexShrink: 0 }}>прошла</span>
              </div>
            ))}
          </>
        )}
      </div>
      <div style={{ fontSize: 12, color: 'var(--text-dim)', margin: '0 4px 14px' }}>
        🔒 {lockHint}
      </div>
    </>
  );
};

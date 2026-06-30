import { FC, useState } from 'react';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useChurnedMembersQuery } from '../../queries/clubStats';
import { useHaptic } from '../../hooks/useHaptic';
import { MemberProfileModal } from '../club/MemberProfileModal';
import type { ChurnedMemberDto, MemberListItemDto } from '../../types/api';
import type { NudgeVM } from './clubStats';

function getInitials(firstName: string, lastName: string | null): string {
  return firstName.charAt(0).toUpperCase() + (lastName ? lastName.charAt(0).toUpperCase() : '');
}

const MS_PER_DAY = 86_400_000;

/** Gender-neutral relative time since departure — the «Верните ушедших» header supplies the verb. */
function formatLeftAgo(iso: string): string {
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / MS_PER_DAY);
  if (days <= 0) return 'сегодня';
  if (days === 1) return 'вчера';
  if (days < 7) return `${days} дн. назад`;
  return new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
}

/** Open the existing per-club profile card for a churned member (it fetches the full profile by id). */
function toMemberStub(member: ChurnedMemberDto): MemberListItemDto {
  return {
    userId: member.userId,
    firstName: member.firstName,
    lastName: member.lastName,
    avatarUrl: member.avatarUrl,
    role: 'member',
    joinedAt: null,
    trust: null,
    promiseFulfillmentPct: null,
    totalConfirmations: null,
    awards: [],
  };
}

interface WinBackNudgeProps {
  nudge: NudgeVM;
  clubId: string;
}

/**
 * The «Верните N ушедших» nudge, expandable into the roster of former members. Each row opens the
 * same profile card as the «Участники» tab. The roster loads lazily (only once expanded).
 */
export const WinBackNudge: FC<WinBackNudgeProps> = ({ nudge, clubId }) => {
  const haptic = useHaptic();
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<ChurnedMemberDto | null>(null);
  const churnedQuery = useChurnedMembersQuery(clubId, open);
  const members = churnedQuery.data ?? [];

  const toggle = () => { haptic.impact('light'); setOpen((v) => !v); };

  return (
    <>
      <button type="button" className="rd-nudge rd-nudge-btn" onClick={toggle} aria-expanded={open}>
        <span className="rd-nudge-ico">{nudge.icon}</span>
        <span className="rd-nudge-t">
          <b>{nudge.lead}</b>
          {nudge.rest}
        </span>
        <span className={`rd-nudge-chev${open ? ' rd-open' : ''}`} aria-hidden="true">›</span>
      </button>

      {open && (
        <div className="rd-glass rd-rep-panel rd-churned-list">
          {churnedQuery.isPending ? (
            <div className="rd-spinner-row" style={{ padding: '12px 0' }}><Spinner size="s" /></div>
          ) : members.length === 0 ? (
            <div className="rd-churned-empty">Список пуст — все вернулись или ещё не ушли.</div>
          ) : (
            members.map((member) => {
              const fullName = `${member.firstName}${member.lastName ? ` ${member.lastName}` : ''}`;
              return (
                <button
                  key={member.userId}
                  type="button"
                  className="rd-rep-row"
                  onClick={() => { haptic.impact('light'); setSelected(member); }}
                >
                  <span className="rd-ico">
                    {member.avatarUrl
                      ? <img src={member.avatarUrl} alt="" />
                      : getInitials(member.firstName, member.lastName)}
                  </span>
                  <div className="rd-info">
                    <div className="rd-ttl">{fullName}</div>
                    <div className="rd-met">{formatLeftAgo(member.leftAt)}</div>
                  </div>
                  <span className="rd-nudge-chev" aria-hidden="true">›</span>
                </button>
              );
            })
          )}
        </div>
      )}

      {selected && (
        <MemberProfileModal
          member={toMemberStub(selected)}
          clubId={clubId}
          onClose={() => setSelected(null)}
        />
      )}
    </>
  );
};

import { FC, useState } from 'react';
import { Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useClubMembersQuery } from '../../queries/members';
import { useHaptic } from '../../hooks/useHaptic';
import { MemberProfileModal } from './MemberProfileModal';
import type { MemberListItemDto } from '../../types/api';

interface ClubMembersTabProps {
  clubId: string;
}

function getInitials(firstName: string, lastName: string | null): string {
  const first = firstName.charAt(0).toUpperCase();
  const last = lastName ? lastName.charAt(0).toUpperCase() : '';
  return first + last;
}

function reliabilityTier(score: number): 'high' | 'mid' | 'low' {
  if (score >= 85) return 'high';
  if (score >= 70) return 'mid';
  return 'low';
}

export const ClubMembersTab: FC<ClubMembersTabProps> = ({ clubId }) => {
  const haptic = useHaptic();
  const [selectedMember, setSelectedMember] = useState<MemberListItemDto | null>(null);
  const membersQuery = useClubMembersQuery(clubId);

  if (membersQuery.isPending) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: 40 }}>
        <Spinner size="l" />
      </div>
    );
  }

  if (membersQuery.error) {
    return (
      <div style={{ padding: '0 20px' }}>
        <Placeholder header="Ошибка" description={membersQuery.error.message} />
      </div>
    );
  }

  const members = membersQuery.data ?? [];

  return (
    <>
      <div className="cp-section-label">Участники ({members.length})</div>

      {members.length === 0 ? (
        <div style={{ padding: '0 20px' }}>
          <Placeholder description="Список участников пуст" />
        </div>
      ) : (
        <div className="cp-members">
          {members.map((member) => {
            const fullName = `${member.firstName}${member.lastName ? ` ${member.lastName}` : ''}`;
            const tier = reliabilityTier(member.reliabilityIndex);
            return (
              <button
                key={member.userId}
                type="button"
                className="cp-member"
                onClick={() => { haptic.impact('light'); setSelectedMember(member); }}
              >
                <div className="avt">
                  {member.avatarUrl
                    ? <img src={member.avatarUrl} alt="" />
                    : getInitials(member.firstName, member.lastName)}
                </div>
                <div className="body">
                  <div className="name">
                    <span>{fullName}</span>
                    {member.role === 'organizer' && (
                      <span className="org-badge">Организатор</span>
                    )}
                  </div>
                  <div className="reliability">
                    <span className={`dot ${tier}`} />
                    <span>Надёжность <span className="num">{member.reliabilityIndex}</span></span>
                  </div>
                </div>
              </button>
            );
          })}
        </div>
      )}

      {selectedMember && (
        <MemberProfileModal
          member={selectedMember}
          clubId={clubId}
          onClose={() => setSelectedMember(null)}
        />
      )}
    </>
  );
};

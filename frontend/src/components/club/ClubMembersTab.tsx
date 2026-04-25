import { FC, useState } from 'react';
import { Section, Cell, Spinner, Placeholder, Avatar, Badge } from '@telegram-apps/telegram-ui';
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
      <Section>
        <Placeholder
          header="Ошибка"
          description={membersQuery.error.message}
        />
      </Section>
    );
  }

  const members = membersQuery.data ?? [];

  return (
    <>
      <Section header={`Участники (${members.length})`}>
        {members.length === 0 && (
          <Placeholder description="Список участников пуст" />
        )}
        {members.map((member) => (
          <Cell
            key={member.userId}
            onClick={() => { haptic.impact('light'); setSelectedMember(member); }}
            before={
              member.avatarUrl ? (
                <Avatar src={member.avatarUrl} size={40} />
              ) : (
                <Avatar
                  size={40}
                  acronym={getInitials(member.firstName, member.lastName)}
                />
              )
            }
            subtitle={`Надёжность: ${member.reliabilityIndex}`}
            after={
              member.role === 'organizer' ? (
                <Badge type="number" mode="primary">
                  Организатор
                </Badge>
              ) : undefined
            }
            multiline
          >
            {member.firstName}{member.lastName ? ` ${member.lastName}` : ''}
          </Cell>
        ))}
      </Section>
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

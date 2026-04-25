import { FC } from 'react';
import { Modal, Section, Cell, Text, Avatar, Spinner } from '@telegram-apps/telegram-ui';
import { useMemberProfileQuery } from '../../queries/members';
import type { MemberListItemDto } from '../../types/api';

interface MemberProfileModalProps {
  member: MemberListItemDto;
  clubId: string;
  onClose: () => void;
}

export const MemberProfileModal: FC<MemberProfileModalProps> = ({ member, clubId, onClose }) => {
  const profileQuery = useMemberProfileQuery(clubId, member.userId);
  const profile = profileQuery.data;
  const loading = profileQuery.isPending;

  const joinedAt = member.joinedAt
    ? new Date(member.joinedAt).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' })
    : '—';

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <div style={{ padding: 16 }}>
        {/* Header */}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
          <Text weight="2" style={{ fontSize: 18 }}>Профиль участника</Text>
          <button onClick={onClose} style={{ background: 'none', border: 'none', fontSize: 20, cursor: 'pointer', color: 'var(--tgui--text_color)' }}>&#x2715;</button>
        </div>

        {/* Avatar + name */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
          <Avatar
            size={48}
            src={member.avatarUrl ?? undefined}
            acronym={`${member.firstName.charAt(0)}${member.lastName?.charAt(0) ?? ''}`}
          />
          <div>
            <Text weight="1" style={{ fontSize: 17, display: 'block' }}>
              {member.firstName} {member.lastName ?? ''}
            </Text>
            {profile?.username && (
              <Text style={{ fontSize: 13, color: 'var(--tgui--hint_color)', display: 'block' }}>
                @{profile.username}
              </Text>
            )}
          </div>
        </div>

        {/* Status in club */}
        <Section header="Статус в клубе">
          <Cell subtitle="Роль">
            {member.role === 'organizer' ? 'Организатор' : 'Участник'}
          </Cell>
          <Cell subtitle="В клубе с">{joinedAt}</Cell>
        </Section>

        {/* Reputation */}
        {loading ? (
          <div style={{ display: 'flex', justifyContent: 'center', padding: 16 }}>
            <Spinner size="s" />
          </div>
        ) : profile ? (
          <Section header="Репутация">
            <Cell subtitle="Индекс надёжности">{profile.reliabilityIndex}</Cell>
            <Cell subtitle="Выполнение обещаний">{profile.promiseFulfillmentPct}%</Cell>
            <Cell subtitle="Подтверждений участия">{profile.totalConfirmations}</Cell>
            <Cell subtitle="Посещений событий">{profile.totalAttendances}</Cell>
          </Section>
        ) : null}
      </div>
    </Modal>
  );
};

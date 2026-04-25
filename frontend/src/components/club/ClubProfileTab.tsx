import { FC } from 'react';
import { Section, Cell, Spinner, Placeholder, Avatar, Text } from '@telegram-apps/telegram-ui';
import { useMemberProfileQuery } from '../../queries/members';

interface ClubProfileTabProps {
  clubId: string;
  userId: string;
}

function getInitials(firstName: string, lastName: string | null): string {
  const first = firstName.charAt(0).toUpperCase();
  const last = lastName ? lastName.charAt(0).toUpperCase() : '';
  return first + last;
}

export const ClubProfileTab: FC<ClubProfileTabProps> = ({ clubId, userId }) => {
  const memberProfileQuery = useMemberProfileQuery(clubId, userId);

  if (memberProfileQuery.isPending) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: 40 }}>
        <Spinner size="l" />
      </div>
    );
  }

  const memberProfile = memberProfileQuery.data;

  if (memberProfileQuery.error || !memberProfile) {
    return (
      <Section>
        <Placeholder
          header="Профиль недоступен"
          description="Не удалось загрузить ваш профиль в этом клубе"
        />
      </Section>
    );
  }

  return (
    <>
      <Section>
        <div style={{ padding: 20, display: 'flex', gap: 16, alignItems: 'center' }}>
          {memberProfile.avatarUrl ? (
            <Avatar src={memberProfile.avatarUrl} size={48} />
          ) : (
            <Avatar
              size={48}
              acronym={getInitials(
                memberProfile.firstName,
                memberProfile.username ?? memberProfile.firstName,
              )}
            />
          )}
          <div>
            <Text weight="1" style={{ fontSize: 20, display: 'block' }}>
              {memberProfile.firstName}
            </Text>
            {memberProfile.username && (
              <span style={{ color: 'var(--tgui--hint_color)', fontSize: 14 }}>
                @{memberProfile.username}
              </span>
            )}
          </div>
        </div>
      </Section>

      <Section header="Репутация">
        <Cell
          after={
            <span style={{ color: 'var(--tgui--hint_color)' }}>
              {memberProfile.reliabilityIndex}
            </span>
          }
        >
          Индекс надёжности
        </Cell>
        <Cell
          after={
            <span style={{ color: 'var(--tgui--hint_color)' }}>
              {memberProfile.promiseFulfillmentPct}%
            </span>
          }
        >
          Выполнение обещаний
        </Cell>
        <Cell
          after={
            <span style={{ color: 'var(--tgui--hint_color)' }}>
              {memberProfile.totalConfirmations}
            </span>
          }
        >
          Подтверждений
        </Cell>
      </Section>
    </>
  );
};

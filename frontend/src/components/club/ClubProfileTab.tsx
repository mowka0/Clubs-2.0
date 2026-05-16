import { FC } from 'react';
import { Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useMemberProfileQuery } from '../../queries/members';

interface ClubProfileTabProps {
  clubId: string;
  userId: string;
}

function getInitials(firstName: string, fallback: string | null): string {
  const first = firstName.charAt(0).toUpperCase();
  const last = fallback ? fallback.charAt(0).toUpperCase() : '';
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
      <div style={{ padding: '0 20px' }}>
        <Placeholder
          header="Профиль недоступен"
          description="Не удалось загрузить ваш профиль в этом клубе"
        />
      </div>
    );
  }

  return (
    <>
      <div className="cp-profile-head">
        <div className="avt">
          {memberProfile.avatarUrl
            ? <img src={memberProfile.avatarUrl} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover', borderRadius: '50%' }} />
            : getInitials(memberProfile.firstName, memberProfile.username)}
        </div>
        <div>
          <div className="name">{memberProfile.firstName}</div>
          {memberProfile.username && (
            <div className="handle">@{memberProfile.username}</div>
          )}
        </div>
      </div>

      <div className="cp-profile-stats">
        <div className="cp-profile-stat">
          <span className="num">{memberProfile.reliabilityIndex}</span>
          <span className="label">Индекс надёжности</span>
        </div>
        <div className="cp-profile-stat">
          <span className="num">{memberProfile.promiseFulfillmentPct}%</span>
          <span className="label">Выполнение обещаний</span>
        </div>
        <div className="cp-profile-stat">
          <span className="num">{memberProfile.totalConfirmations}</span>
          <span className="label">Подтверждений</span>
        </div>
      </div>
    </>
  );
};

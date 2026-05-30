import { FC, useState } from 'react';
import { Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import {
  useClubAwaitingPaymentApplicantsQuery,
  useClubMembersQuery,
} from '../../queries/members';
import { useHaptic } from '../../hooks/useHaptic';
import { MemberProfileModal } from './MemberProfileModal';
import type { AwaitingPaymentApplicantDto, MemberListItemDto } from '../../types/api';

interface ClubMembersTabProps {
  clubId: string;
  /**
   * Whether the caller is the organizer of [clubId]. Gates the
   * «Ожидают оплаты» section query — backend enforces 403 for non-organizers,
   * but we skip the request entirely from member/visitor contexts to avoid
   * a guaranteed-fail round-trip.
   */
  isOrganizer?: boolean;
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

/** Russian plural picker — same shape as MyClubsPage helper. */
function pluralRu(n: number, forms: [string, string, string]): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return forms[0];
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return forms[1];
  return forms[2];
}

/** Russian relative date for «одобрено …» meta on awaiting-payment rows. */
function formatRelativeApprovedAt(iso: string): string {
  const approvedAt = new Date(iso);
  const now = new Date();
  const ms = now.getTime() - approvedAt.getTime();
  const days = Math.floor(ms / (1000 * 60 * 60 * 24));
  if (days <= 0) return 'одобрено сегодня';
  if (days === 1) return 'одобрено вчера';
  if (days < 7) return `одобрено ${days} ${pluralRu(days, ['день', 'дня', 'дней'])} назад`;
  return `одобрено ${approvedAt.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })}`;
}

interface AwaitingPaymentRowProps {
  applicant: AwaitingPaymentApplicantDto;
}

const AwaitingPaymentRow: FC<AwaitingPaymentRowProps> = ({ applicant }) => {
  const fullName = `${applicant.firstName}${applicant.lastName ? ` ${applicant.lastName}` : ''}`;
  return (
    <div className="cp-member cp-member-awaiting-payment">
      <div className="avt">
        {applicant.avatarUrl
          ? <img src={applicant.avatarUrl} alt="" />
          : getInitials(applicant.firstName, applicant.lastName)}
      </div>
      <div className="body">
        <div className="name">
          <span>{fullName}</span>
          {applicant.telegramUsername && (
            <span className="username">@{applicant.telegramUsername}</span>
          )}
        </div>
        <div className="reliability">
          <span>{formatRelativeApprovedAt(applicant.approvedAt)}</span>
        </div>
      </div>
      <span className="mc-app-status status awaiting-payment">Ожидает оплаты</span>
    </div>
  );
};

export const ClubMembersTab: FC<ClubMembersTabProps> = ({ clubId, isOrganizer = false }) => {
  const haptic = useHaptic();
  const [selectedMember, setSelectedMember] = useState<MemberListItemDto | null>(null);
  const membersQuery = useClubMembersQuery(clubId);
  const awaitingQuery = useClubAwaitingPaymentApplicantsQuery(clubId, { enabled: isOrganizer });

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
  const awaitingApplicants = awaitingQuery.data ?? [];

  return (
    <>
      {isOrganizer && awaitingApplicants.length > 0 && (
        <>
          <div className="cp-section-label">Ожидают оплаты · {awaitingApplicants.length}</div>
          <div className="cp-members">
            {awaitingApplicants.map((applicant) => (
              <AwaitingPaymentRow key={applicant.applicationId} applicant={applicant} />
            ))}
          </div>
        </>
      )}

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

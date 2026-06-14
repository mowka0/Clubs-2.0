import { FC, useState } from 'react';
import { Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import {
  useClubAwaitingPaymentApplicantsQuery,
  useClubMembersQuery,
} from '../../queries/members';
import { useHaptic } from '../../hooks/useHaptic';
import { MemberProfileModal } from './MemberProfileModal';
import { reliabilityTier } from '../../utils/reputationTier';
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
    <div className="rd-rep-row" style={{ cursor: 'default' }}>
      <span className="rd-ico">
        {applicant.avatarUrl
          ? <img src={applicant.avatarUrl} alt="" />
          : getInitials(applicant.firstName, applicant.lastName)}
      </span>
      <div className="rd-info">
        <div className="rd-ttl">
          {fullName}
          {applicant.telegramUsername && (
            <span style={{ color: 'var(--text-faint)', fontWeight: 400, marginLeft: 6 }}>
              @{applicant.telegramUsername}
            </span>
          )}
        </div>
        <div className="rd-met">{formatRelativeApprovedAt(applicant.approvedAt)}</div>
      </div>
      <span className="rd-badge rd-warn">Ожидает оплаты</span>
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
      <div className="rd-spinner-row">
        <Spinner size="l" />
      </div>
    );
  }

  if (membersQuery.error) {
    return <Placeholder header="Ошибка" description={membersQuery.error.message} />;
  }

  const members = membersQuery.data ?? [];
  const awaitingApplicants = awaitingQuery.data ?? [];

  return (
    <>
      {isOrganizer && awaitingApplicants.length > 0 && (
        <>
          <div className="rd-section-sub-h">
            Ожидают оплаты <span className="rd-count">· {awaitingApplicants.length}</span>
          </div>
          <div className="rd-glass rd-rep-panel">
            {awaitingApplicants.map((applicant) => (
              <AwaitingPaymentRow key={applicant.applicationId} applicant={applicant} />
            ))}
          </div>
        </>
      )}

      <div className="rd-section-sub-h">
        Участники <span className="rd-count">· {members.length}</span>
      </div>

      {members.length === 0 ? (
        <div className="rd-glass rd-empty">
          <div className="rd-sub">Список участников пуст</div>
        </div>
      ) : (
        <div className="rd-glass rd-rep-panel">
          {members.map((member) => {
            const fullName = `${member.firstName}${member.lastName ? ` ${member.lastName}` : ''}`;
            const isOwner = member.role === 'organizer';
            const hasScore = member.trust !== null;
            const tier = reliabilityTier(member.trust);
            return (
              <button
                key={member.userId}
                type="button"
                className="rd-rep-row"
                onClick={() => { haptic.impact('light'); setSelectedMember(member); }}
              >
                <span className="rd-ico">
                  {member.avatarUrl
                    ? <img src={member.avatarUrl} alt="" />
                    : getInitials(member.firstName, member.lastName)}
                </span>
                <div className="rd-info">
                  <div className="rd-ttl">
                    {fullName}
                    {member.role === 'organizer' && (
                      <span className="rd-badge rd-rep" style={{ marginLeft: 8, fontSize: 10, padding: '2px 8px' }}>
                        Орг
                      </span>
                    )}
                    {member.levelName && (
                      <span className="rd-badge rd-neutral" style={{ marginLeft: 8, fontSize: 10, padding: '2px 8px' }}>
                        {member.levelName}
                      </span>
                    )}
                  </div>
                  <div className="rd-met">
                    {hasScore
                      ? `Обещания ${Math.round(member.promiseFulfillmentPct ?? 0)}%`
                      : isOwner
                        ? 'Репутация за организаторские качества'
                        : 'Пока нет данных'}
                  </div>
                </div>
                <span className="rd-score">
                  {hasScore ? (
                    <>
                      <span className={`rd-v rd-${tier}`}>{member.trust}</span>
                      <span className="rd-cap">надёжность</span>
                    </>
                  ) : (
                    <span className="rd-v rd-new">{isOwner ? 'Орг' : 'Новичок'}</span>
                  )}
                </span>
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

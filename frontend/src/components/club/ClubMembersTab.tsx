import { FC, useState } from 'react';
import { Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useClubMembersQuery, useMarkMemberDuesPaidMutation } from '../../queries/members';
import { useHaptic } from '../../hooks/useHaptic';
import { ApiError } from '../../api/apiClient';
import { Toast } from '../Toast';
import { MemberProfileModal } from './MemberProfileModal';
import { reliabilityTier } from '../../utils/reputationTier';
import type { MemberListItemDto } from '../../types/api';

interface ClubMembersTabProps {
  clubId: string;
  /**
   * Whether the caller is the organizer of [clubId]. The organizer-only view splits paid members
   * into the «Скоро закончится» / «Ждут оплаты» / «Активные» buckets and exposes the «Взнос получен»
   * action. Regular members get the active-only calm list (the access fields come back null).
   */
  isOrganizer?: boolean;
}

// Paid access ending within this window surfaces in «Скоро закончится» (mirrors the backend red-dot).
const EXPIRING_SOON_DAYS = 7;
const MS_PER_DAY = 86_400_000;

function getInitials(firstName: string, lastName: string | null): string {
  const first = firstName.charAt(0).toUpperCase();
  const last = lastName ? lastName.charAt(0).toUpperCase() : '';
  return first + last;
}

function fullNameOf(member: MemberListItemDto): string {
  return `${member.firstName}${member.lastName ? ` ${member.lastName}` : ''}`;
}

function pluralRu(n: number, forms: [string, string, string]): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return forms[0];
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return forms[1];
  return forms[2];
}

/** Whole days from now until [iso] (rounded up — "через 3 дня"). Negative once past. */
function daysUntil(iso: string): number {
  return Math.ceil((new Date(iso).getTime() - Date.now()) / MS_PER_DAY);
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
}

/** «до 28 июня · через 3 дня» for the «Скоро закончится» bucket. */
function formatExpiringMeta(iso: string): string {
  const days = daysUntil(iso);
  const date = `до ${formatDate(iso)}`;
  if (days <= 0) return `${date} · истекла`;
  if (days === 1) return `${date} · завтра`;
  return `${date} · через ${days} ${pluralRu(days, ['день', 'дня', 'дней'])}`;
}

/** «вступил(а) 2 дня назад» for the «Ждут оплаты» (frozen) bucket. */
function formatJoinedMeta(iso: string | null): string {
  if (!iso) return 'ждёт первой оплаты';
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / MS_PER_DAY);
  if (days <= 0) return 'вступил(а) сегодня';
  if (days === 1) return 'вступил(а) вчера';
  if (days < 7) return `вступил(а) ${days} ${pluralRu(days, ['день', 'дня', 'дней'])} назад`;
  return `вступил(а) ${formatDate(iso)}`;
}

type Bucket = 'expiring' | 'awaiting' | 'calm';

/**
 * Bucket a member by access state (de-Stars dashboard). For a regular viewer the access fields are
 * null, so every member lands in «calm» — the legacy active-only list.
 */
function bucketOf(member: MemberListItemDto): Bucket {
  if (member.accessStatus === 'frozen') return 'awaiting';
  if (member.accessStatus === 'active' && member.subscriptionExpiresAt
      && daysUntil(member.subscriptionExpiresAt) <= EXPIRING_SOON_DAYS) {
    return 'expiring';
  }
  return 'calm';
}

interface DuesActionRowProps {
  clubId: string;
  member: MemberListItemDto;
  metaText: string;
  onOpenProfile: (member: MemberListItemDto) => void;
  onFeedback: (message: string) => void;
}

/**
 * A «Скоро закончится» / «Ждут оплаты» row: tap the member to open the profile card; the «Взнос
 * получен» button opens access and extends the paid window +30d. The row is a div (not a button) so
 * the two tap targets don't nest. 409 (lost race) is swallowed — the list cache is already refreshed.
 */
const DuesActionRow: FC<DuesActionRowProps> = ({ clubId, member, metaText, onOpenProfile, onFeedback }) => {
  const haptic = useHaptic();
  const markPaid = useMarkMemberDuesPaidMutation();

  const handleDuesPaid = () => {
    if (markPaid.isPending) return;
    haptic.impact('medium');
    markPaid.mutate(
      { clubId, userId: member.userId },
      {
        onSuccess: () => {
          haptic.notify('success');
          onFeedback(`Взнос принят — доступ ${member.firstName} продлён на 30 дней`);
        },
        onError: (e) => {
          if (e instanceof ApiError && e.status === 409) return;
          haptic.notify('error');
          onFeedback(e instanceof Error ? e.message : 'Не удалось отметить взнос');
        },
      },
    );
  };

  return (
    <div className="rd-rep-row">
      <button
        type="button"
        className="rd-rep-row-main"
        onClick={() => { haptic.impact('light'); onOpenProfile(member); }}
      >
        <span className="rd-ico">
          {member.avatarUrl ? <img src={member.avatarUrl} alt="" /> : getInitials(member.firstName, member.lastName)}
        </span>
        <div className="rd-info">
          <div className="rd-ttl">{fullNameOf(member)}</div>
          <div className="rd-met">{metaText}</div>
        </div>
      </button>
      <button type="button" className="rd-row-act-pri" disabled={markPaid.isPending} onClick={handleDuesPaid}>
        {markPaid.isPending ? '…' : 'Взнос получен'}
      </button>
    </div>
  );
};

interface CalmMemberRowProps {
  member: MemberListItemDto;
  forOrganizer: boolean;
  onOpenProfile: (member: MemberListItemDto) => void;
}

/** Calm «Активные» row: reputation score on the right; for the organizer a paid member also shows
 *  «Активен · до DATE». */
const CalmMemberRow: FC<CalmMemberRowProps> = ({ member, forOrganizer, onOpenProfile }) => {
  const haptic = useHaptic();
  const isOwner = member.role === 'organizer';
  const hasScore = member.trust !== null;
  // Show the promise line only when there's an event track; a finance-only member (skladchina
  // record, 0 confirmations) keeps the score but hides the misleading "Обещания 0%" (F5-08).
  const hasActivity = hasScore && (member.totalConfirmations ?? 0) > 0;
  const tier = reliabilityTier(member.trust);
  const repMeta = hasActivity
    ? `Обещания ${Math.round(member.promiseFulfillmentPct ?? 0)}%`
    : isOwner
      ? 'Репутация за организаторские качества'
      : hasScore
        ? null
        : 'Пока нет данных';
  // Organizer-only access line for a paid active member (free memberships have no expiry).
  const accessMeta = forOrganizer && !isOwner && member.subscriptionExpiresAt
    ? `Активен · до ${formatDate(member.subscriptionExpiresAt)}`
    : null;
  // Public club awards (R3) — defend against a payload without the field (boundary isn't schema-checked).
  const awards = member.awards ?? [];

  return (
    <button
      type="button"
      className="rd-rep-row"
      onClick={() => { haptic.impact('light'); onOpenProfile(member); }}
    >
      <span className="rd-ico">
        {member.avatarUrl ? <img src={member.avatarUrl} alt="" /> : getInitials(member.firstName, member.lastName)}
      </span>
      <div className="rd-info">
        <div className="rd-ttl">
          {fullNameOf(member)}
          {isOwner && (
            <span className="rd-badge rd-rep" style={{ marginLeft: 8, fontSize: 10, padding: '2px 8px' }}>Орг</span>
          )}
        </div>
        {accessMeta && <div className="rd-met rd-met-ok">{accessMeta}</div>}
        {repMeta && <div className="rd-met">{repMeta}</div>}
        {awards.length > 0 && (
          <div className="rd-member-awards">
            {awards.map((a) => (
              <span key={a.id} className="rd-award-chip rd-award-chip-ro rd-award-chip-sm">
                <span className="rd-award-emoji" aria-hidden="true">{a.emoji}</span>{a.label}
              </span>
            ))}
          </div>
        )}
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
};

export const ClubMembersTab: FC<ClubMembersTabProps> = ({ clubId, isOrganizer = false }) => {
  const [selectedMember, setSelectedMember] = useState<MemberListItemDto | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const membersQuery = useClubMembersQuery(clubId);

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
  // Buckets are only meaningful for the organizer; a regular viewer gets active-only rows (calm).
  const expiring = isOrganizer ? members.filter((m) => bucketOf(m) === 'expiring') : [];
  const awaiting = isOrganizer ? members.filter((m) => bucketOf(m) === 'awaiting') : [];
  const calm = isOrganizer ? members.filter((m) => bucketOf(m) === 'calm') : members;

  return (
    <>
      {expiring.length > 0 && (
        <>
          <div className="rd-section-sub-h rd-attn-exp">
            ⏳ Скоро закончится <span className="rd-count">· {expiring.length}</span>
          </div>
          <div className="rd-attn-hint">Подписка кончается в ближайшую неделю. Получил продление — подтверди.</div>
          <div className="rd-glass rd-rep-panel rd-attn-block rd-attn-block-exp">
            {expiring.map((member) => (
              <DuesActionRow
                key={member.userId}
                clubId={clubId}
                member={member}
                metaText={member.subscriptionExpiresAt ? formatExpiringMeta(member.subscriptionExpiresAt) : ''}
                onOpenProfile={setSelectedMember}
                onFeedback={setToast}
              />
            ))}
          </div>
        </>
      )}

      {awaiting.length > 0 && (
        <>
          <div className="rd-section-sub-h rd-attn-pay">
            💸 Ждут оплаты <span className="rd-count">· {awaiting.length}</span>
          </div>
          <div className="rd-attn-hint">Вступили, но ещё ни разу не платили — доступа нет.</div>
          <div className="rd-glass rd-rep-panel rd-attn-block rd-attn-block-pay">
            {awaiting.map((member) => (
              <DuesActionRow
                key={member.userId}
                clubId={clubId}
                member={member}
                metaText={formatJoinedMeta(member.joinedAt)}
                onOpenProfile={setSelectedMember}
                onFeedback={setToast}
              />
            ))}
          </div>
        </>
      )}

      <div className="rd-section-sub-h">
        Участники <span className="rd-count">· {calm.length}</span>
      </div>

      {calm.length === 0 ? (
        <div className="rd-glass rd-empty">
          <div className="rd-sub">Список участников пуст</div>
        </div>
      ) : (
        <div className="rd-glass rd-rep-panel">
          {calm.map((member) => (
            <CalmMemberRow
              key={member.userId}
              member={member}
              forOrganizer={isOrganizer}
              onOpenProfile={setSelectedMember}
            />
          ))}
        </div>
      )}

      {selectedMember && (
        <MemberProfileModal
          member={selectedMember}
          clubId={clubId}
          isOrganizer={isOrganizer}
          onClose={() => setSelectedMember(null)}
          onActionToast={setToast}
        />
      )}

      {toast && <Toast message={toast} onClose={() => setToast(null)} />}
    </>
  );
};

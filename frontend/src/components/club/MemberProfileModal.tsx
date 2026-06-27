import { FC, useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import {
  useFreezeMemberMutation,
  useMarkMemberDuesPaidMutation,
  useMemberProfileQuery,
  useSetMemberAccessUntilMutation,
  useUpdateMemberNoteMutation,
} from '../../queries/members';
import { useHaptic } from '../../hooks/useHaptic';
import { ApiError } from '../../api/apiClient';
import { pluralRu } from '../../utils/formatters';
import { DonutRing } from '../reputation/DonutRing';
import { TRUST_TIER_COLOR, trustTier } from '../reputation/trust-tier';
import type { MemberListItemDto, MemberProfileDto } from '../../types/api';

interface MemberProfileModalProps {
  member: MemberListItemDto;
  clubId: string;
  /** Organizer view — unlocks the «Подписка активна до …» strip + dues/freeze actions (de-Stars). */
  isOrganizer?: boolean;
  onClose: () => void;
  /** Surface a toast at the page level after a gate action succeeds. */
  onActionToast?: (message: string) => void;
}

const MS_PER_DAY = 86_400_000;
// Mirrors the backend honor-system period (membership.access-period-days) for the «продлить до …» preview.
const ACCESS_PERIOD_DAYS = 30;

function formatDateFull(iso: string): string {
  return new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
}

function daysUntil(iso: string): number {
  return Math.ceil((new Date(iso).getTime() - Date.now()) / MS_PER_DAY);
}

function relativeUntil(iso: string): string {
  const days = daysUntil(iso);
  if (days <= 0) return 'истекла';
  if (days === 1) return 'завтра';
  return `через ${days} ${pluralRu(days, ['день', 'дня', 'дней'])}`;
}

/** End date after «Взнос получен» — backend extends +30d from max(now, current expiry). */
function extendedEndLabel(iso: string | null): string {
  const base = iso ? Math.max(Date.now(), new Date(iso).getTime()) : Date.now();
  return formatDateFull(new Date(base + ACCESS_PERIOD_DAYS * MS_PER_DAY).toISOString());
}

interface OrganizerGateProps {
  clubId: string;
  member: MemberListItemDto;
  /** Current private note from the loaded profile (null until loaded / when empty). */
  organizerNote: string | null;
  onDone: (message: string) => void;
}

/** ISO datetime → yyyy-mm-dd for a `<input type="date">`. */
function toDateInput(iso: string | null): string {
  return iso ? new Date(iso).toISOString().slice(0, 10) : '';
}

/**
 * Organizer-only access controls (de-Stars) + edit mode (member admin S1, Variant B):
 *  - Immediate actions: «Взнос получен» (dues-paid → open + extend), «Закрыть доступ» (freeze).
 *  - ✎ Редактировать → form: «Своя дата» (custom access-window end) + «Заметка» (private note), saved together.
 * 409 (lost race) closes the card — the list cache is already refreshed.
 */
const OrganizerGate: FC<OrganizerGateProps> = ({ clubId, member, organizerNote, onDone }) => {
  const haptic = useHaptic();
  const markPaid = useMarkMemberDuesPaidMutation();
  const freeze = useFreezeMemberMutation();
  const setAccess = useSetMemberAccessUntilMutation();
  const updateNote = useUpdateMemberNoteMutation();
  const [error, setError] = useState<string | null>(null);

  const [editing, setEditing] = useState(false);
  const [noteDraft, setNoteDraft] = useState('');
  const [dateDraft, setDateDraft] = useState('');

  const busy = markPaid.isPending || freeze.isPending;
  const savingEdit = setAccess.isPending || updateNote.isPending;
  const frozen = member.accessStatus === 'frozen';
  const expiresAt = member.subscriptionExpiresAt ?? null;
  const soon = !frozen && !!expiresAt && daysUntil(expiresAt) <= 7;
  const today = new Date().toISOString().slice(0, 10);
  const originalDate = toDateInput(expiresAt);

  const run = (
    mutation: ReturnType<typeof useMarkMemberDuesPaidMutation> | ReturnType<typeof useFreezeMemberMutation>,
    successMessage: string,
  ) => {
    if (busy) return;
    setError(null);
    haptic.impact('medium');
    mutation.mutate(
      { clubId, userId: member.userId },
      {
        onSuccess: () => { haptic.notify('success'); onDone(successMessage); },
        onError: (e) => {
          if (e instanceof ApiError && e.status === 409) { onDone('Статус участника изменился'); return; }
          haptic.notify('error');
          setError(e instanceof Error ? e.message : 'Не удалось выполнить действие');
        },
      },
    );
  };

  const openEdit = () => {
    setError(null);
    setNoteDraft(organizerNote ?? '');
    setDateDraft(originalDate);
    setEditing(true);
  };

  const handleSaveEdit = async () => {
    setError(null);
    const tasks: Promise<unknown>[] = [];
    if (dateDraft && dateDraft !== originalDate) {
      // End-of-day local so «до 28 июля» grants access through the 28th.
      const untilIso = new Date(`${dateDraft}T23:59:59`).toISOString();
      tasks.push(setAccess.mutateAsync({ clubId, userId: member.userId, until: untilIso }));
    }
    const cleanNote = noteDraft.trim();
    if (cleanNote !== (organizerNote ?? '')) {
      tasks.push(updateNote.mutateAsync({ clubId, userId: member.userId, note: cleanNote || null }));
    }
    if (tasks.length === 0) { setEditing(false); return; }
    try {
      haptic.impact('medium');
      await Promise.all(tasks);
      haptic.notify('success');
      onDone('Изменения сохранены');
    } catch (e) {
      haptic.notify('error');
      setError(e instanceof Error ? e.message : 'Не удалось сохранить');
    }
  };

  const duesLabel = frozen
    ? 'Взнос получен · открыть доступ'
    : `Взнос получен · продлить до ${extendedEndLabel(expiresAt)}`;

  return (
    <div className="rd-org-gate">
      <div className={`rd-sub-strip${frozen || soon ? ' rd-soon' : ''}`}>
        <div style={{ minWidth: 0 }}>
          <div className="rd-sub-strip-k">{frozen ? 'Доступ закрыт' : 'Подписка активна до'}</div>
          <div className="rd-sub-strip-v">
            {frozen
              ? 'участник ждёт оплаты'
              : expiresAt
                ? `${formatDateFull(expiresAt)} · ${relativeUntil(expiresAt)}`
                : '—'}
          </div>
        </div>
        <span className="rd-org-tag">Только орг</span>
      </div>

      {error && <div className="rd-error" style={{ textAlign: 'left' }}>{error}</div>}

      <div className="rd-org-gate-acts">
        <button type="button" className="rd-btn-primary" disabled={busy} onClick={() => run(markPaid, duesLabel.includes('открыть') ? `Доступ ${member.firstName} открыт` : `Доступ ${member.firstName} продлён на 30 дней`)}>
          {markPaid.isPending ? <Spinner size="s" /> : duesLabel}
        </button>
        {!frozen && (
          <button
            type="button"
            className="rd-btn-outline"
            style={{ color: 'var(--danger)' }}
            disabled={busy}
            onClick={() => run(freeze, `Доступ ${member.firstName} закрыт`)}
          >
            {freeze.isPending ? <Spinner size="s" /> : 'Закрыть доступ'}
          </button>
        )}
      </div>

      {/* Member admin S1 (Variant B): ✎ reveals editable fields — custom access date + private note. */}
      {!editing && organizerNote && (
        <div className="rd-org-note-read">
          <span className="rd-org-note-k">Заметка</span>{organizerNote}
        </div>
      )}
      {!editing ? (
        <button type="button" className="rd-org-edit-toggle" onClick={openEdit}>
          ✎ Редактировать
        </button>
      ) : (
        <div className="rd-org-edit">
          <label className="rd-field">
            <span className="rd-label">Своя дата окончания доступа</span>
            <input
              type="date"
              className="rd-input"
              value={dateDraft}
              min={today}
              onChange={(e) => setDateDraft(e.target.value)}
            />
          </label>
          <label className="rd-field">
            <span className="rd-label">Заметка (видите только вы)</span>
            <textarea
              className="rd-textarea"
              rows={3}
              maxLength={500}
              placeholder="Например: помогает с площадкой для встреч"
              value={noteDraft}
              onChange={(e) => setNoteDraft(e.target.value)}
            />
          </label>
          <div className="rd-org-gate-acts">
            <button type="button" className="rd-btn-primary" disabled={savingEdit} onClick={handleSaveEdit}>
              {savingEdit ? <Spinner size="s" /> : 'Сохранить'}
            </button>
            <button type="button" className="rd-btn-outline" disabled={savingEdit} onClick={() => setEditing(false)}>
              Отмена
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

/**
 * Per-club reputation rings + spontaneity/role footer. Надёжность (smart composite) always shows;
 * Посещаемость (events) and Сборы (reputation-affecting skladchina) appear only when there's data,
 * so a member with only one axis never sees an empty "0/0" ring.
 */
const ReputationRings: FC<{ profile: MemberProfileDto }> = ({ profile }) => {
  const trust = profile.trust ?? 0;
  const confirmations = profile.totalConfirmations ?? 0;
  const attendances = profile.totalAttendances ?? 0;
  const attendancePct = confirmations > 0 ? Math.round((attendances / confirmations) * 100) : 0;
  const skladchinaPaid = profile.skladchinaPaid ?? 0;
  const skladchinaTotal = profile.skladchinaTotal ?? 0;
  const skladchinaPct = skladchinaTotal > 0 ? Math.round((skladchinaPaid / skladchinaTotal) * 100) : 0;
  const roleLabel = profile.role === 'organizer' ? 'Организатор' : 'Участник';

  return (
    <>
      <div className="rd-glass rd-rings">
        <div className="rd-ring">
          <DonutRing
            size={72}
            fraction={trust / 100}
            color={TRUST_TIER_COLOR[trustTier(trust)]}
            ariaLabel={`Надёжность ${trust} из 100`}
          >
            <span className="rd-ring-num">{trust}</span>
            <span className="rd-ring-cap">ИЗ 100</span>
          </DonutRing>
          <div className="rd-ring-l">надёжность</div>
        </div>

        {confirmations > 0 && (
          <div className="rd-ring">
            <DonutRing
              size={72}
              fraction={attendances / confirmations}
              color="var(--accent)"
              ariaLabel={`Посещаемость ${attendances} из ${confirmations}`}
            >
              <span className="rd-ring-frac">{attendances}/{confirmations}</span>
              <span className="rd-ring-cap">{attendancePct}%</span>
            </DonutRing>
            <div className="rd-ring-l">посещаемость</div>
          </div>
        )}

        {skladchinaTotal > 0 && (
          <div className="rd-ring">
            <DonutRing
              size={72}
              fraction={skladchinaPaid / skladchinaTotal}
              color="var(--accent)"
              ariaLabel={`Сборы: оплачено ${skladchinaPaid} из ${skladchinaTotal}`}
            >
              <span className="rd-ring-frac">{skladchinaPaid}/{skladchinaTotal}</span>
              <span className="rd-ring-cap">{skladchinaPct}%</span>
            </DonutRing>
            <div className="rd-ring-l">сборы</div>
          </div>
        )}
      </div>
      <div className="rd-rep-foot">
        {/* Spontaneity is an attendance metric — hide it for a finance-only member (no event
            track) so it doesn't read as a meaningless "Спонтанных визитов: 0" (F5-08). */}
        {confirmations > 0 && (
          <>Спонтанных визитов: <b>{profile.spontaneityCount ?? 0}</b> · </>
        )}
        Роль: {roleLabel}
      </div>
    </>
  );
};

export const MemberProfileModal: FC<MemberProfileModalProps> = ({
  member,
  clubId,
  isOrganizer = false,
  onClose,
  onActionToast,
}) => {
  const profileQuery = useMemberProfileQuery(clubId, member.userId);
  const profile = profileQuery.data;
  const loading = profileQuery.isPending;

  // Organizer gate (de-Stars): shown for a paid member (has an expiry window) or a frozen member.
  // Never for the organizer's own row — the backend rejects managing the organizer.
  const showGate =
    isOrganizer
    && member.role !== 'organizer'
    && (member.accessStatus === 'frozen' || !!member.subscriptionExpiresAt);

  const handleGateDone = (message: string) => {
    onActionToast?.(message);
    onClose();
  };

  // Lock background scroll while the sheet is open (same as the other rd-sheets).
  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, []);

  const joinedAt = member.joinedAt
    ? new Date(member.joinedAt).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' })
    : null;
  const initials = `${member.firstName.charAt(0)}${member.lastName?.charAt(0) ?? ''}`;
  const bio = profile?.bio?.trim();
  const hasInterests = (profile?.interests.length ?? 0) > 0;

  return createPortal(
    <>
      <div className="rd-sheet-overlay" onClick={onClose} aria-hidden="true" />
      <div className="rd-sheet" role="dialog" aria-modal="true" aria-label="Профиль участника">
        <div className="rd-sheet-grabber" aria-hidden="true" />
        <div className="rd-sheet-head">
          <h2>Профиль участника</h2>
          <button type="button" className="rd-sheet-close" onClick={onClose}>Закрыть</button>
        </div>

        <div className="rd-sheet-body">
          {/* Avatar + name + (@username · в клубе с DATE) */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span className="rd-avatar" style={{ width: 56, height: 56, borderRadius: '50%', fontSize: 18 }}>
              {member.avatarUrl ? <img src={member.avatarUrl} alt="" /> : initials}
            </span>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 17, fontWeight: 700, color: 'var(--text)' }}>
                {member.firstName} {member.lastName ?? ''}
              </div>
              <div style={{ fontSize: 13, color: 'var(--text-dim)' }}>
                {[profile?.username ? `@${profile.username}` : null, joinedAt ? `в клубе с ${joinedAt}` : null]
                  .filter(Boolean)
                  .join(' · ')}
              </div>
            </div>
          </div>

          {/* About */}
          {bio && (
            <div className="rd-field">
              <span className="rd-label">О себе</span>
              <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{bio}</div>
            </div>
          )}

          {/* Interests */}
          {hasInterests && (
            <div className="rd-field">
              <span className="rd-label">Интересы</span>
              <div className="rd-tags" style={{ margin: 0 }}>
                {profile!.interests.map((interest) => (
                  <span key={interest} className="rd-tag">{interest}</span>
                ))}
              </div>
            </div>
          )}

          {/* Reputation in this club */}
          <div>
            <div className="rd-section-sub-h" style={{ margin: '0 0 8px' }}>Репутация в этом клубе</div>
            {loading ? (
              <div className="rd-spinner-row" style={{ padding: '8px 0' }}><Spinner size="s" /></div>
            ) : profile && profile.trust !== null ? (
              <ReputationRings profile={profile} />
            ) : (
              <div className="rd-glass rd-rep-panel">
                <div className="rd-kv">
                  <span>
                    {profile?.role === 'organizer'
                      ? 'Здесь репутация начисляется за организаторские качества'
                      : 'Новичок — пока недостаточно данных'}
                  </span>
                </div>
              </div>
            )}
          </div>

          {/* Organizer-only access gate (de-Stars Slice 2) + admin edit (S1) */}
          {showGate && (
            <OrganizerGate
              clubId={clubId}
              member={member}
              organizerNote={profile?.organizerNote ?? null}
              onDone={handleGateDone}
            />
          )}
        </div>
      </div>
    </>,
    document.body,
  );
};

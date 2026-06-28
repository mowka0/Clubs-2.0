import { FC, useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import {
  useAwardSuggestionsQuery,
  useFreezeMemberMutation,
  useGrantMemberAwardMutation,
  useMarkMemberDuesPaidMutation,
  useMemberProfileQuery,
  useRejectMemberMutation,
  useRevokeMemberAwardMutation,
  useSetMemberAccessUntilMutation,
  useUpdateMemberNoteMutation,
} from '../../queries/members';
import { useHaptic } from '../../hooks/useHaptic';
import { ApiError } from '../../api/apiClient';
import { pluralRu } from '../../utils/formatters';
import { DonutRing } from '../reputation/DonutRing';
import { TRUST_TIER_COLOR, trustTier } from '../reputation/trust-tier';
import type { AwardDto, MemberListItemDto, MemberProfileDto } from '../../types/api';

// Curated emoji set for the award picker — broad enough to label most kinds of recognition.
const AWARD_EMOJIS = ['🏆', '🥇', '⭐', '🔥', '💎', '👑', '🚀', '❤️', '🌟', '🎯', '💪', '🙌'];
// Mirrors the backend cap (AwardService.MAX_AWARDS_PER_MEMBER) + label length (GrantAwardRequest).
const MAX_AWARDS = 6;
const MAX_AWARD_LABEL = 40;

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

interface AwardEditorProps {
  clubId: string;
  userId: string;
  awards: AwardDto[];
  /** Surface grant/revoke failures on the shared error line of the admin section. */
  onError: (message: string | null) => void;
}

/**
 * Award management «как интересы» (member admin S2): existing award chips with × (revoke) plus a
 * «＋ Добавить награду» control — emoji picker + label input with autocomplete from past club awards.
 * Each grant/revoke is an immediate API call (not batched under the form's «Сохранить»), so the
 * organizer can award several in a row without closing the card. Cosmetic only — no reputation effect.
 */
const AwardEditor: FC<AwardEditorProps> = ({ clubId, userId, awards, onError }) => {
  const haptic = useHaptic();
  const grant = useGrantMemberAwardMutation();
  const revoke = useRevokeMemberAwardMutation();
  const [adding, setAdding] = useState(false);
  const [emoji, setEmoji] = useState(AWARD_EMOJIS[0]);
  const [label, setLabel] = useState('');
  const suggestQuery = useAwardSuggestionsQuery(clubId, { enabled: adding });

  const atMax = awards.length >= MAX_AWARDS;
  const busy = grant.isPending || revoke.isPending;

  const existingLabels = new Set(awards.map((a) => a.label.toLowerCase()));
  const query = label.trim().toLowerCase();
  const suggestions = (suggestQuery.data ?? [])
    .filter((s) => !existingLabels.has(s.label.toLowerCase()))
    .filter((s) => !query || s.label.toLowerCase().includes(query))
    .slice(0, 6);

  const doGrant = (em: string, raw: string) => {
    const cleanLabel = raw.trim();
    if (!cleanLabel || busy) return;
    onError(null);
    haptic.impact('light');
    grant.mutate(
      { clubId, userId, emoji: em, label: cleanLabel },
      {
        onSuccess: () => { haptic.notify('success'); setLabel(''); setAdding(false); },
        onError: (e) => {
          haptic.notify('error');
          onError(e instanceof Error ? e.message : 'Не удалось выдать награду');
        },
      },
    );
  };

  const doRevoke = (awardId: string) => {
    if (busy) return;
    onError(null);
    haptic.impact('light');
    revoke.mutate(
      { clubId, userId, awardId },
      {
        onError: (e) => {
          haptic.notify('error');
          onError(e instanceof Error ? e.message : 'Не удалось снять награду');
        },
      },
    );
  };

  return (
    <div className="rd-field">
      <span className="rd-label">Награды клуба</span>
      <div className="rd-award-chips">
        {awards.map((a) => (
          <span key={a.id} className="rd-award-chip">
            <span className="rd-award-emoji" aria-hidden="true">{a.emoji}</span>
            {a.label}
            <button
              type="button"
              className="x"
              disabled={busy}
              onClick={() => doRevoke(a.id)}
              aria-label={`Снять награду ${a.label}`}
            >
              ×
            </button>
          </span>
        ))}
        {!atMax && !adding && (
          <button type="button" className="rd-award-add" onClick={() => { onError(null); setAdding(true); }}>
            ＋ Добавить награду
          </button>
        )}
      </div>

      {adding && !atMax && (
        <div className="rd-award-form">
          <div className="rd-award-emoji-pick" role="group" aria-label="Эмодзи награды">
            {AWARD_EMOJIS.map((em) => (
              <button
                key={em}
                type="button"
                className={`rd-award-emoji-opt${em === emoji ? ' sel' : ''}`}
                aria-pressed={em === emoji}
                onClick={() => setEmoji(em)}
              >
                {em}
              </button>
            ))}
          </div>
          <div className="rd-award-input-row">
            <input
              className="rd-input"
              value={label}
              maxLength={MAX_AWARD_LABEL}
              placeholder="Название награды"
              aria-label="Название награды"
              onChange={(e) => setLabel(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); doGrant(emoji, label); } }}
            />
            <button
              type="button"
              className="rd-award-submit"
              disabled={!label.trim() || busy}
              onClick={() => doGrant(emoji, label)}
              aria-label="Создать награду"
            >
              {grant.isPending ? <Spinner size="s" /> : '→'}
            </button>
          </div>
          {suggestions.length > 0 && (
            <div className="rd-award-suggest" role="listbox">
              {suggestions.map((s) => (
                <button
                  key={`${s.emoji}-${s.label}`}
                  type="button"
                  className="rd-award-suggest-item"
                  onClick={() => doGrant(s.emoji, s.label)}
                >
                  <span className="rd-award-emoji" aria-hidden="true">{s.emoji}</span>{s.label}
                </button>
              ))}
            </div>
          )}
        </div>
      )}
      {atMax && <div className="rd-award-hint">Максимум {MAX_AWARDS} наград</div>}
    </div>
  );
};

interface OrganizerGateProps {
  clubId: string;
  member: MemberListItemDto;
  /** Current private note from the loaded profile (null until loaded / when empty). */
  organizerNote: string | null;
  /** Awards on the loaded profile (managed inline; public on the card, R3). */
  awards: AwardDto[];
  /** Whether the member has a paid access window — gates the subscription strip + dues/freeze + custom date. */
  isPaidMember: boolean;
  /** Member's dues claim to review (de-Stars), organizer-only. null when no claim pending. */
  claim: { claimedAt: string; method: string | null; proofUrl: string | null } | null;
  /** The member's join-application answer (closed clubs), organizer-only. null = open club / no question. */
  applicationAnswer: string | null;
  onDone: (message: string) => void;
}

/** ISO datetime → yyyy-mm-dd for a `<input type="date">`. */
function toDateInput(iso: string | null): string {
  return iso ? new Date(iso).toISOString().slice(0, 10) : '';
}

/**
 * Organizer admin section for a member (member admin Variant B). Two layers, decoupled:
 *  - Paid-only (isPaidMember): de-Stars access controls — subscription strip + «Взнос получен» /
 *    «Закрыть доступ», and «Своя дата» in the edit form. A free member has no access window, so none show.
 *  - Always (any club): ✎ Редактировать → «Награды клуба» (S2, immediate add/remove) + «Заметка» (S1,
 *    private, saved on «Сохранить»).
 * 409 (lost race) on a dues/freeze action closes the card — the list cache is already refreshed.
 */
const OrganizerGate: FC<OrganizerGateProps> = ({ clubId, member, organizerNote, awards, isPaidMember, claim, applicationAnswer, onDone }) => {
  const haptic = useHaptic();
  const markPaid = useMarkMemberDuesPaidMutation();
  const freeze = useFreezeMemberMutation();
  const reject = useRejectMemberMutation();
  const setAccess = useSetMemberAccessUntilMutation();
  const updateNote = useUpdateMemberNoteMutation();
  const [error, setError] = useState<string | null>(null);

  const [editing, setEditing] = useState(false);
  const [noteDraft, setNoteDraft] = useState('');
  const [dateDraft, setDateDraft] = useState('');
  const [confirmingReject, setConfirmingReject] = useState(false);

  const busy = markPaid.isPending || freeze.isPending || reject.isPending;
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

  const handleReject = () => {
    if (busy) return;
    setError(null);
    haptic.impact('medium');
    reject.mutate(
      { clubId, userId: member.userId },
      {
        onSuccess: () => { haptic.notify('success'); onDone(`Вступление ${member.firstName} отклонено`); },
        onError: (e) => {
          if (e instanceof ApiError && e.status === 409) { onDone('Статус участника изменился'); return; }
          haptic.notify('error');
          setError(e instanceof Error ? e.message : 'Не удалось отклонить');
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
      {/* Join-application answer (closed clubs) — review «why they joined» alongside the payment proof. */}
      {applicationAnswer && (
        <div className="rd-org-note-read" style={{ marginTop: 0, marginBottom: 12 }}>
          <span className="rd-org-note-k">Ответ на заявку</span>{applicationAnswer}
        </div>
      )}

      {isPaidMember && (
        <>
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
          </div>

          {/* Dues claim review (de-Stars): the member declared payment — show method + screenshot so the
              organizer verifies before «Взнос получен». */}
          {claim && (
            <div className="rd-claim">
              <div className="rd-claim-h">
                ⏳ Оплата заявлена · {claim.method === 'cash' ? 'наличные' : 'СБП'}
              </div>
              {claim.proofUrl ? (
                <a href={claim.proofUrl} target="_blank" rel="noopener noreferrer" className="rd-claim-proof">
                  <img src={claim.proofUrl} alt="Скриншот оплаты" />
                </a>
              ) : (
                <div className="rd-claim-note">Скриншота нет (наличные) — подтвердите после получения.</div>
              )}
            </div>
          )}

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
            {/* B+C: reject a paid join (refund offline). Frozen-only; two-tap confirm — it removes the member. */}
            {frozen && !confirmingReject && (
              <button type="button" className="rd-btn-outline" style={{ color: 'var(--danger)' }} disabled={busy} onClick={() => { setError(null); setConfirmingReject(true); }}>
                Отказать · вернуть перевод
              </button>
            )}
            {frozen && confirmingReject && (
              <div className="rd-reject-confirm">
                <div className="rd-reject-q">Убрать {member.firstName} из клуба? Перевод вернёте сами — платформа деньги не держит.</div>
                <div className="rd-org-gate-acts">
                  <button type="button" className="rd-btn-outline" style={{ color: 'var(--danger)' }} disabled={busy} onClick={handleReject}>
                    {reject.isPending ? <Spinner size="s" /> : 'Отказать и вернуть'}
                  </button>
                  <button type="button" className="rd-btn-outline" disabled={busy} onClick={() => setConfirmingReject(false)}>
                    Отмена
                  </button>
                </div>
              </div>
            )}
          </div>
        </>
      )}

      {error && <div className="rd-error" style={{ textAlign: 'left' }}>{error}</div>}

      {/* Member admin (Variant B): ✎ reveals editable fields — awards + note (+ custom date for paid). */}
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
          {isPaidMember && (
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
          )}
          <AwardEditor clubId={clubId} userId={member.userId} awards={awards} onError={setError} />
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

  // Admin section: the organizer can manage any non-organizer member (note + awards work in free
  // clubs too, S2). Never the organizer's own row — the backend rejects managing the organizer.
  const isManageable = isOrganizer && member.role !== 'organizer';
  // Paid member = has an access window (or is frozen pending dues). Gates the de-Stars layer
  // (subscription strip + dues/freeze + custom date); a free member only gets note + awards.
  const isPaidMember = member.accessStatus === 'frozen' || !!member.subscriptionExpiresAt;

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
  const awards = profile?.awards ?? [];

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

          {/* Club awards (S2) — public recognition, shown to every viewer (R3). Read-only here; the
              organizer manages them in the ✎ edit form below. */}
          {awards.length > 0 && (
            <div className="rd-field">
              <span className="rd-label">Награды клуба</span>
              <div className="rd-award-chips" style={{ margin: 0 }}>
                {awards.map((a) => (
                  <span key={a.id} className="rd-award-chip rd-award-chip-ro">
                    <span className="rd-award-emoji" aria-hidden="true">{a.emoji}</span>{a.label}
                  </span>
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

          {/* Organizer admin section: de-Stars access gate (paid-only) + admin edit (note S1 / awards S2). */}
          {isManageable && (
            <OrganizerGate
              clubId={clubId}
              member={member}
              organizerNote={profile?.organizerNote ?? null}
              awards={awards}
              isPaidMember={isPaidMember}
              claim={profile?.duesClaimedAt
                ? { claimedAt: profile.duesClaimedAt, method: profile.duesClaimMethod, proofUrl: profile.duesProofUrl }
                : null}
              applicationAnswer={profile?.applicationAnswer ?? null}
              onDone={handleGateDone}
            />
          )}
        </div>
      </div>
    </>,
    document.body,
  );
};

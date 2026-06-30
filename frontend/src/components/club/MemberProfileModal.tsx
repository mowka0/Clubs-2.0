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
  useRemoveMemberMutation,
  useRevokeMemberAwardMutation,
  useSetMemberAccessUntilMutation,
  useUpdateMemberNoteMutation,
} from '../../queries/members';
import { useHaptic } from '../../hooks/useHaptic';
import { ApiError } from '../../api/apiClient';
import { pluralRu } from '../../utils/formatters';
import { DonutRing } from '../reputation/DonutRing';
import { ImageLightbox } from '../ImageLightbox';
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
}

/**
 * Award management «как интересы» (member admin S2): existing award chips with × (revoke) plus a
 * «＋ Добавить награду» control. The add form leads with the club's existing awards (tap to reuse,
 * keeping the same emoji + label — R2), then a «создать свою» path with an emoji picker + label input.
 * Each grant/revoke is an immediate API call (not batched under the form's «Сохранить»), so the
 * organizer can award several in a row without closing the card. Cosmetic only — no reputation effect.
 */
const AwardEditor: FC<AwardEditorProps> = ({ clubId, userId, awards }) => {
  const haptic = useHaptic();
  const grant = useGrantMemberAwardMutation();
  const revoke = useRevokeMemberAwardMutation();
  const [adding, setAdding] = useState(false);
  const [emoji, setEmoji] = useState(AWARD_EMOJIS[0]);
  const [label, setLabel] = useState('');
  const [error, setError] = useState<string | null>(null);
  const suggestQuery = useAwardSuggestionsQuery(clubId, { enabled: adding });

  const atMax = awards.length >= MAX_AWARDS;
  const busy = grant.isPending || revoke.isPending;

  // Reuse pool: past awards of this club minus the ones already on this member, prefix/substring-filtered
  // by what's typed — so the organizer reuses «Активист» instead of re-creating it (R2, «как интересы»).
  const existingLabels = new Set(awards.map((a) => a.label.toLowerCase()));
  const query = label.trim().toLowerCase();
  const suggestions = (suggestQuery.data ?? [])
    .filter((s) => !existingLabels.has(s.label.toLowerCase()))
    .filter((s) => !query || s.label.toLowerCase().includes(query))
    .slice(0, 8);

  const doGrant = (em: string, raw: string) => {
    const cleanLabel = raw.trim();
    if (!cleanLabel || busy) return;
    setError(null);
    haptic.impact('light');
    grant.mutate(
      { clubId, userId, emoji: em, label: cleanLabel },
      {
        onSuccess: () => { haptic.notify('success'); setLabel(''); setAdding(false); },
        onError: (e) => {
          haptic.notify('error');
          setError(e instanceof Error ? e.message : 'Не удалось выдать награду');
        },
      },
    );
  };

  const doRevoke = (awardId: string) => {
    if (busy) return;
    setError(null);
    haptic.impact('light');
    revoke.mutate(
      { clubId, userId, awardId },
      {
        onError: (e) => {
          haptic.notify('error');
          setError(e instanceof Error ? e.message : 'Не удалось снять награду');
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
          <button type="button" className="rd-award-add" onClick={() => { setError(null); setAdding(true); }}>
            ＋ Добавить награду
          </button>
        )}
      </div>

      {adding && !atMax && (
        <div className="rd-award-form">
          {/* Reuse an existing club award first («как интересы») — tap grants it with its own emoji. */}
          {suggestions.length > 0 && (
            <>
              <div className="rd-award-suggest-h">Уже в клубе — нажмите, чтобы выдать</div>
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
            </>
          )}
          <div className="rd-award-new-h">{suggestions.length > 0 ? 'Или создайте свою' : 'Создать награду'}</div>
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
        </div>
      )}
      {atMax && <div className="rd-award-hint">Максимум {MAX_AWARDS} наград</div>}
      {error && <div className="rd-error" style={{ textAlign: 'left' }}>{error}</div>}
    </div>
  );
};

interface OrganizerGateProps {
  clubId: string;
  member: MemberListItemDto;
  /** Current private note from the loaded profile (null until loaded / when empty). */
  organizerNote: string | null;
  /** Whether the member has a paid access window — gates the subscription strip + dues/freeze + custom date. */
  isPaidMember: boolean;
  /** Member's dues claim to review (de-Stars), organizer-only. null when no claim pending. */
  claim: { claimedAt: string; method: string | null; proofUrl: string | null } | null;
  /** The member's join-application answer (closed clubs), organizer-only. null = open club / no question. */
  applicationAnswer: string | null;
  /** Edit mode is owned by the modal (toggled by the header ✎): reveals the awards editor (above, under
   *  interests) and the «Своя дата» form (paid). The note is always-open and NOT gated by this. */
  editing: boolean;
  onEditingChange: (editing: boolean) => void;
  onDone: (message: string) => void;
}

/** ISO datetime → yyyy-mm-dd for a `<input type="date">`. */
function toDateInput(iso: string | null): string {
  return iso ? new Date(iso).toISOString().slice(0, 10) : '';
}

/**
 * Organizer admin section for a member (member admin Variant B). Three layers, decoupled:
 *  - Paid-only (isPaidMember): de-Stars access controls — subscription strip + «Взнос получен» /
 *    «Закрыть доступ», and «Своя дата» behind the ✎. A free member has no access window, so none show.
 *  - Always (any club): the private «Заметка» (S1) is an always-open field with its own «Сохранить» —
 *    so the panel has a home for it even in a free club (where it would otherwise be just «Удалить из
 *    клуба»). Saving the note does NOT close the card.
 *  - Awards (S2) live above, under interests, revealed by the same header ✎.
 * Edit mode (`editing`) is owned by the modal: the ✎ toggles the awards editor + the «Своя дата» form.
 * 409 (lost race) on a dues/freeze action closes the card — the list cache is already refreshed.
 */
const OrganizerGate: FC<OrganizerGateProps> = ({ clubId, member, organizerNote, isPaidMember, claim, applicationAnswer, editing, onEditingChange, onDone }) => {
  const haptic = useHaptic();
  const markPaid = useMarkMemberDuesPaidMutation();
  const freeze = useFreezeMemberMutation();
  const reject = useRejectMemberMutation();
  const remove = useRemoveMemberMutation();
  const setAccess = useSetMemberAccessUntilMutation();
  const updateNote = useUpdateMemberNoteMutation();
  const [error, setError] = useState<string | null>(null);

  const [noteDraft, setNoteDraft] = useState('');
  const [dateDraft, setDateDraft] = useState('');
  const [confirmingReject, setConfirmingReject] = useState(false);
  const [confirmingKick, setConfirmingKick] = useState(false);
  const [kickReason, setKickReason] = useState('');
  const [zoomedProof, setZoomedProof] = useState<string | null>(null);

  const busy = markPaid.isPending || freeze.isPending || reject.isPending || remove.isPending;
  const KICK_REASON_MIN = 5;
  const savingDate = setAccess.isPending;
  const savingNote = updateNote.isPending;
  const frozen = member.accessStatus === 'frozen';
  const expiresAt = member.subscriptionExpiresAt ?? null;
  const soon = !frozen && !!expiresAt && daysUntil(expiresAt) <= 7;
  const today = new Date().toISOString().slice(0, 10);
  const originalDate = toDateInput(expiresAt);
  const noteDirty = noteDraft.trim() !== (organizerNote ?? '');

  // Note is an always-open field — keep its draft synced to the saved value (incl. after a save refetch,
  // which makes the «Сохранить» button disappear once persisted).
  useEffect(() => { setNoteDraft(organizerNote ?? ''); }, [organizerNote]);
  // «Своя дата» form opens with the header ✎; seed it from the current window each time it opens.
  useEffect(() => { if (editing) { setError(null); setDateDraft(originalDate); } }, [editing, originalDate]);

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

  const handleKick = () => {
    const reason = kickReason.trim();
    if (busy || reason.length < KICK_REASON_MIN) return;
    setError(null);
    haptic.impact('medium');
    remove.mutate(
      { clubId, userId: member.userId, reason },
      {
        onSuccess: () => { haptic.notify('success'); onDone(`${member.firstName} удалён(а) из клуба`); },
        onError: (e) => {
          if (e instanceof ApiError && e.status === 409) { onDone('Статус участника изменился'); return; }
          haptic.notify('error');
          setError(e instanceof Error ? e.message : 'Не удалось удалить');
        },
      },
    );
  };

  // Save the private note in place — does NOT close the card, so the organizer can keep awarding/managing.
  // The profile refetch (mutation invalidates it) re-seeds noteDraft and hides «Сохранить».
  const handleSaveNote = () => {
    if (!noteDirty || savingNote) return;
    setError(null);
    haptic.impact('medium');
    updateNote.mutate(
      { clubId, userId: member.userId, note: noteDraft.trim() || null },
      {
        onSuccess: () => haptic.notify('success'),
        onError: (e) => { haptic.notify('error'); setError(e instanceof Error ? e.message : 'Не удалось сохранить заметку'); },
      },
    );
  };

  // «Своя дата» (paid only, behind the ✎) — closes the card on success like the other gate actions.
  const handleSaveDate = async () => {
    if (!dateDraft || dateDraft === originalDate) { onEditingChange(false); return; }
    setError(null);
    // End-of-day local so «до 28 июля» grants access through the 28th.
    const untilIso = new Date(`${dateDraft}T23:59:59`).toISOString();
    try {
      haptic.impact('medium');
      await setAccess.mutateAsync({ clubId, userId: member.userId, until: untilIso });
      haptic.notify('success');
      onDone('Изменения сохранены');
    } catch (e) {
      haptic.notify('error');
      setError(e instanceof Error ? e.message : 'Не удалось сохранить');
    }
  };

  return (
    <div className="rd-org-gate">
      {/* Join-application answer (closed clubs) — review «why they joined» alongside the payment proof. */}
      {applicationAnswer && (
        <div className="rd-org-note-read" style={{ marginTop: 0, marginBottom: 12 }}>
          <span className="rd-org-note-k">Ответ на заявку</span>{applicationAnswer}
        </div>
      )}

      {/* «Управление участником» panel (R1): subscription summary + paired actions, private note, and the
          destructive «Удалить из клуба» in the footer — distinct from «Закрыть доступ» (a reversible pause). */}
      <div className="rd-mgmt">
        <div className="rd-mgmt-h">⚙ Управление участником</div>

        {/* FROZEN paid member: review the dues claim, then open access or reject+refund the paid join. */}
        {isPaidMember && frozen && (
          <div className="rd-mgmt-body">
            {/* Status as plain text (no boxed «rd-claim» card) — the screenshot looked heavy. */}
            <div className="rd-mgmt-claim-line">
              {claim
                ? `⏳ Оплата заявлена · ${claim.method === 'cash' ? 'наличные' : 'СБП'}`
                : '🔒 Доступ закрыт · участник ещё не оплатил'}
            </div>
            {claim?.proofUrl ? (
              <button type="button" className="rd-claim-thumb" onClick={() => setZoomedProof(claim.proofUrl)}>
                <img src={claim.proofUrl} alt="Скриншот оплаты" />
                <span className="rd-claim-thumb-hint">нажмите, чтобы увеличить</span>
              </button>
            ) : claim ? (
              <div className="rd-claim-note">Наличные — скриншота нет, подтвердите после получения.</div>
            ) : null}
            {!confirmingReject ? (
              <div className="rd-mgmt-pair">
                <button type="button" className="rd-mgmt-pb primary" disabled={busy} onClick={() => run(markPaid, `Доступ ${member.firstName} открыт`)}>
                  {markPaid.isPending ? <Spinner size="s" /> : 'Взнос получен · открыть доступ'}
                </button>
                <button type="button" className="rd-mgmt-pb danger" disabled={busy} onClick={() => { setError(null); setConfirmingReject(true); }}>
                  Отказать · вернуть перевод
                </button>
              </div>
            ) : (
              <div className="rd-reject-confirm">
                <div className="rd-reject-q">Убрать {member.firstName} из клуба? Перевод вернёте сами — платформа деньги не держит.</div>
                <div className="rd-org-gate-acts">
                  <button type="button" className="rd-btn-outline" disabled={busy} onClick={() => setConfirmingReject(false)}>Отмена</button>
                  <button type="button" className="rd-btn-primary rd-btn-danger" disabled={busy} onClick={handleReject}>
                    {reject.isPending ? <Spinner size="s" /> : 'Отказать и вернуть'}
                  </button>
                </div>
              </div>
            )}
          </div>
        )}

        {/* ACTIVE paid member (R1): status summary + paired «Взнос получен» / «Закрыть доступ». */}
        {isPaidMember && !frozen && (
          <div className="rd-mgmt-body">
            <div className={`rd-mgmt-sum${soon ? ' rd-soon' : ''}`}>
              <span className="rd-mgmt-dot" aria-hidden="true" />
              <div>
                <div className="rd-mgmt-sum-v">Подписка активна</div>
                <div className="rd-mgmt-sum-k">{expiresAt ? `до ${formatDateFull(expiresAt)} · ${relativeUntil(expiresAt)}` : '—'}</div>
              </div>
            </div>
            <div className="rd-mgmt-pair">
              <button type="button" className="rd-mgmt-pb primary" disabled={busy} onClick={() => run(markPaid, `Доступ ${member.firstName} продлён на 30 дней`)}>
                {markPaid.isPending ? <Spinner size="s" /> : <>Взнос получен<small>+30 дн · до {extendedEndLabel(expiresAt)}</small></>}
              </button>
              <button type="button" className="rd-mgmt-pb" disabled={busy} onClick={() => run(freeze, `Доступ ${member.firstName} закрыт`)}>
                {freeze.isPending ? <Spinner size="s" /> : 'Закрыть доступ'}
              </button>
            </div>
          </div>
        )}

        {error && <div className="rd-error" style={{ textAlign: 'left', padding: '0 14px 4px' }}>{error}</div>}

        {/* Private note (S1) — an always-open field so the panel has a home for it even in a free club
            (where it would otherwise be just «Удалить из клуба»). Saved on its own «Сохранить», which
            appears only when the text changed; saving does NOT close the card. */}
        <div className="rd-mgmt-body rd-org-edit">
          <label className="rd-field">
            <span className="rd-label">Заметка (видите только вы)</span>
            <textarea className="rd-textarea" rows={3} maxLength={500} placeholder="Например: помогает с площадкой для встреч" value={noteDraft} onChange={(e) => setNoteDraft(e.target.value)} />
          </label>
          {noteDirty && (
            <div className="rd-org-gate-acts">
              <button type="button" className="rd-btn-primary" disabled={savingNote} onClick={handleSaveNote}>{savingNote ? <Spinner size="s" /> : 'Сохранить заметку'}</button>
            </div>
          )}
        </div>

        {/* «Своя дата окончания доступа» (paid only) — revealed by the header ✎. */}
        {editing && isPaidMember && (
          <div className="rd-mgmt-body rd-org-edit">
            <label className="rd-field">
              <span className="rd-label">Своя дата окончания доступа</span>
              <input type="date" className="rd-input" value={dateDraft} min={today} onChange={(e) => setDateDraft(e.target.value)} />
            </label>
            <div className="rd-org-gate-acts">
              <button type="button" className="rd-btn-primary" disabled={savingDate} onClick={handleSaveDate}>{savingDate ? <Spinner size="s" /> : 'Сохранить'}</button>
              <button type="button" className="rd-btn-outline" disabled={savingDate} onClick={() => onEditingChange(false)}>Отмена</button>
            </div>
          </div>
        )}

        {/* «Удалить из клуба» — active/free members only (frozen paid joins use «Отказать·вернуть» above). */}
        {!frozen && (
          !confirmingKick ? (
            <div className="rd-mgmt-killzone">
              <button type="button" className="rd-mgmt-kill" disabled={busy} onClick={() => { setError(null); setKickReason(''); setConfirmingKick(true); }}>
                Удалить из клуба
              </button>
            </div>
          ) : (
            <div className="rd-mgmt-killconfirm">
              <div className="rd-reject-q">Удалить {member.firstName} из клуба? Доступ закроется сразу. Если оплатил — возврат на ваше усмотрение (деньги вне платформы).</div>
              <textarea
                className="rd-textarea"
                rows={2}
                maxLength={500}
                placeholder={`Причина (увидит участник) — минимум ${KICK_REASON_MIN} символов`}
                value={kickReason}
                onChange={(e) => setKickReason(e.target.value)}
              />
              <div className="rd-org-gate-acts">
                <button type="button" className="rd-btn-outline" disabled={busy} onClick={() => setConfirmingKick(false)}>Отмена</button>
                <button type="button" className="rd-btn-primary rd-btn-danger" disabled={busy || kickReason.trim().length < KICK_REASON_MIN} onClick={handleKick}>
                  {remove.isPending ? <Spinner size="s" /> : 'Удалить из клуба'}
                </button>
              </div>
            </div>
          )
        )}
      </div>

      <ImageLightbox src={zoomedProof} alt="Скриншот оплаты" onClose={() => setZoomedProof(null)} />
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

  // Edit mode lives here (not in OrganizerGate) so the header ✎ toggles the awards editor (under
  // interests) and the «Своя дата» form together. The note is always-open (not gated by ✎). Only
  // the organizer ever edits.
  const [editing, setEditing] = useState(false);

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
          <div className="rd-sheet-head-acts">
            {isManageable && (
              <button
                type="button"
                className={`rd-sheet-edit${editing ? ' on' : ''}`}
                aria-label={editing ? 'Закончить редактирование' : 'Редактировать'}
                aria-pressed={editing}
                onClick={() => setEditing((e) => !e)}
              >
                ✎
              </button>
            )}
            <button type="button" className="rd-sheet-close" onClick={onClose}>Закрыть</button>
          </div>
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
              {profile?.username && (
                <div style={{ fontSize: 13, color: 'var(--text-dim)' }}>@{profile.username}</div>
              )}
              {joinedAt && (
                <div style={{ fontSize: 13, color: 'var(--text-dim)' }}>в клубе с {joinedAt}</div>
              )}
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

          {/* Club awards (S2) — ONE place: the editor when the organizer is in edit mode, otherwise the
              public read-only chips (R3, shown to every viewer). No duplicate «Награды клуба» section. */}
          {isManageable && editing ? (
            <AwardEditor clubId={clubId} userId={member.userId} awards={awards} />
          ) : awards.length > 0 ? (
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
          ) : null}

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

          {/* Organizer admin section: de-Stars access gate (paid-only) + always-open private note (S1) +
              «Своя дата» behind the ✎. Awards (S2) are handled above, under interests. */}
          {isManageable && (
            <OrganizerGate
              clubId={clubId}
              member={member}
              organizerNote={profile?.organizerNote ?? null}
              isPaidMember={isPaidMember}
              claim={profile?.duesClaimedAt
                ? { claimedAt: profile.duesClaimedAt, method: profile.duesClaimMethod, proofUrl: profile.duesProofUrl }
                : null}
              applicationAnswer={profile?.applicationAnswer ?? null}
              editing={editing}
              onEditingChange={setEditing}
              onDone={handleGateDone}
            />
          )}
        </div>
      </div>
    </>,
    document.body,
  );
};

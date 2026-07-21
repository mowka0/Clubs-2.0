import { FC, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import {
  useAwardSuggestionsQuery,
  useGrantMemberAwardMutation,
  useMarkMemberDuesPaidMutation,
  useMemberProfileQuery,
  useRejectMemberMutation,
  useRemoveMemberMutation,
  useRevokeMemberAwardMutation,
  useSetMemberAccessUntilMutation,
  useUpdateMemberNoteMutation,
  useUpdateMemberRoleMutation,
} from '../../queries/members';
import { useHaptic } from '../../hooks/useHaptic';
import { useAuthStore } from '../../store/useAuthStore';
import { ApiError } from '../../api/apiClient';
import { pluralRu } from '../../utils/formatters';
import {
  ASSIGNABLE_ROLES,
  ROLE_DESCRIPTIONS,
  ROLE_LABELS,
  membershipRoleLabel,
} from '../../utils/membershipRole';
import type { AssignableMemberRole } from '../../api/membership';
import { DonutRing } from '../reputation/DonutRing';
import { ImageLightbox } from '../ImageLightbox';
import { TRUST_TIER_COLOR, trustTier } from '../reputation/trust-tier';
import type { AwardDto, MemberListItemDto, MemberProfileDto } from '../../types/api';

// Подобранный набор эмодзи для пикера наград — достаточно широкий для большинства видов признания.
const AWARD_EMOJIS = ['🏆', '🥇', '⭐', '🔥', '💎', '👑', '🚀', '❤️', '🌟', '🎯', '💪', '🙌'];
// Зеркалит бэкенд: лимит наград (AwardService.MAX_AWARDS_PER_MEMBER) + длину названия (GrantAwardRequest).
const MAX_AWARDS = 6;
// 16 — лимит титула Telegram: награда целиком помещается в титул рядом с именем в чате (слайс 4).
const MAX_AWARD_LABEL = 16;

interface MemberProfileModalProps {
  member: MemberListItemDto;
  clubId: string;
  /** Менеджерский вид (владелец ИЛИ активный со-организатор) — открывает строку
   *  «Подписка активна до …» + dues-действия (de-Stars). */
  isOrganizer?: boolean;
  /** Вызывающий — владелец клуба (co-organizers): открывает секцию смены роли и управление
   *  со-организаторами. Со-орг — менеджер (isOrganizer), но управляет только role=member. */
  isOwner?: boolean;
  onClose: () => void;
  /** Показать toast на уровне страницы после успешного gate-действия. */
  onActionToast?: (message: string) => void;
}

// Миллисекунд в сутках.
const MS_PER_DAY = 86_400_000;
// Зеркалит honor-system-период бэкенда (membership.access-period-days) для превью «продлить до …».
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

/** Дата окончания после «Взнос получен» — бэкенд продлевает на +30 дн от max(сейчас, текущее окончание). */
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
 * Управление наградами «как интересы» (member admin S2): чипы существующих наград с × (снять) плюс
 * кнопка «＋ Добавить награду». Форма добавления сначала предлагает уже существующие награды клуба
 * (тап переиспользует их с тем же эмодзи и названием — R2), затем путь «создать свою» с пикером эмодзи
 * и полем названия. Каждая выдача/снятие — немедленный API-вызов (не батчится под «Сохранить» формы),
 * чтобы организатор мог выдать несколько подряд, не закрывая карточку. Чистая косметика — на репутацию
 * не влияет.
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

  // Пул переиспользования: прошлые награды клуба минус уже висящие на этом участнике, с фильтром по
  // введённой подстроке — организатор переиспользует «Активист», а не создаёт заново (R2, «как интересы»).
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
          {/* Сначала переиспользуем существующую награду клуба («как интересы») — тап выдаёт её с её эмодзи. */}
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
  /** Текущая приватная заметка из загруженного профиля (null, пока не загружено / когда пусто). */
  organizerNote: string | null;
  /** Есть ли у участника платное окно доступа — гейтит строку подписки + dues-действия + свою дату. */
  isPaidMember: boolean;
  /** Claim об оплате взноса на проверку (de-Stars), только организатору. null = ожидающего claim нет. */
  claim: { claimedAt: string; method: string | null; proofUrl: string | null } | null;
  /** Ответ на заявку о вступлении (закрытые клубы), только организатору. null = открытый клуб / без вопроса. */
  applicationAnswer: string | null;
  /** Режимом редактирования владеет модалка (переключается ✎ в шапке): открывает редактор наград (выше,
   *  под интересами) и форму «Своя дата» (платные). Заметка открыта всегда и этим флагом НЕ гейтится. */
  editing: boolean;
  onEditingChange: (editing: boolean) => void;
  onDone: (message: string) => void;
}

/** ISO-дата-время → yyyy-mm-dd для `<input type="date">`. */
function toDateInput(iso: string | null): string {
  return iso ? new Date(iso).toISOString().slice(0, 10) : '';
}

/**
 * Админ-секция организатора для участника (member admin Variant B). Три независимых слоя:
 *  - Только платные (isPaidMember): de-Stars-управление доступом — строка подписки + «Взнос получен»,
 *    а также «Своя дата» за ✎. У бесплатного участника нет окна доступа — ничего из этого не
 *    показывается. Ручной кнопки «Закрыть доступ» нет (PO 2026-07-06): просрочку окна ежедневно
 *    закрывает шедулер (processExpiry), ручная пауза дублировала автоматику.
 *  - Всегда (любой клуб): приватная «Заметка» (S1) — всегда открытое поле со своим «Сохранить», чтобы
 *    у панели был смысл и в бесплатном клубе (иначе там остался бы лишь «Удалить из клуба»).
 *    Сохранение заметки НЕ закрывает карточку.
 *  - Награды (S2) живут выше, под интересами, открываются тем же ✎ в шапке.
 * Режимом редактирования (`editing`) владеет модалка: ✎ переключает редактор наград + форму «Своя дата».
 * 409 (проигранная гонка) на dues-действии закрывает карточку — кэш списка уже обновлён.
 */
const OrganizerGate: FC<OrganizerGateProps> = ({ clubId, member, organizerNote, isPaidMember, claim, applicationAnswer, editing, onEditingChange, onDone }) => {
  const haptic = useHaptic();
  const markPaid = useMarkMemberDuesPaidMutation();
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

  const busy = markPaid.isPending || reject.isPending || remove.isPending;
  // Минимум символов в причине удаления из клуба — причину увидит участник.
  const KICK_REASON_MIN = 5;
  const savingDate = setAccess.isPending;
  const savingNote = updateNote.isPending;
  const frozen = member.accessStatus === 'frozen';
  // Просрочил продление: был доступ, окно истекло (шедулер active → expired). Путь назад — оплата
  // взноса, поэтому claim-линия и «Взнос получен» как у frozen, но без «Отказать · вернуть»
  // (это действие только про ПЕРВОЕ вступление; должника удаляют киком в футере).
  const expired = member.accessStatus === 'expired';
  const expiresAt = member.subscriptionExpiresAt ?? null;
  const soon = !frozen && !expired && !!expiresAt && daysUntil(expiresAt) <= 7;
  const today = new Date().toISOString().slice(0, 10);
  const originalDate = toDateInput(expiresAt);
  const noteDirty = noteDraft.trim() !== (organizerNote ?? '');

  // Заметка — всегда открытое поле: держим черновик в синхроне с сохранённым значением (в т.ч. после
  // refetch по сохранении, из-за которого кнопка «Сохранить» исчезает, как только текст записан).
  useEffect(() => { setNoteDraft(organizerNote ?? ''); }, [organizerNote]);
  // Форма «Своя дата» открывается ✎ в шапке; при каждом открытии заполняем её текущим окном доступа.
  useEffect(() => { if (editing) { setError(null); setDateDraft(originalDate); } }, [editing, originalDate]);

  const run = (
    mutation: ReturnType<typeof useMarkMemberDuesPaidMutation>,
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

  // Сохраняем приватную заметку на месте — карточка НЕ закрывается, организатор продолжает награждать
  // и управлять. Refetch профиля (мутация его инвалидирует) пересеет noteDraft и скроет «Сохранить».
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

  // «Своя дата» (только платные, за ✎) — при успехе закрывает карточку, как остальные gate-действия.
  const handleSaveDate = async () => {
    if (!dateDraft || dateDraft === originalDate) { onEditingChange(false); return; }
    setError(null);
    // Конец дня в локальном времени: «до 28 июля» даёт доступ включительно по 28-е.
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
      {/* Ответ на заявку (закрытые клубы) — «зачем вступает» смотрится рядом с подтверждением оплаты. */}
      {applicationAnswer && (
        <div className="rd-org-note-read" style={{ marginTop: 0, marginBottom: 12 }}>
          <span className="rd-org-note-k">Ответ на заявку</span>{applicationAnswer}
        </div>
      )}

      {/* Панель «Управление участником» (R1): сводка подписки + «Взнос получен», приватная заметка и
          деструктивное «Удалить из клуба» в футере. */}
      <div className="rd-mgmt">
        <div className="rd-mgmt-h">⚙ Управление участником</div>

        {/* FROZEN платный участник: проверяем claim об оплате, затем открываем доступ или отказ+возврат. */}
        {isPaidMember && frozen && (
          <div className="rd-mgmt-body">
            {/* Статус обычным текстом (без рамочной карточки «rd-claim») — на скриншоте выглядело тяжело.
                Эмодзи отдельным флекс-элементом: перенесённый текст выравнивается по левому краю (PO 2026-07-07). */}
            <div className="rd-mgmt-claim-line">
              <span aria-hidden="true">{claim ? '⏳' : '🔒'}</span>
              <span>
                {claim
                  ? `Оплата заявлена · ${claim.method === 'cash' ? 'наличные' : 'СБП'}`
                  : 'Доступ закрыт · участник ещё не оплатил'}
              </span>
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

        {/* EXPIRED платный участник: подписка истекла (шедулер закрыл доступ). Сводка честная —
            «Доступ истёк», claim-линия как у frozen (должник тоже заявляет оплату), «Взнос получен»
            открывает доступ на +30 дн. «Отказать · вернуть» здесь нет — это про первое вступление. */}
        {isPaidMember && expired && (
          <div className="rd-mgmt-body">
            <div className="rd-mgmt-sum rd-soon">
              <span className="rd-mgmt-dot" aria-hidden="true" />
              <div>
                <div className="rd-mgmt-sum-v">Доступ истёк</div>
                <div className="rd-mgmt-sum-k">{expiresAt ? `подписка закончилась ${formatDateFull(expiresAt)}` : '—'}</div>
              </div>
            </div>
            <div className="rd-mgmt-claim-line">
              <span aria-hidden="true">{claim ? '⏳' : '🔒'}</span>
              <span>
                {claim
                  ? `Оплата заявлена · ${claim.method === 'cash' ? 'наличные' : 'СБП'}`
                  : 'Доступ закрыт · участник ещё не оплатил продление'}
              </span>
            </div>
            {claim?.proofUrl ? (
              <button type="button" className="rd-claim-thumb" onClick={() => setZoomedProof(claim.proofUrl)}>
                <img src={claim.proofUrl} alt="Скриншот оплаты" />
                <span className="rd-claim-thumb-hint">нажмите, чтобы увеличить</span>
              </button>
            ) : claim ? (
              <div className="rd-claim-note">Наличные — скриншота нет, подтвердите после получения.</div>
            ) : null}
            <div className="rd-mgmt-pair">
              <button type="button" className="rd-mgmt-pb primary" disabled={busy} onClick={() => run(markPaid, `Доступ ${member.firstName} открыт`)}>
                {markPaid.isPending ? <Spinner size="s" /> : <>Взнос получен · открыть доступ<small>+30 дн · до {extendedEndLabel(expiresAt)}</small></>}
              </button>
            </div>
          </div>
        )}

        {/* ACTIVE платный участник (R1): сводка статуса + «Взнос получен». Ручной кнопки «Закрыть
            доступ» больше нет (решение PO 2026-07-06): просроченное окно ежедневно закрывает шедулер
            (processExpiry, active → expired), ручная пауза дублировала автоматику и путала организатора.
            Радикальный рычаг остался один — «Удалить из клуба» в футере. */}
        {isPaidMember && !frozen && !expired && (
          <div className="rd-mgmt-body">
            <div className={`rd-mgmt-sum${soon ? ' rd-soon' : ''}`}>
              <span className="rd-mgmt-dot" aria-hidden="true" />
              <div>
                <div className="rd-mgmt-sum-v">Подписка активна</div>
                <div className="rd-mgmt-sum-k">{expiresAt ? `до ${formatDateFull(expiresAt)} · ${relativeUntil(expiresAt)}` : '—'}</div>
              </div>
            </div>
            {/* Раннее продление (§7): участник заявил оплату СЛЕДУЮЩЕГО периода до истечения —
                показываем claim + скриншот, чтобы организатор проверил перевод до «Взнос получен». */}
            {claim && (
              <>
                <div className="rd-mgmt-claim-line">
                  <span aria-hidden="true">⏳</span>
                  <span>{`Оплата заявлена (продление) · ${claim.method === 'cash' ? 'наличные' : 'СБП'}`}</span>
                </div>
                {claim.proofUrl ? (
                  <button type="button" className="rd-claim-thumb" onClick={() => setZoomedProof(claim.proofUrl)}>
                    <img src={claim.proofUrl} alt="Скриншот оплаты" />
                    <span className="rd-claim-thumb-hint">нажмите, чтобы увеличить</span>
                  </button>
                ) : (
                  <div className="rd-claim-note">Наличные — скриншота нет, подтвердите после получения.</div>
                )}
              </>
            )}
            <div className="rd-mgmt-pair">
              <button type="button" className="rd-mgmt-pb primary" disabled={busy} onClick={() => run(markPaid, `Доступ ${member.firstName} продлён на 30 дней`)}>
                {markPaid.isPending ? <Spinner size="s" /> : <>Взнос получен<small>+30 дн · до {extendedEndLabel(expiresAt)}</small></>}
              </button>
            </div>
          </div>
        )}

        {error && <div className="rd-error" style={{ textAlign: 'left', padding: '0 14px 4px' }}>{error}</div>}

        {/* Приватная заметка (S1) — всегда открытое поле, чтобы у панели был смысл и в бесплатном клубе
            (иначе там остался бы лишь «Удалить из клуба»). Сохраняется своей кнопкой «Сохранить»,
            которая появляется только при изменении текста; сохранение НЕ закрывает карточку. */}
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

        {/* «Своя дата окончания доступа» (только платные) — открывается ✎ в шапке. */}
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

        {/* «Удалить из клуба» — только active/бесплатные (frozen платным вступлениям — «Отказать·вернуть» выше). */}
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

interface RoleGateProps {
  clubId: string;
  member: MemberListItemDto;
  /** Toast на уровне страницы + закрытие карточки после успешной смены роли. */
  onDone: (message: string) => void;
}

/**
 * Секция «Роль в клубе» (club-roles) — видна ТОЛЬКО владельцу и только по чужой не-владельческой
 * строке (гейт в модалке). Вместо одиночной кнопки — СЕЛЕКТОР назначаемых ролей (ASSIGNABLE_ROLES:
 * Участник · Со-организатор; будущая роль = ещё одна строка). У каждого пункта — описание
 * (ROLE_DESCRIPTIONS). Текущая роль подсвечена бейджем «Сейчас». Выбор другой роли → инлайн-
 * подтверждение (вопрос + Отмена/Назначить) → PUT .../role.
 *
 * Промоут в со-организатора требует активного доступа участника (У-9): для frozen/expired пункт
 * задизейблен с пояснением, бэкенд всё равно ответит 400. Демоут в участника доступен при любом
 * статусе. Лимит со-оргов (5, У-3) проверяет бэкенд — его 400 показывается текстом ошибки.
 * 409 (параллельная смена роли) закрывает карточку: кэш ростера уже инвалидирован.
 *
 * UX-баг B (AC-10): при входе в подтверждение автоскроллим к блоку с обеими кнопками, чтобы в
 * нерастянутом окне вопрос И кнопки Отмена/Назначить были видны одновременно, а не «текст без кнопок».
 */
const RoleGate: FC<RoleGateProps> = ({ clubId, member, onDone }) => {
  const haptic = useHaptic();
  const updateRole = useUpdateMemberRoleMutation();
  // Выбранная в селекторе роль, ожидающая подтверждения (null = селектор в покое, текущая роль активна).
  const [pendingRole, setPendingRole] = useState<AssignableMemberRole | null>(null);
  const [error, setError] = useState<string | null>(null);
  const confirmRef = useRef<HTMLDivElement>(null);

  const currentRole = member.role;
  // У-9: со-оргом можно сделать только участника с активным доступом. frozen (ждёт первого взноса)
  // и expired (должник) — нельзя; null = поле не пришло (у владельца оно всегда заполнено).
  const canPromote = member.accessStatus !== 'frozen' && member.accessStatus !== 'expired';
  const busy = updateRole.isPending;

  // AC-10: как только выбрана роль на подтверждение — скроллим блок с кнопками в зону видимости,
  // чтобы Отмена/Назначить не оказались за фолдом в нерастянутом окне.
  useEffect(() => {
    if (pendingRole) confirmRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [pendingRole]);

  const selectRole = (role: AssignableMemberRole) => {
    if (busy || (role === 'co_organizer' && !canPromote)) return;
    // Тап по текущей роли отменяет выбор: селектор возвращается в покой (как кнопка «Отмена»).
    if (role === currentRole) {
      if (pendingRole) { haptic.impact('light'); setPendingRole(null); }
      return;
    }
    setError(null);
    haptic.impact('light');
    setPendingRole(role);
  };

  const handleConfirm = () => {
    if (busy || !pendingRole) return;
    const target = pendingRole;
    setError(null);
    haptic.impact('medium');
    updateRole.mutate(
      { clubId, userId: member.userId, role: target },
      {
        onSuccess: () => {
          haptic.notify('success');
          onDone(target === 'co_organizer'
            ? `${member.firstName} — теперь со-организатор`
            : `${member.firstName} больше не со-организатор`);
        },
        onError: (e) => {
          if (e instanceof ApiError && e.status === 409) { onDone('Роль участника уже изменилась'); return; }
          haptic.notify('error');
          setError(e instanceof Error ? e.message : 'Не удалось изменить роль');
        },
      },
    );
  };

  return (
    <div className="rd-mgmt">
      <div className="rd-mgmt-h">🤝 Роль в клубе</div>
      <div className="rd-mgmt-body">
        <div className={`rd-role-picker${pendingRole ? ' picking' : ''}`} role="radiogroup" aria-label="Роль участника">
          {ASSIGNABLE_ROLES.map((role) => {
            const selected = role === currentRole;
            // Выбрана тапом и ждёт подтверждения — пункт подсвечивается явно, иначе тап не давал
            // никакой обратной связи и было непонятно, что роль вообще выбрана (PO 2026-07-13).
            const pending = role === pendingRole;
            // Пока выбор висит на подтверждении, «отмечен» именно он, а не текущая роль:
            // radiogroup показывает намерение владельца, а бейдж «Сейчас» — фактическое состояние.
            const checked = pendingRole ? pending : selected;
            // Промоут frozen/expired недоступен (У-9); текущую роль не дизейблим — тап по ней отменяет выбор.
            const blocked = !selected && role === 'co_organizer' && !canPromote;
            return (
              <button
                key={role}
                type="button"
                role="radio"
                aria-checked={checked}
                aria-label={ROLE_LABELS[role]}
                className={`rd-role-opt${selected ? ' sel' : ''}${pending ? ' pend' : ''}`}
                disabled={busy || blocked}
                onClick={() => selectRole(role)}
              >
                <span className="rd-role-opt-top">
                  <span className="rd-role-opt-name">{ROLE_LABELS[role]}</span>
                  {selected && <span className="rd-role-opt-badge">Сейчас</span>}
                  {pending && <span className="rd-role-opt-badge rd-role-opt-badge-pick">✓ Выбрано</span>}
                </span>
                <span className="rd-role-opt-desc">{ROLE_DESCRIPTIONS[role]}</span>
              </button>
            );
          })}
        </div>

        {/* У-9: почему пункт «Со-организатор» недоступен — участник без активного доступа. */}
        {currentRole !== 'co_organizer' && !canPromote && (
          <div className="rd-claim-note">
            Назначить со-организатором можно только участника с активным доступом.
          </div>
        )}

        {pendingRole && (
          <div className="rd-reject-confirm" ref={confirmRef}>
            <div className="rd-reject-q">
              {pendingRole === 'co_organizer'
                ? `Сделать ${member.firstName} со-организатором? Появится доступ к заявкам, событиям, складчинам и участникам — кроме владельческих настроек.`
                : `Снять с ${member.firstName} роль со-организатора? Управляющие экраны клуба станут недоступны.`}
            </div>
            <div className="rd-org-gate-acts">
              <button type="button" className="rd-btn-outline" disabled={busy} onClick={() => setPendingRole(null)}>
                Отмена
              </button>
              <button
                type="button"
                className={`rd-btn-primary${pendingRole === 'member' ? ' rd-btn-danger' : ''}`}
                disabled={busy}
                onClick={handleConfirm}
              >
                {busy ? <Spinner size="s" /> : pendingRole === 'co_organizer' ? 'Назначить' : 'Снять роль'}
              </button>
            </div>
          </div>
        )}
        {error && <div className="rd-error" style={{ textAlign: 'left' }}>{error}</div>}
      </div>
    </div>
  );
};

/**
 * «Активность в клубе» (мокап B1, PO 2026-07-21): внерепутационные метрики отдельным блоком —
 * открытые встречи (V62, сырые отметки явки) и спонтанные визиты (переехали из футера колец).
 * Видимость данных решает бэкенд (AC-5): чужому зрителю поля приходят null → блок не рендерится.
 * Расширяемо: будущая внерепутационная метрика = ещё одна строка здесь.
 */
const ClubActivityStats: FC<{ profile: MemberProfileDto }> = ({ profile }) => {
  const openTotal = profile.openEventsTotal ?? 0;
  const openAttended = profile.openEventsAttended ?? 0;
  const openPct = openTotal > 0 ? Math.round((openAttended / openTotal) * 100) : 0;
  // Спонтанность — метрика посещаемости; для «финансового» участника (нет трека событий)
  // скрываем, чтобы не показывать бессмысленное «0» (F5-08 — гейт прежнего футера сохранён).
  const spontaneity = (profile.totalConfirmations ?? 0) > 0 ? (profile.spontaneityCount ?? 0) : null;
  if (openTotal === 0 && spontaneity === null) return null;
  return (
    <div className="rd-glass rd-ostat">
      <div className="rd-ostat-cap">Активность в клубе</div>
      {openTotal > 0 && (
        <div className="rd-ostat-row">
          <span className="rd-ostat-ico rd-ost-wave" aria-hidden="true">🌊</span>
          <span>
            <span className="rd-ostat-lbl">Открытые встречи</span>
            <div className="rd-ostat-sub">вне репутации — просто факт участия</div>
          </span>
          <span className="rd-ostat-val">
            <b>{openAttended} из {openTotal}</b>
            <span className="rd-ostat-pc">{openPct}% прихода</span>
          </span>
        </div>
      )}
      {spontaneity !== null && (
        <div className="rd-ostat-row">
          <span className="rd-ostat-ico rd-ost-spark" aria-hidden="true">⚡</span>
          <span>
            <span className="rd-ostat-lbl">Спонтанные визиты</span>
            <div className="rd-ostat-sub">визит состоялся, хотя голос был лишь «возможно»</div>
          </span>
          <span className="rd-ostat-val"><b>{spontaneity}</b></span>
        </div>
      )}
    </div>
  );
};

/**
 * Кольца per-club репутации + футер роли. Надёжность (умный композит) показывается всегда;
 * Посещаемость (события) и Сборы (складчины, влияющие на репутацию) — только когда есть данные, чтобы
 * участник с единственной осью не видел пустое кольцо «0/0». Спонтанность переехала из футера
 * в блок «Активность в клубе» (ClubActivityStats).
 */
const ReputationRings: FC<{ profile: MemberProfileDto }> = ({ profile }) => {
  const trust = profile.trust ?? 0;
  const confirmations = profile.totalConfirmations ?? 0;
  const attendances = profile.totalAttendances ?? 0;
  const attendancePct = confirmations > 0 ? Math.round((attendances / confirmations) * 100) : 0;
  const skladchinaPaid = profile.skladchinaPaid ?? 0;
  const skladchinaTotal = profile.skladchinaTotal ?? 0;
  const skladchinaPct = skladchinaTotal > 0 ? Math.round((skladchinaPaid / skladchinaTotal) * 100) : 0;
  // Роль в футере (co-organizers): «Организатор» / «Со-организатор» / «Участник».
  const roleLabel = membershipRoleLabel(profile.role);

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
      <div className="rd-rep-foot">Роль: {roleLabel}</div>
    </>
  );
};

export const MemberProfileModal: FC<MemberProfileModalProps> = ({
  member,
  clubId,
  isOrganizer = false,
  isOwner = false,
  onClose,
  onActionToast,
}) => {
  const profileQuery = useMemberProfileQuery(clubId, member.userId);
  const profile = profileQuery.data;
  const loading = profileQuery.isPending;

  // Смотрит ли пользователь свою же карточку. trust=null у бэкенда неотличим «нет истории» ↔ «скрыто
  // асимметрией» (#94): чужому фолбэк «Новичок» не показываем, себе и организатору — можно (у них null честен).
  const viewerId = useAuthStore((s) => s.user?.id);
  const isSelf = viewerId != null && viewerId === member.userId;
  // Секцию «Репутация в этом клубе» показываем, если: реальный скор пришёл (в списочной строке ИЛИ в
  // загруженном профиле — бэкенд уже решил, что зритель вправе его видеть → рисуем кольца) ЛИБО это карточка
  // организатора (объяснялка ролевая, не скор — видна всем) ЛИБО смотрящий вправе видеть фолбэк-статус
  // (организатор / сам о себе → null покажется как «Новичок»). Чужому участнику со скрытым скором (trust=null,
  // асимметрия #94) секцию не рендерим вовсе — вместо ложного «Новичок» просто ничего.
  const showReputationSection =
    member.trust !== null
    || profile?.trust != null
    || member.role === 'organizer'
    || isOrganizer
    || isSelf;

  // Админ-секция (per-target матрица co-organizers, зеркалит бэкенд): владелец управляет любым,
  // кроме владельца (в т.ч. со-организаторами); со-орг (менеджерский вид без isOwner) — только
  // участниками с ролью member (заморозка/кик/награды по владельцу или другому со-оргу → 403).
  // Никогда — своей строкой.
  const isManageable = !isSelf && (
    isOwner ? member.role !== 'organizer' : isOrganizer && member.role === 'member'
  );
  // Секция смены роли (owner-only): селектор назначаемых ролей по чужой не-владельческой строке.
  const showRoleGate = isOwner && !isSelf && member.role !== 'organizer';
  // Платный участник = есть окно доступа, либо статус без доступа (frozen — ждёт первого взноса,
  // expired — просрочил продление). Гейтит de-Stars-слой (строка подписки + dues-действия + своя
  // дата); бесплатному остаются только заметка + награды.
  const isPaidMember =
    member.accessStatus === 'frozen' || member.accessStatus === 'expired' || !!member.subscriptionExpiresAt;

  // Режим редактирования живёт здесь (не в OrganizerGate), чтобы ✎ в шапке переключал редактор наград
  // (под интересами) и форму «Своя дата» вместе. Заметка всегда открыта (✎ её не гейтит). Редактирует
  // только организатор.
  const [editing, setEditing] = useState(false);

  const handleGateDone = (message: string) => {
    onActionToast?.(message);
    onClose();
  };

  // Блокируем скролл фона, пока шторка открыта (как у остальных rd-шторок).
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
          {/* Аватар + имя + (@username · в клубе с DATE) */}
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

          {/* О себе */}
          {bio && (
            <div className="rd-field">
              <span className="rd-label">О себе</span>
              <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{bio}</div>
            </div>
          )}

          {/* Интересы */}
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

          {/* Награды клуба (S2) — ОДНО место: редактор, когда организатор в режиме редактирования, иначе
              публичные read-only чипы (R3, видны любому зрителю). Дублирующей секции «Награды клуба» нет. */}
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

          {/* Репутация в этом клубе — скрыта для чужого зрителя при скрытом скоре (см. showReputationSection) */}
          {showReputationSection && (
            <div>
              <div className="rd-section-sub-h" style={{ margin: '0 0 8px' }}>Репутация в этом клубе</div>
              {loading ? (
                <div className="rd-spinner-row" style={{ padding: '8px 0' }}><Spinner size="s" /></div>
              ) : profile && profile.trust !== null ? (
                <>
                  <ReputationRings profile={profile} />
                  <ClubActivityStats profile={profile} />
                </>
              ) : (
                <>
                  <div className="rd-glass rd-rep-panel">
                    <div className="rd-kv">
                      <span>
                        {profile?.role === 'organizer'
                          ? 'Здесь репутация начисляется за организаторские качества'
                          : 'Новичок — пока недостаточно данных'}
                      </span>
                    </div>
                  </div>
                  {/* Открытые встречи — вне репутации, поэтому видны и у «Новичка»: орг/сам могут
                      видеть визиты даже без репутационного трек-рекорда. */}
                  {profile && <ClubActivityStats profile={profile} />}
                </>
              )}
            </div>
          )}

          {/* Роль в клубе (co-organizers) — только владельцу: назначение/снятие со-организатора.
              Идёт перед админ-панелью, чтобы деструктивное «Удалить из клуба» осталось последним. */}
          {showRoleGate && (
            <RoleGate clubId={clubId} member={member} onDone={handleGateDone} />
          )}

          {/* Админ-секция менеджера: de-Stars-гейт доступа (только платные) + всегда открытая заметка
              (S1) + «Своя дата» за ✎. Награды (S2) обрабатываются выше, под интересами. */}
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

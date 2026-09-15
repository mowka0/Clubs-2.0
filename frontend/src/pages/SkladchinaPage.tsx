import { FC, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useSetClubContext } from '../store/useClubContextStore';
import { useAuthStore } from '../store/useAuthStore';
import { useSkladchinaActionMutation, useSkladchinaQuery, type SkladchinaAction } from '../queries/skladchina';
import { useDebtActionMutation, type DebtAction } from '../queries/debts';
import { ApiError } from '../api/apiClient';
import { Toast } from '../components/Toast';
import { ConfirmSheet, useConfirm } from '../components/ConfirmSheet';
import { ImageLightbox } from '../components/ImageLightbox';
import { DebtRow } from '../components/debt/DebtRow';
import type { DebtPersonDto, DebtStatus, SkladchinaDetailDto } from '../types/api';
import { formatRub, rubToKopecks } from '../utils/money';
import { formatTimeHM, shortName, untilText } from '../utils/formatters';
import { DATE_FMT, DAY_FMT, FREE_AMOUNT_LABEL, KIND_EMOJI, KIND_LABEL, SHORT_DAY_FMT, defaultPromiseDate, initials, isHttpLink, personName } from '../utils/skladchinaKind';

// Порядок в панели «не оплатили» у создателя: сначала те, кому нужен его ответ, потом обещавшие,
// потом ждём; прощённые и выбывшие в хвосте. `received` в эту панель не попадает.
const OPEN_ORDER: Record<DebtStatus, number> = { claimed: 0, promised: 1, waiting: 2, forgiven: 3, dropped: 4, received: 5 };

/** Строка человека в панелях сбора: аватар, имя (+ «(вы)»), @username, справа короткая пометка. */
const PersonRow: FC<{ person: DebtPersonDto; viewerId?: string; meta?: string }> = ({ person, viewerId, meta }) => (
  <div className="rd-debt-head rd-enrolled-row">
    <span className="rd-av rd-debt-av">{person.avatarUrl ? <img src={person.avatarUrl} alt="" /> : initials(personName(person))}</span>
    <span className="rd-debt-who">
      <b>{personName(person)}{viewerId && person.id === viewerId ? ' (вы)' : ''}</b>
      {person.username && <span className="rd-debt-handle">@{person.username}</span>}
    </span>
    {meta && <span className="rd-debt-meta">{meta}</span>}
  </div>
);

function errorMessage(e: unknown, fallback: string): string {
  if (e instanceof ApiError && (e.status === 400 || e.status === 403 || e.status === 409) && e.message) return e.message;
  return fallback;
}

/** Строка стадии под названием: что сейчас происходит со сбором и когда срок. */
function stageLine(s: SkladchinaDetailDto): string {
  if (s.isEnrolling) {
    return `В деле ${s.enrolledCount}${s.minParticipants ? ` · нужно ${s.minParticipants}` : ''}`;
  }
  const parts: string[] = [];
  if (s.kind === 'per_head') parts.push(s.orderedAt ? `куплено ${s.receivedItems} · приём закрыт${s.openCount > 0 ? ` · ждём оплату ${s.openCount}` : ''}` : `берут ${s.debtCount} · оплатили ${s.receivedCount}`);
  else if (s.kind === 'voluntary') {
    // «Сумму выбираете сами»: круг известен (позвали + создатель, если скинулся) — «перевели N из M»;
    // подарок «По желанию» — без знаменателя, круг платящих не задан (PO 2026-09-14).
    const circle = s.freeAmountRequired ? s.enrolled.length + (s.paid.some((p) => p.id === s.creatorId) ? 1 : 0) : 0;
    parts.push(circle > 0 ? `перевели ${s.receivedCount} из ${circle}` : `перевели ${s.receivedCount}`);
    if (s.promisedCount > 0) parts.push(`обещали ${s.promisedCount}`);
    if (s.claimedCount > 0) parts.push(`${s.claimedCount} ждут подтверждения`);
  }
  else parts.push(`оплатили ${s.receivedCount} из ${s.debtCount}`);
  // Сам срок здесь больше не повторяется — он крупно в плашке шапки (PO 2026-09-15); остаётся
  // только то, чего в плашке нет: сколько человек не заплатило после срока.
  if (s.deadline && s.status === 'active' && !s.orderedAt && new Date(s.deadline).getTime() < Date.now()) {
    parts.push(`не оплатили ${s.openCount}`);
  }
  return parts.join(' · ');
}

/**
 * Левая плашка шапки — зеркало «когда» у встречи: срок сбора, крупно дата и сколько до неё осталось.
 * У закрытого сбора срока уже нет, и крупной строкой становится его судьба («Собран» / «Отменён»).
 */
function deadlinePanel(s: SkladchinaDetailDto): { cap: string; value: string; at: string | null; note: string; late: boolean; mutedNote: boolean } {
  const closedDay = s.closedAt ? SHORT_DAY_FMT.format(new Date(s.closedAt)) : '';
  if (s.status === 'collected') return { cap: 'сбор', value: 'Собран', at: null, note: closedDay, late: false, mutedNote: true };
  if (s.status === 'cancelled') return { cap: 'сбор', value: 'Отменён', at: null, note: closedDay, late: false, mutedNote: true };
  if (s.isEnrolling && s.enrollmentUntil) {
    const left = untilText(s.enrollmentUntil);
    return { cap: 'отметиться до', value: SHORT_DAY_FMT.format(new Date(s.enrollmentUntil)), at: formatTimeHM(s.enrollmentUntil), note: left ?? 'срок вышел', late: left === null, mutedNote: false };
  }
  // «Кто берёт?» после заказа: платить ещё нужно, но главный факт экрана — что приём закрыт.
  if (s.orderedAt) return { cap: 'заказ сделан', value: SHORT_DAY_FMT.format(new Date(s.orderedAt)), at: formatTimeHM(s.orderedAt), note: 'приём закрыт', late: false, mutedNote: true };
  if (s.deadline) {
    const left = untilText(s.deadline);
    return { cap: 'оплатить до', value: SHORT_DAY_FMT.format(new Date(s.deadline)), at: formatTimeHM(s.deadline), note: left ?? 'срок вышел', late: left === null, mutedNote: false };
  }
  return { cap: 'сбор', value: 'Идёт', at: null, note: 'без срока', late: false, mutedNote: true };
}

export const SkladchinaPage: FC = () => {
  useBackButton(true);
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const haptic = useHaptic();
  const viewerId = useAuthStore((st) => st.user?.id) ?? '';
  const query = useSkladchinaQuery(id);
  useSetClubContext(query.data?.clubId);
  const actionMut = useSkladchinaActionMutation();
  const debtMut = useDebtActionMutation();

  const [toast, setToast] = useState<string | null>(null);
  const { confirm, confirmSheet } = useConfirm();
  const [error, setError] = useState<string | null>(null);
  const [amountInput, setAmountInput] = useState('');
  // «Оплачу позже» в «Сумму выбираете сами»: обещание всегда с суммой и датой (§ 3.5).
  const [promiseOpen, setPromiseOpen] = useState(false);
  const [promiseAmountInput, setPromiseAmountInput] = useState('');
  const [promiseDate, setPromiseDate] = useState(defaultPromiseDate);
  const [noteInput, setNoteInput] = useState('');
  const [quantityInput, setQuantityInput] = useState('1');
  // «Заказываю»: шторка со списком обещавших и выбором, брать ли их в долг.
  const [orderAsk, setOrderAsk] = useState(false);
  const [includePromised, setIncludePromised] = useState(true);
  const [photoZoomed, setPhotoZoomed] = useState(false);

  if (query.isPending) {
    return (
      <div className="rd-page">
        <div className="rd-spinner-row" style={{ paddingTop: 60 }}><Spinner size="m" /></div>
      </div>
    );
  }
  if (query.isError || !query.data) {
    return (
      <div className="rd-page">
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--text-dim)' }}>
          Не удалось загрузить сбор. Попробуйте позже.
        </div>
      </div>
    );
  }

  const s = query.data;
  const isActive = s.status === 'active';
  const busy = actionMut.isPending || debtMut.isPending;
  const promisedRows = (s.debts ?? []).filter((d) => d.status === 'promised');
  const waitingRows = (s.debts ?? []).filter((d) => d.status === 'waiting' && d.debtor.id !== d.creditor.id);
  // Создателю долги двумя панелями: не оплатившие и оплатившие, включая его собственный взнос (PO 2026-09-14).
  const openDebts = (s.debts ?? []).filter((d) => d.status !== 'received').sort((a, b) => OPEN_ORDER[a.status] - OPEN_ORDER[b.status]);
  const paidDebts = (s.debts ?? []).filter((d) => d.status === 'received');
  const openTitle = s.kind === 'per_head' ? 'Берут' : s.kind === 'voluntary' ? 'Подтвердите' : 'Кто должен';
  // «По желанию» (PO 2026-09-14): в «Подтвердите» только «говорит, что перевёл»; обещавшие и спорящие — в «Скидываются»
  // вместе с молчунами; кто перевёл — в «Перевели». Остальным участникам — те же панели людьми, без сумм.
  const openRows = s.kind === 'voluntary' ? openDebts.filter((d) => d.status === 'claimed') : openDebts;
  const pendingDebts = s.kind === 'voluntary' ? openDebts.filter((d) => d.status === 'promised' || d.status === 'waiting') : [];
  const paidIds = new Set(s.paid.map((p) => p.id));
  const debtorIds = new Set((s.debts ?? []).map((d) => d.debtor.id));
  const pendingPeople = s.enrolled.filter((p) => !paidIds.has(p.id) && !debtorIds.has(p.id));
  const paidTitle = s.kind === 'voluntary' ? 'Перевели' : 'Оплатили';
  const orderText = `Заказываю: оплатили ${s.receivedCount}, говорят, что отдали ${s.claimedCount}` +
    (waitingRows.length > 0 ? `, не оплатили ${waitingRows.length} — они выбывают.` : '.');
  const takeQuantity = /^\d+$/.test(quantityInput.trim()) && Number(quantityInput) >= 1 && Number(quantityInput) <= 50 ? Number(quantityInput) : null;
  const target = s.targetKopecks ?? s.amountKopecks;
  // Знаменатель и полоса: у «По желанию» — ориентир создателя (есть с первой секунды), у остальных
  // видов появляются с первым долгом, до него нет ни знаменателя, ни ожидания.
  const showTarget = Boolean(target && target > 0 && (s.kind === 'voluntary' || s.debtCount > 0));
  const receivedPct = target && target > 0 ? Math.min(100, Math.round((s.receivedKopecks / target) * 100)) : 0;
  // Обещанное — своей штриховкой (PO 2026-09-14); у «Кто берёт?» всё взятое и так в штриховке ожидания.
  const promisedPct = target && target > 0 && s.kind !== 'per_head' ? Math.min(100 - receivedPct, Math.round((s.promisedKopecks / target) * 100)) : 0;
  // Штриховка = деньги ждём: у «Кто берёт?» это всё взятое и не оплаченное («Беру» — заявка),
  // у остальных видов только «говорит, что отдал» (PO 2026-09-13).
  const pendingKopecks = s.kind === 'per_head' && target ? Math.max(0, target - s.receivedKopecks) : s.claimedKopecks;
  const claimedPct = target && target > 0 ? Math.min(100 - receivedPct - promisedPct, Math.round((pendingKopecks / target) * 100)) : 0;

  const run = async (action: SkladchinaAction, done: string | ((result: SkladchinaDetailDto) => string), confirmText?: string) => {
    if (confirmText && !(await confirm(confirmText))) return;
    setError(null);
    try {
      haptic.impact('medium');
      const result = await actionMut.mutateAsync({ id: s.id, action });
      haptic.notify('success');
      setToast(typeof done === 'function' ? done(result) : done);
    } catch (e) {
      console.error('skladchina action failed', action.type, e);
      haptic.notify('error');
      setError(errorMessage(e, 'Не получилось. Попробуйте ещё раз.'));
    }
  };

  const runDebt = async (debtId: string, action: DebtAction) => {
    setError(null);
    try {
      haptic.impact('medium');
      await debtMut.mutateAsync({ debtId, action });
      haptic.notify('success');
    } catch (e) {
      console.error('debt action failed', action.type, e);
      haptic.notify('error');
      setError(errorMessage(e, 'Не получилось. Попробуйте ещё раз.'));
    }
  };

  const handleContribute = () => {
    const kopecks = rubToKopecks(amountInput);
    if (kopecks === null) {
      haptic.notify('error');
      setError('Введите сумму');
      return;
    }
    void run({ type: 'contribute', amountKopecks: kopecks }, 'Перевод отмечен — создатель подтвердит.', `Перевели ${formatRub(kopecks)}? ${s.creator.firstName} получит уведомление и подтвердит.`).then(() => setAmountInput(''));
  };

  const handlePromise = () => {
    const kopecks = rubToKopecks(promiseAmountInput);
    if (kopecks === null) {
      haptic.notify('error');
      setError('Укажите, сколько отдадите');
      return;
    }
    void run(
      { type: 'promise', amountKopecks: kopecks, date: promiseDate },
      'Обещание записано — создатель увидит.',
      `Обещаете ${formatRub(kopecks)} до ${DAY_FMT.format(new Date(promiseDate))}? Сумма вычтется из остатка счёта.`,
    );
  };

  const clubInitials = initials(s.clubName);
  const when = deadlinePanel(s);
  // Вид сбора одной подписью: у сбора из встречи с суммой вместо «По желанию» — режим (§ 3.5).
  const kindBadge = s.freeAmountRequired ? `💸 ${FREE_AMOUNT_LABEL}` : `${KIND_EMOJI[s.kind]} ${KIND_LABEL[s.kind]}`;

  return (
    <div className="rd-page">
      {/* Обложка — только когда организатор приложил фото (PO 2026-09-15): у сбора без фото
          градиент во весь экран ничего не сообщал бы. Тап открывает полный размер — на фото
          сбора обычно чек, и его читают, а обрезанный под обложку он нечитаем. */}
      {s.photoUrl ? (
        <button
          type="button"
          className="rd-hero rd-compact rd-hero-event rd-hero-tap"
          aria-label={`${s.title} — открыть фото сбора`}
          onClick={() => { haptic.impact('light'); setPhotoZoomed(true); }}
        >
          <span className="rd-hero-bg" style={{ backgroundImage: `url(${s.photoUrl})` }} />
          <span className="rd-hero-meta">
            <span className="rd-hero-type-badge">{kindBadge.toUpperCase()}</span>
            <span className="rd-hero-ttl">{s.title}</span>
          </span>
        </button>
      ) : (
        <>
          <div className="rd-ft-eyebrow">{kindBadge}</div>
          <h1 className="rd-page-h" style={{ marginBottom: 14 }}>{s.title}</h1>
        </>
      )}
      <ImageLightbox src={photoZoomed ? s.photoUrl : null} alt="Фото сбора" onClose={() => setPhotoZoomed(false)} />

      {/* Две плашки в ряд, как на встрече (PO 2026-09-15): слева срок, справа кто собирает.
          Статус сбора уехал в левую плашку — отдельный бейдж «Идёт» рядом со сроком повторял
          то же самое. */}
      <div className="rd-head-row">
        <div className="rd-glass rd-when-panel">
          <div className="rd-when-day">{when.cap}</div>
          <div className="rd-when-time rd-when-date">{when.value}</div>
          {when.at && <div className="rd-when-at">{when.at}</div>}
          {when.note && (
            <div className={`rd-when-until${when.late ? ' rd-when-late' : when.mutedNote ? ' rd-when-muted' : ''}`}>{when.note}</div>
          )}
        </div>
        <button
          type="button"
          className="rd-glass rd-host-panel"
          aria-label={`Открыть клуб ${s.clubName}`}
          onClick={() => { haptic.impact('light'); navigate(`/clubs/${s.clubId}`); }}
        >
          <span className="rd-host-cap">собирает</span>
          <span className="rd-host-line">
            <span className="rd-host-av rd-host-club">
              {s.clubAvatarUrl ? <img src={s.clubAvatarUrl} alt="" /> : clubInitials}
            </span>
            <span className="rd-host-nm">{s.clubName}</span>
          </span>
          <span className="rd-host-line">
            <span className="rd-host-av rd-host-man">
              {s.creator.avatarUrl ? <img src={s.creator.avatarUrl} alt="" /> : initials(personName(s.creator))}
            </span>
            <span className="rd-host-nm">{shortName(s.creator)}{s.isCreator ? ' (вы)' : ''}</span>
          </span>
        </button>
      </div>

      {/* Сбор из встречи: встреча отдельной строкой, как крошка клуба, с переходом (PO 2026-09-14). */}
      {s.eventId && (
        <button
          type="button"
          className="rd-glass rd-host-row"
          onClick={() => { haptic.impact('light'); navigate(`/events/${s.eventId}`); }}
          aria-label={`Открыть встречу ${s.eventTitle ?? ''}`}
          style={{ width: '100%', marginBottom: 14, cursor: 'pointer', fontFamily: 'inherit', textAlign: 'left' }}
        >
          <span className="rd-ico" aria-hidden="true">📅</span>
          <div className="rd-info">
            <div className="rd-met">За встречу{s.eventDatetime ? ` · ${DATE_FMT.format(new Date(s.eventDatetime))}` : ''}</div>
            <div className="rd-ttl">{s.eventTitle}</div>
          </div>
          <span aria-hidden="true" style={{ color: 'var(--text-faint)', fontSize: 20, lineHeight: 1 }}>›</span>
        </button>
      )}

      {/* Прогресс: деньги — главная строка, полоса 🟩 получено / 🟨 говорят, что отдали. */}
      <div className="rd-glass" style={{ padding: 16, marginBottom: 14 }}>
        <div style={{ fontSize: 18, fontWeight: 700, color: 'var(--text)', marginBottom: 10 }}>
          {s.isEnrolling
            ? `${formatRub(s.amountKopecks ?? 0)} на группу, поровну между теми, кто в деле`
            : showTarget
              ? `Получено ${formatRub(s.receivedKopecks)} из ${formatRub(target ?? 0)}`
              : `Получено ${formatRub(s.receivedKopecks)}`}
        </div>
        {!s.isEnrolling && showTarget && (
          <div className="rd-progress" aria-hidden="true">
            <div className="rd-fill" style={{ width: `${receivedPct}%` }} />
            <div className="rd-fill rd-fill-promised" style={{ width: `${promisedPct}%` }} />
            <div className="rd-fill rd-fill-claimed" style={{ width: `${claimedPct}%` }} />
          </div>
        )}
        <div className="rd-sklad-stats">{stageLine(s)}</div>
        {s.freeAmountRequired && isActive && s.deadline && new Date(s.deadline).getTime() > Date.now() && (
          <div className="rd-debt-meta" style={{ marginTop: 6 }}>после срока остаток разделится между теми, кто промолчал</div>
        )}
        {s.description && <div className="rd-sklad-inline-sep">{s.description}</div>}
      </div>

      {s.rules && (
        <>
          <div className="rd-section-sub-h">Правила</div>
          <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
            <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{s.rules}</div>
          </div>
        </>
      )}

      {/* На этапе записи платить ещё нечего: реквизиты появляются со второго этапа (PO 2026-09-13). */}
      {!s.isCreator && !s.isEnrolling && (
        <>
          <div className="rd-section-sub-h">Кому переводить</div>
          {/* Только реквизиты: кто собирает, видно в шапке — строка человека здесь его повторяла (PO 2026-09-15). */}
          <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
            {s.paymentMethodNote && <div className="rd-body-text" style={{ margin: '0 0 10px', padding: 0 }}>{s.paymentMethodNote}</div>}
            {isHttpLink(s.paymentLink) ? (
              <>
                <button type="button" className="rd-btn-primary" onClick={() => { haptic.impact('light'); window.open(s.paymentLink, '_blank', 'noopener,noreferrer'); }}>
                  Открыть в банке
                </button>
                <div className="rd-payment-link-text">{s.paymentLink}</div>
              </>
            ) : (
              // Номер телефона или свободный текст: кнопке присвоить нечего — показываем как есть (PO 2026-09-14).
              <div className="rd-requisites-text">{s.paymentLink}</div>
            )}
          </div>
        </>
      )}

      {/* Моя часть: запись, «Беру», «Перевёл» или мой долг. */}
      {!s.isCreator && isActive && (
        <div className="rd-glass" style={{ padding: 16, marginBottom: 14 }}>
          {s.isEnrolling && (
            s.myEnrolled ? (
              <>
                <div className="rd-debt-meta" style={{ marginBottom: 10 }}>Вы в деле. Доля посчитается, когда список закроется.</div>
                <button type="button" className="rd-btn-outline" disabled={busy} onClick={() => run({ type: 'leave' }, 'Вы вышли из списка.', 'Выйти из списка «В деле»?')}>Передумал</button>
              </>
            ) : (
              <button type="button" className="rd-btn-primary" disabled={busy} onClick={() => run({ type: 'join' }, 'Вы в деле!', 'Отметиться «В деле»? Доля посчитается, когда список закроется.')}>В деле</button>
            )
          )}

          {s.kind === 'per_head' && (!s.myDebt || s.myDebt.status === 'dropped') && !s.orderedAt && (
            <>
              <input
                className="rd-input"
                placeholder="Заметка: размер, вариант (необязательно)"
                value={noteInput}
                onChange={(e) => setNoteInput(e.target.value)}
                maxLength={200}
                style={{ marginBottom: 10 }}
              />
              <div className="rd-take-row">
                <input
                  className="rd-input rd-take-qty"
                  type="number"
                  inputMode="numeric"
                  min="1"
                  max="50"
                  aria-label="Сколько штук"
                  value={quantityInput}
                  onChange={(e) => setQuantityInput(e.target.value)}
                />
                <button
                  type="button"
                  className="rd-btn-primary"
                  disabled={busy || takeQuantity === null}
                  onClick={() => run(
                    { type: 'join', note: noteInput.trim() || null, quantity: takeQuantity ?? 1 },
                    'Записали за вами.',
                    `Беру ${takeQuantity ?? 1} × ${formatRub(s.amountKopecks ?? 0)} = ${formatRub((s.amountKopecks ?? 0) * (takeQuantity ?? 1))}?`,
                  )}
                >
                  Беру
                </button>
              </div>
              {s.myDebt?.status === 'dropped' && <div className="rd-debt-meta" style={{ marginTop: 8 }}>Вы выбывали из этого сбора — можно взять снова.</div>}
            </>
          )}
          {s.kind === 'per_head' && (!s.myDebt || s.myDebt.status === 'dropped') && s.orderedAt && (
            <div className="rd-debt-meta">Приём закрыт: заказ уже сделан.</div>
          )}

          {s.kind === 'voluntary' && !s.myDebt && (
            <>
              <div className="rd-section-sub-h" style={{ marginTop: 0 }}>Ваш перевод</div>
              <div style={{ position: 'relative', marginBottom: 10 }}>
                <input
                  type="number"
                  inputMode="decimal"
                  min="1"
                  step="1"
                  placeholder="Сколько перевели, ₽"
                  value={amountInput}
                  onChange={(e) => setAmountInput(e.target.value)}
                  className="rd-input"
                  style={{ paddingRight: 32 }}
                  aria-label="Сумма перевода"
                />
                <span style={{ position: 'absolute', right: 14, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-faint)' }}>₽</span>
              </div>
              <button type="button" className="rd-btn-primary" disabled={busy} onClick={handleContribute}>Перевёл</button>
              {s.freeAmountRequired && !promiseOpen && (
                <button type="button" className="rd-btn-outline" style={{ marginTop: 8 }} onClick={() => { haptic.select(); setPromiseOpen(true); }}>Оплачу позже</button>
              )}
              {s.freeAmountRequired && promiseOpen && (
                <div style={{ marginTop: 10 }}>
                  <div className="rd-section-sub-h" style={{ marginTop: 0 }}>Оплачу позже</div>
                  <div style={{ position: 'relative', marginBottom: 8 }}>
                    <input
                      type="number"
                      inputMode="decimal"
                      min="1"
                      step="1"
                      placeholder="Сколько отдадите, ₽"
                      value={promiseAmountInput}
                      onChange={(e) => setPromiseAmountInput(e.target.value)}
                      className="rd-input"
                      style={{ paddingRight: 32 }}
                      aria-label="Сумма обещания"
                    />
                    <span style={{ position: 'absolute', right: 14, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-faint)' }}>₽</span>
                  </div>
                  <input className="rd-input" type="date" value={promiseDate} onChange={(e) => setPromiseDate(e.target.value)} aria-label="Дата обещания" style={{ marginBottom: 8 }} />
                  <button type="button" className="rd-btn-primary" disabled={busy} onClick={handlePromise}>Обещаю</button>
                  <button type="button" className="rd-btn-outline" style={{ marginTop: 6 }} onClick={() => setPromiseOpen(false)}>Отмена</button>
                </div>
              )}
            </>
          )}

          {s.myDebt && s.myDebt.status !== 'dropped' && (
            <>
              <div className="rd-section-sub-h" style={{ marginTop: 0 }}>Мой долг</div>
              <DebtRow
                debt={s.myDebt}
                viewerId={viewerId}
                busy={busy}
                onAction={(a) => runDebt(s.myDebt!.id, a)}
                extraAction={s.kind === 'per_head' && !s.orderedAt && (s.myDebt.status === 'waiting' || s.myDebt.status === 'promised') ? (
                  <button type="button" className="rd-btn-outline" disabled={busy} onClick={() => run({ type: 'leave' }, 'Вы передумали.', 'Передумали брать? Долг снимется, взять снова можно до заказа.')}>
                    Передумал
                  </button>
                ) : undefined}
              />
            </>
          )}
          {error && <div className="rd-error" style={{ marginTop: 8 }}>{error}</div>}
        </div>
      )}

      {!s.isCreator && !isActive && s.myDebt && (
        <div className="rd-glass" style={{ padding: 16, marginBottom: 14 }}>
          <DebtRow debt={s.myDebt} viewerId={viewerId} readOnly onAction={() => undefined} />
        </div>
      )}

      {/* Этап записи — кто в деле (долгов ещё нет). */}
      {s.isEnrolling && (
        <>
          <div className="rd-section-sub-h">
            В деле <span className="rd-count">· {s.enrolled.length}</span>
          </div>
          <div className="rd-glass" style={{ padding: '6px 12px', marginBottom: 14 }}>
            {s.enrolled.length === 0 && <div className="rd-debt-meta" style={{ padding: '10px 4px' }}>Пока никого.</div>}
            {s.enrolled.map((p) => <PersonRow key={p.id} person={p} viewerId={viewerId} />)}
          </div>
        </>
      )}

      {/* «По желанию»: «Скидываются» — только кто ещё не скинул (PO 2026-09-14). Создателю обещания и споры —
          строками долга с кнопками, молчуны — людьми; остальным — людьми без пометок. Пусто — панели нет. */}
      {s.kind === 'voluntary' && pendingDebts.length + pendingPeople.length > 0 && (
        <>
          <div className="rd-section-sub-h">
            Скидываются <span className="rd-count">· {pendingDebts.length + pendingPeople.length}</span>
          </div>
          <div className="rd-glass" style={{ padding: '6px 12px', marginBottom: 14 }}>
            {pendingDebts.map((d) => (
              <DebtRow key={d.id} debt={d} viewerId={viewerId} readOnly={!isActive} busy={busy} onAction={(a) => runDebt(d.id, a)} />
            ))}
            {pendingPeople.map((p) => <PersonRow key={p.id} person={p} viewerId={viewerId} />)}
          </div>
        </>
      )}

      {/* Оплатившие — всем участникам, людьми без сумм (PO 2026-09-14); у создателя вместо этого панели долгов ниже. */}
      {!s.isCreator && !s.isEnrolling && s.paid.length > 0 && (
        <>
          <div className="rd-section-sub-h">
            {paidTitle} <span className="rd-count">· {s.paid.length}</span>
          </div>
          <div className="rd-glass" style={{ padding: '6px 12px', marginBottom: 14 }}>
            {s.paid.map((p) => <PersonRow key={p.id} person={p} viewerId={viewerId} meta="✅" />)}
          </div>
        </>
      )}

      {/* Создатель: не оплатившие и оплатившие двумя панелями, потом кнопки стадии. */}
      {s.isCreator && s.debts && !s.isEnrolling && (
        <>
          {(s.kind !== 'voluntary' || openRows.length > 0) && (
            <>
              <div className="rd-section-sub-h">
                {openTitle} <span className="rd-count">· {openRows.length}</span>
              </div>
              <div className="rd-glass" style={{ padding: '6px 12px', marginBottom: 14 }}>
                {openRows.length === 0 && (
                  <div className="rd-debt-meta" style={{ padding: '10px 4px' }}>{paidDebts.length === 0 ? 'Пока никого.' : 'Все оплатили.'}</div>
                )}
                {openRows.map((d) => (
                  <DebtRow key={d.id} debt={d} viewerId={viewerId} readOnly={!isActive} busy={busy} onAction={(a) => runDebt(d.id, a)} />
                ))}
              </div>
            </>
          )}
          {paidDebts.length > 0 && (
            <>
              <div className="rd-section-sub-h">
                {paidTitle} <span className="rd-count">· {paidDebts.length}</span>
              </div>
              <div className="rd-glass" style={{ padding: '6px 12px', marginBottom: 14 }}>
                {paidDebts.map((d) => (
                  <DebtRow key={d.id} debt={d} viewerId={viewerId} readOnly={!isActive} busy={busy} onAction={(a) => runDebt(d.id, a)} />
                ))}
              </div>
            </>
          )}
        </>
      )}

      {isActive && (s.isCreator || s.canCancel) && (
        <div className="rd-form" style={{ marginTop: 4 }}>
          {s.isCreator && s.isEnrolling && (
            <button
              type="button"
              className="rd-btn-primary"
              disabled={busy}
              onClick={() => run(
                { type: 'lock' },
                (r) => (r.status === 'cancelled' ? 'Не набрали: сбор отменён, денег никто не переводил.' : 'Список закрыт, долги созданы.'),
                s.minParticipants && s.enrolledCount < s.minParticipants
                  ? `В деле ${s.enrolledCount}, нужно ${s.minParticipants}: сбор будет отменён как «не набрали». Закрыть запись?`
                  : 'Закрыть запись сейчас? Доли посчитаются по тем, кто в деле.',
              )}
            >
              Закрыть запись
            </button>
          )}
          {s.isCreator && s.kind === 'per_head' && !s.orderedAt && (
            <button
              type="button"
              className="rd-btn-primary"
              disabled={busy}
              onClick={() => setOrderAsk(true)}
            >
              Заказываю
            </button>
          )}
          {s.isCreator && s.kind === 'voluntary' && (
            <>
              <button type="button" className="rd-btn-primary" disabled={busy || s.claimedCount > 0} onClick={() => run({ type: 'close' }, 'Сбор закрыт.', 'Закрыть сбор?')}>
                Закрыть сбор
              </button>
              {s.claimedCount > 0 && <div className="rd-hint">Разберите переводы: {s.claimedCount}</div>}
            </>
          )}
          {s.canCancel && (
            <button
              type="button"
              className="rd-btn-outline"
              disabled={busy}
              style={{ color: 'var(--danger)' }}
              onClick={() => run({ type: 'cancel' }, 'Сбор отменён.', 'Отменить сбор? Открытые долги простятся, полученное придётся вернуть.')}
            >
              Отменить сбор
            </button>
          )}
          {error && <div className="rd-error">{error}</div>}
        </div>
      )}

      {toast && <Toast message={toast} onClose={() => setToast(null)} />}
      {confirmSheet}
      {orderAsk && (
        <ConfirmSheet
          text={orderText}
          confirmLabel="Заказываю"
          onCancel={() => setOrderAsk(false)}
          onConfirm={() => {
            setOrderAsk(false);
            void run({ type: 'order', includePromised: promisedRows.length > 0 && includePromised }, 'Заказ сделан.');
          }}
        >
          {promisedRows.length > 0 && (
            <div className="rd-order-promised">
              <div className="rd-debt-meta">Обещали позже:</div>
              {promisedRows.map((d) => (
                <div className="rd-debt-meta" key={d.id}>
                  {personName(d.debtor)} — {formatRub(d.amountKopecks)} к {d.promisedAt ? DAY_FMT.format(new Date(d.promisedAt)) : '—'}
                </div>
              ))}
              <label className="rd-check" style={{ marginTop: 8 }}>
                <input type="checkbox" checked={includePromised} onChange={(e) => setIncludePromised(e.target.checked)} />
                <span>Купить и на них в долг. Без галочки они выбывают, обещание аннулируется.</span>
              </label>
            </div>
          )}
        </ConfirmSheet>
      )}
    </div>
  );
};

import { FC, useState } from 'react';
import { createPortal } from 'react-dom';
import { useHaptic } from '../../hooks/useHaptic';
import type { SkladchinaDetailDto, SkladchinaParticipantDto } from '../../types/api';

/**
 * «Заявки на оплату» — панель организатора на странице сбора (решение PO 2026-09-09, мокап
 * docs/design/skladchina-split-header/mockups/09-organizer-final-t3.html).
 *
 * Три таба, как ростер встречи: «В работе» — всё, что ждёт хода организатора (заявил оплату,
 * прислал чек, просит отказаться), «Готово» — по чему решение есть, «Ждём» — от кого ничего не
 * пришло. В строке только статус; у заявок в работе справа чекбокс, хвост панели — «Обработать (n)»:
 * отмеченные принимаются (оплата засчитывается, отказ одобряется). Отказать можно только раскрыв
 * заявку — тап по строке открывает шторку с подробностями и «отрицательными» действиями.
 */

type Tab = 'work' | 'done' | 'wait';
/** Группы таба «В работе»: заголовок группы объясняет, что значит галочка в её строках. */
type RequestKind = 'claim' | 'receipt' | 'decline';

const TAB_LABELS: Record<Tab, string> = { work: 'В работе', done: 'Готово', wait: 'Ждём' };
const TAB_ORDER: Tab[] = ['work', 'done', 'wait'];
const GROUP_LABELS: Record<RequestKind, string> = {
  claim: 'Заявили оплату',
  receipt: 'Прислали чек',
  decline: 'Просят отказаться',
};
const GROUP_ORDER: RequestKind[] = ['claim', 'receipt', 'decline'];
const EMPTY_TEXT: Record<Tab, string> = {
  work: 'В работе ничего нет — разбирать нечего.',
  done: 'Решений пока нет.',
  wait: 'Все ответили.',
};

// Когда заявил / прислал чек — в шторке: «8 сентября, 21:40».
const WHEN_FMT = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' });

function formatRubles(kopecks: number): string {
  return Math.floor(kopecks / 100).toLocaleString('ru-RU');
}

function getInitials(firstName: string, lastName: string | null): string {
  const last = lastName ? lastName.charAt(0).toUpperCase() : '';
  return `${firstName.charAt(0).toUpperCase()}${last}`;
}

function fullName(p: SkladchinaParticipantDto): string {
  return `${p.firstName}${p.lastName ? ` ${p.lastName}` : ''}`;
}

/** Заявка, требующая решения организатора; null — участник не в работе. */
export function requestKind(p: SkladchinaParticipantDto): RequestKind | null {
  if (p.status === 'paid') return 'claim';
  if (p.status === 'payment_disputed') return 'receipt';
  if (p.status === 'pending' && p.declineRequested) return 'decline';
  return null;
}

function tabOf(p: SkladchinaParticipantDto): Tab {
  if (requestKind(p) !== null) return 'work';
  if (p.status === 'pending') return 'wait';
  return 'done';
}

interface RowStatus {
  text: string;
  dot: string;
  tone?: 'ok' | 'bad';
}

/** Статус одной строкой и цвет точки — тем же набором, что у точек ростера встречи. */
function rowStatus(p: SkladchinaParticipantDto): RowStatus {
  const rub = formatRubles(p.declaredAmountKopecks ?? p.expectedAmountKopecks ?? 0);
  switch (p.status) {
    case 'paid':                return { text: `заявил ${rub} ₽`, dot: 'rd-d-go' };
    case 'payment_disputed':    return { text: 'прислал чек', dot: 'rd-d-maybe' };
    case 'payment_confirmed':   return { text: `зачтено · ${rub} ₽`, dot: 'rd-d-ok', tone: 'ok' };
    case 'payment_rejected':    return { text: 'не дошёл', dot: 'rd-d-expired', tone: 'bad' };
    case 'declined':            return { text: 'отказался', dot: 'rd-d-no' };
    case 'expired_no_response': return { text: 'не ответил', dot: 'rd-d-expired' };
    case 'released':            return { text: 'не потребовался', dot: 'rd-d-no' };
    case 'pending':
    default:
      return p.declineRequested
        ? { text: 'просит отказаться', dot: 'rd-d-go' }
        : { text: 'не ответил', dot: 'rd-d-no' };
  }
}

export interface OrganizerRequestsPanelProps {
  skladchina: SkladchinaDetailDto;
  participants: SkladchinaParticipantDto[];
  /** Идёт орг-мутация — чекбоксы и кнопки строк неактивны. */
  busy: boolean;
  /** Наличные отмечает организатор и только в фиксированных режимах. */
  canMarkCash: boolean;
  /** «Обработать»: принять отмеченные заявки. */
  onProcess: (selected: SkladchinaParticipantDto[]) => Promise<void>;
  onResolvePayment: (p: SkladchinaParticipantDto, accept: boolean) => Promise<void>;
  onResolveDecline: (p: SkladchinaParticipantDto, approve: boolean, rejectReason?: string) => Promise<void>;
  onMarkCash: (p: SkladchinaParticipantDto) => Promise<void>;
  /** Снять отметку наличных, поставленную по ошибке (fixed-режимы). */
  onUnmarkCash: (p: SkladchinaParticipantDto) => Promise<void>;
}

export const OrganizerRequestsPanel: FC<OrganizerRequestsPanelProps> = ({
  skladchina, participants, busy, canMarkCash, onProcess, onResolvePayment, onResolveDecline, onMarkCash, onUnmarkCash,
}) => {
  const haptic = useHaptic();
  const isActive = skladchina.status === 'active';
  const [tab, setTab] = useState<Tab>('work');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set());
  const [openId, setOpenId] = useState<string | null>(null);

  const counts: Record<Tab, number> = { work: 0, done: 0, wait: 0 };
  participants.forEach((p) => { counts[tabOf(p)] += 1; });

  // Закрытый сбор — итоги одним списком: табов и чекбоксов нет, решать уже нечего.
  const visible = isActive ? participants.filter((p) => tabOf(p) === tab) : participants;
  // Отмеченными считаем только тех, кто ещё в работе: после чужого решения галочка теряет смысл.
  const selected = participants.filter((p) => selectedIds.has(p.userId) && tabOf(p) === 'work');
  const openParticipant = openId ? participants.find((p) => p.userId === openId) ?? null : null;

  // Шторка есть у всего, по чему можно решать: спор с чеком организатор разбирает и после закрытия.
  const canOpen = (p: SkladchinaParticipantDto) =>
    isActive ? tabOf(p) !== 'wait'
      : p.status === 'paid' || p.status === 'payment_disputed' || p.status === 'payment_rejected';

  const toggle = (userId: string) => {
    haptic.select();
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(userId)) next.delete(userId); else next.add(userId);
      return next;
    });
  };

  const handleProcess = async () => {
    await onProcess(selected);
    setSelectedIds(new Set());
  };

  const renderRow = (p: SkladchinaParticipantDto) => {
    const status = rowStatus(p);
    const name = fullName(p);
    const openable = canOpen(p);
    const inWork = isActive && tabOf(p) === 'work';
    const checked = selectedIds.has(p.userId);
    return (
      <div className="rd-pend-row" key={p.userId}>
        <button
          type="button"
          className="rd-pend-main"
          disabled={!openable}
          aria-label={openable ? `Открыть заявку: ${name}` : undefined}
          onClick={() => { haptic.impact('light'); setOpenId(p.userId); }}
        >
          <span className="rd-pend-av">
            {p.avatarUrl ? <img src={p.avatarUrl} alt="" /> : getInitials(p.firstName, p.lastName)}
          </span>
          <span className="rd-pend-txt">
            <span className="rd-pend-name">
              {name}
              <span className={`rd-vdot ${status.dot}`} aria-hidden="true" />
            </span>
            <span className={`rd-pend-met${status.tone ? ` rd-req-met-${status.tone}` : ''}`}>
              {status.text}
              {/* «›» — часть текста, а не кнопка: намёк, что строка раскрывается (вариант F). */}
              {openable && <span className="rd-req-chev" aria-hidden="true">›</span>}
            </span>
          </span>
        </button>
        {inWork && (
          <button
            type="button"
            role="checkbox"
            aria-checked={checked}
            aria-label={`Принять: ${name}`}
            className={`rd-req-cb${checked ? ' rd-on' : ''}`}
            disabled={busy}
            onClick={() => toggle(p.userId)}
          >
            ✓
          </button>
        )}
        {isActive && tab === 'wait' && canMarkCash && (
          <button
            type="button"
            className="rd-remind-btn"
            aria-label={`Получили наличными: ${name}`}
            title="Получили наличными"
            disabled={busy}
            onClick={() => onMarkCash(p)}
          >
            💵
          </button>
        )}
      </div>
    );
  };

  // «В работе» — группами по виду заявки, остальные табы и закрытый сбор — одним списком.
  const grouped = isActive && tab === 'work'
    ? GROUP_ORDER
      .map((kind) => ({ kind, rows: visible.filter((p) => requestKind(p) === kind) }))
      .filter((g) => g.rows.length > 0)
    : null;

  return (
    <>
      <div className="rd-section-sub-h">Заявки на оплату</div>
      {isActive && (
        <div className="rd-seg rd-seg-flush" style={{ marginBottom: 10 }}>
          {TAB_ORDER.map((t) => (
            <button
              key={t}
              type="button"
              className={`rd-seg-btn${tab === t ? ' rd-active' : ''}`}
              aria-pressed={tab === t}
              onClick={() => { haptic.impact('light'); setTab(t); }}
            >
              {TAB_LABELS[t]}<span className="rd-seg-cnt">· {counts[t]}</span>
            </button>
          ))}
        </div>
      )}
      <div className="rd-glass rd-pend-panel" style={{ marginBottom: 14 }}>
        {visible.length === 0 && (
          <div className="rd-resp-empty">{isActive ? EMPTY_TEXT[tab] : 'Участников нет.'}</div>
        )}
        {grouped
          ? grouped.map((g) => (
            <div key={g.kind}>
              <div className="rd-req-grp">{GROUP_LABELS[g.kind]} · {g.rows.length}</div>
              {g.rows.map(renderRow)}
            </div>
          ))
          : visible.map(renderRow)}
        {isActive && tab === 'work' && visible.length > 0 && (
          <button
            type="button"
            className="rd-remind-all"
            disabled={busy || selected.length === 0}
            onClick={handleProcess}
          >
            {busy ? 'Обрабатываем…' : selected.length > 0 ? `Обработать (${selected.length})` : 'Обработать'}
          </button>
        )}
      </div>
      {isActive && tab === 'work' && (
        <div className="rd-cta-hint" style={{ textAlign: 'left', marginTop: -6, marginBottom: 14 }}>
          Отмеченные принимаются: оплата засчитывается, отказ одобряется. Не нашли платёж — откройте
          заявку. Когда по каждому будет решение, сбор закроется сам.
        </div>
      )}
      {openParticipant && (
        <RequestSheet
          participant={openParticipant}
          affectsReputation={skladchina.affectsReputation}
          canUnmarkCash={canMarkCash && openParticipant.status === 'payment_confirmed'}
          busy={busy}
          onClose={() => setOpenId(null)}
          onResolvePayment={onResolvePayment}
          onResolveDecline={onResolveDecline}
          onUnmarkCash={onUnmarkCash}
        />
      )}
    </>
  );
};

interface RequestSheetProps {
  participant: SkladchinaParticipantDto;
  affectsReputation: boolean;
  canUnmarkCash: boolean;
  busy: boolean;
  onClose: () => void;
  onResolvePayment: (p: SkladchinaParticipantDto, accept: boolean) => Promise<void>;
  onResolveDecline: (p: SkladchinaParticipantDto, approve: boolean, rejectReason?: string) => Promise<void>;
  onUnmarkCash: (p: SkladchinaParticipantDto) => Promise<void>;
}

/**
 * Шторка заявки: подробности (когда, причина, чек) и действия словами. Здесь живёт всё редкое и
 * серьёзное — «не дошёл», «платежа нет», отклонение просьбы с причиной; принять можно и отсюда,
 * но основной путь принятия — чекбокс и «Обработать».
 */
const RequestSheet: FC<RequestSheetProps> = ({
  participant: p, affectsReputation, canUnmarkCash, busy, onClose, onResolvePayment, onResolveDecline, onUnmarkCash,
}) => {
  const [rejecting, setRejecting] = useState(false);
  const [rejectText, setRejectText] = useState('');
  const name = fullName(p);
  const rub = formatRubles(p.declaredAmountKopecks ?? p.expectedAmountKopecks ?? 0);
  const kind = requestKind(p);

  const run = async (action: () => Promise<void>) => {
    await action();
    onClose();
  };

  const subtitle = (() => {
    switch (kind) {
      case 'claim': return p.paidAt ? `заявил оплату ${WHEN_FMT.format(new Date(p.paidAt))}` : 'заявил оплату';
      case 'receipt': return p.disputedAt ? `чек прислан ${WHEN_FMT.format(new Date(p.disputedAt))}` : 'чек прислан';
      case 'decline': return 'просит отказаться от оплаты';
      default:
        return p.status === 'payment_confirmed' ? 'зачтено'
          : p.status === 'payment_rejected' ? 'платёж не найден — у участника 48 часов на чек'
          : rowStatus(p).text;
    }
  })();

  const penaltyNote = affectsReputation ? ', иначе −40 к надёжности' : '';

  return createPortal(
    <>
      <div className="rd-sheet-overlay rd-overlay-in" onClick={onClose} aria-hidden="true" />
      <div className="rd-sheet rd-sheet-in" role="dialog" aria-modal="true" aria-label={`Заявка: ${name}`}>
        <div className="rd-sheet-grabber" aria-hidden="true" />
        <div className="rd-sheet-body">
          <div className="rd-req-sheet-h">
            <span className="rd-pend-av">
              {p.avatarUrl ? <img src={p.avatarUrl} alt="" /> : getInitials(p.firstName, p.lastName)}
            </span>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="rd-req-sheet-name">{name} · {rub} ₽</div>
              <div className="rd-req-sheet-sub">{subtitle}</div>
            </div>
            <button type="button" className="rd-sheet-close" onClick={onClose}>Закрыть</button>
          </div>

          {kind === 'receipt' && (
            <>
              {p.receiptNote && <div className="rd-req-sheet-quote">«{p.receiptNote}»</div>}
              {p.receiptUrl && (
                <a href={p.receiptUrl} target="_blank" rel="noopener noreferrer">
                  <img src={p.receiptUrl} alt="Чек участника" className="rd-req-sheet-receipt" />
                </a>
              )}
              <button type="button" className="rd-btn-primary" disabled={busy} onClick={() => run(() => onResolvePayment(p, true))}>
                Всё сошлось — засчитать
              </button>
              <button
                type="button"
                className="rd-btn-outline"
                style={{ color: 'var(--danger)', marginTop: 8 }}
                disabled={busy}
                onClick={() => {
                  if (!window.confirm(`Платежа от ${name} нет? Решение окончательное${affectsReputation ? ', спишется 40 очков надёжности' : ''}.`)) return;
                  void run(() => onResolvePayment(p, false));
                }}
              >
                Платежа нет
              </button>
              <div className="rd-cta-hint" style={{ textAlign: 'left', marginTop: 8 }}>
                «Платежа нет» — окончательно{affectsReputation ? ': −40, повторно оспорить нельзя' : ': повторно оспорить нельзя'}.
              </div>
            </>
          )}

          {kind === 'claim' && (
            <>
              <button type="button" className="rd-btn-primary" disabled={busy} onClick={() => run(() => onResolvePayment(p, true))}>
                Засчитать
              </button>
              <button type="button" className="rd-btn-outline" style={{ color: 'var(--danger)', marginTop: 8 }} disabled={busy} onClick={() => run(() => onResolvePayment(p, false))}>
                Не дошёл — попросить чек
              </button>
              <div className="rd-cta-hint" style={{ textAlign: 'left', marginTop: 8 }}>
                У {p.firstName} будет 48 часов приложить чек{penaltyNote}.
              </div>
            </>
          )}

          {kind === 'decline' && (
            <>
              {p.declineNote && <div className="rd-req-sheet-quote">«{p.declineNote}»</div>}
              {rejecting ? (
                <>
                  <textarea
                    className="rd-textarea"
                    rows={2}
                    placeholder="Почему участник должен оплатить (обязательно)"
                    value={rejectText}
                    onChange={(e) => setRejectText(e.target.value)}
                    maxLength={500}
                  />
                  <button
                    type="button"
                    className="rd-btn-primary"
                    style={{ marginTop: 10 }}
                    disabled={busy || !rejectText.trim()}
                    onClick={() => run(() => onResolveDecline(p, false, rejectText))}
                  >
                    Отклонить заявку
                  </button>
                  <button type="button" className="rd-btn-outline" style={{ marginTop: 8 }} onClick={() => { setRejecting(false); setRejectText(''); }}>
                    Отмена
                  </button>
                </>
              ) : (
                <>
                  <button type="button" className="rd-btn-primary" disabled={busy} onClick={() => run(() => onResolveDecline(p, true))}>
                    Одобрить отказ
                  </button>
                  <button type="button" className="rd-btn-outline" style={{ marginTop: 8 }} disabled={busy} onClick={() => setRejecting(true)}>
                    Отклонить — с причиной
                  </button>
                  <div className="rd-cta-hint" style={{ textAlign: 'left', marginTop: 8 }}>
                    Отклонённый обязан оплатить и повторно просить не может.
                  </div>
                </>
              )}
            </>
          )}

          {kind === null && p.status === 'payment_confirmed' && (
            <>
              <button type="button" className="rd-btn-outline" style={{ color: 'var(--danger)' }} disabled={busy} onClick={() => run(() => onResolvePayment(p, false))}>
                Не дошёл — попросить чек
              </button>
              {canUnmarkCash && (
                <button type="button" className="rd-btn-outline" style={{ marginTop: 8 }} disabled={busy} onClick={() => run(() => onUnmarkCash(p))}>
                  Снять отметку
                </button>
              )}
              <div className="rd-cta-hint" style={{ textAlign: 'left', marginTop: 8 }}>
                Очки начисляются только при закрытии сбора — пересмотреть можно без последствий.
              </div>
            </>
          )}

          {kind === null && p.status === 'payment_rejected' && (
            <>
              {p.paymentRejectNote && <div className="rd-req-sheet-quote">Ваша причина: «{p.paymentRejectNote}»</div>}
              <button type="button" className="rd-btn-primary" disabled={busy} onClick={() => run(() => onResolvePayment(p, true))}>
                Засчитать — деньги нашлись
              </button>
              <div className="rd-cta-hint" style={{ textAlign: 'left', marginTop: 8 }}>
                Пришлёт чек — заявка вернётся в работу{affectsReputation ? '; без чека через 48 часов спишется 40 очков' : ''}.
              </div>
            </>
          )}
        </div>
      </div>
    </>,
    document.body,
  );
};

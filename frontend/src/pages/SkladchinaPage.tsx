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
import { ImageLightbox } from '../components/ImageLightbox';
import { DebtRow } from '../components/debt/DebtRow';
import type { SkladchinaDetailDto } from '../types/api';
import { formatRub, rubToKopecks } from '../utils/money';
import { DATE_FMT, KIND_EMOJI, KIND_LABEL, initials, statusLabel } from '../utils/skladchinaKind';

function errorMessage(e: unknown, fallback: string): string {
  if (e instanceof ApiError && (e.status === 400 || e.status === 403 || e.status === 409) && e.message) return e.message;
  return fallback;
}

/** Строка стадии под названием: что сейчас происходит со сбором и когда срок. */
function stageLine(s: SkladchinaDetailDto): string {
  if (s.isEnrolling) {
    return `В деле ${s.enrolledCount}${s.minParticipants ? ` · нужно ${s.minParticipants}` : ''} · отметиться до ${DATE_FMT.format(new Date(s.enrollmentUntil!))}`;
  }
  const parts: string[] = [];
  if (s.kind === 'per_head') parts.push(s.orderedAt ? `куплено ${s.receivedCount} · приём закрыт` : `берут ${s.debtCount} · оплатили ${s.receivedCount}`);
  else parts.push(`оплатили ${s.receivedCount} из ${s.debtCount}`);
  if (s.deadline && s.status === 'active' && !s.orderedAt) {
    const past = new Date(s.deadline).getTime() < Date.now();
    parts.push(past ? `срок вышел · не оплатили ${s.openCount}` : `до ${DATE_FMT.format(new Date(s.deadline))}`);
  }
  return parts.join(' · ');
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
  const [error, setError] = useState<string | null>(null);
  const [amountInput, setAmountInput] = useState('');
  const [noteInput, setNoteInput] = useState('');
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
  const target = s.targetKopecks ?? s.amountKopecks;
  const receivedPct = target && target > 0 ? Math.min(100, Math.round((s.receivedKopecks / target) * 100)) : 0;
  const claimedPct = target && target > 0 ? Math.min(100 - receivedPct, Math.round((s.claimedKopecks / target) * 100)) : 0;

  const run = async (action: SkladchinaAction, done: string, confirmText?: string) => {
    if (confirmText && !window.confirm(confirmText)) return;
    setError(null);
    try {
      haptic.impact('medium');
      await actionMut.mutateAsync({ id: s.id, action });
      haptic.notify('success');
      setToast(done);
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
    void run({ type: 'contribute', amountKopecks: kopecks }, 'Перевод отмечен — создатель подтвердит.').then(() => setAmountInput(''));
  };

  const clubInitials = initials(s.clubName);
  const statusCls = s.status === 'cancelled' ? 'rd-neutral2' : s.status === 'collected' ? 'rd-going' : 'rd-warn';

  return (
    <div className="rd-page">
      <button
        type="button"
        className="rd-glass rd-host-row"
        onClick={() => { haptic.impact('light'); navigate(`/clubs/${s.clubId}`); }}
        aria-label={`Открыть клуб ${s.clubName}`}
        style={{ width: '100%', marginBottom: 14, cursor: 'pointer', fontFamily: 'inherit', textAlign: 'left' }}
      >
        <span className="rd-ico">{s.clubAvatarUrl ? <img src={s.clubAvatarUrl} alt="" /> : clubInitials}</span>
        <div className="rd-info">
          <div className="rd-met">Сбор в клубе · собирает {s.creatorName}</div>
          <div className="rd-ttl">{s.clubName}</div>
        </div>
        <span aria-hidden="true" style={{ color: 'var(--text-faint)', fontSize: 20, lineHeight: 1 }}>›</span>
      </button>

      <div className="rd-ft-eyebrow">{KIND_EMOJI[s.kind]} {KIND_LABEL[s.kind]}</div>
      <h1 className="rd-page-h" style={{ marginBottom: 10 }}>{s.title}</h1>
      <div className="rd-badges-row" style={{ marginBottom: 16 }}>
        <span className={`rd-badge ${statusCls}`}>{statusLabel(s.status)}</span>
        {s.eventId && (
          <button type="button" className="rd-badge rd-neutral2" style={{ cursor: 'pointer', font: 'inherit' }} onClick={() => navigate(`/events/${s.eventId}`)}>
            за встречу «{s.eventTitle}» ›
          </button>
        )}
      </div>

      {s.photoUrl && (
        <button
          type="button"
          className="rd-glass"
          onClick={() => { haptic.impact('light'); setPhotoZoomed(true); }}
          style={{ overflow: 'hidden', padding: 0, marginBottom: 14, border: 'none', cursor: 'pointer', display: 'block', width: '100%' }}
        >
          <img src={s.photoUrl} alt="Фото сбора" style={{ width: '100%', display: 'block' }} />
        </button>
      )}
      <ImageLightbox src={photoZoomed ? s.photoUrl : null} onClose={() => setPhotoZoomed(false)} />

      {/* Прогресс: деньги — главная строка, полоса 🟩 получено / 🟨 говорят, что отдали. */}
      <div className="rd-glass" style={{ padding: 16, marginBottom: 14 }}>
        <div style={{ fontSize: 18, fontWeight: 700, color: 'var(--text)', marginBottom: 10 }}>
          {s.isEnrolling
            ? `${formatRub(s.amountKopecks ?? 0)} на группу, поровну между теми, кто в деле`
            : target && target > 0
              ? `Получено ${formatRub(s.receivedKopecks)} из ${formatRub(target)}`
              : `Получено ${formatRub(s.receivedKopecks)}`}
        </div>
        {!s.isEnrolling && (
          <div className="rd-progress" aria-hidden="true">
            <div className="rd-fill" style={{ width: `${receivedPct}%`, display: 'inline-block' }} />
            <div className="rd-fill rd-fill-claimed" style={{ width: `${claimedPct}%`, display: 'inline-block' }} />
          </div>
        )}
        <div className="rd-sklad-stats">{stageLine(s)}</div>
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

      {!s.isCreator && (
        <>
          <div className="rd-section-sub-h">Реквизиты</div>
          <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
            {s.paymentMethodNote && <div className="rd-body-text" style={{ margin: '0 0 10px', padding: 0 }}>{s.paymentMethodNote}</div>}
            <button type="button" className="rd-btn-primary" onClick={() => { haptic.impact('light'); window.open(s.paymentLink, '_blank', 'noopener,noreferrer'); }}>
              Открыть в банке
            </button>
            <div className="rd-payment-link-text">{s.paymentLink}</div>
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
                <button type="button" className="rd-btn-outline" disabled={busy} onClick={() => run({ type: 'leave' }, 'Вы вышли из списка.')}>Передумал</button>
              </>
            ) : (
              <button type="button" className="rd-btn-primary" disabled={busy} onClick={() => run({ type: 'join' }, 'Вы в деле!')}>В деле</button>
            )
          )}

          {s.kind === 'per_head' && !s.myDebt && !s.orderedAt && (
            <>
              <input
                className="rd-input"
                placeholder="Заметка: размер, вариант (необязательно)"
                value={noteInput}
                onChange={(e) => setNoteInput(e.target.value)}
                maxLength={200}
                style={{ marginBottom: 10 }}
              />
              <button type="button" className="rd-btn-primary" disabled={busy} onClick={() => run({ type: 'join', note: noteInput.trim() || null }, 'Записали за вами.')}>
                Беру
              </button>
            </>
          )}
          {s.kind === 'per_head' && !s.myDebt && s.orderedAt && (
            <div className="rd-debt-meta">Приём закрыт: заказ уже сделан.</div>
          )}

          {s.kind === 'voluntary' && !s.myDebt && (
            <>
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
            </>
          )}

          {s.myDebt && (
            <>
              <div className="rd-section-sub-h" style={{ marginTop: 0 }}>Мой долг</div>
              <DebtRow debt={s.myDebt} viewerId={viewerId} busy={busy} onAction={(a) => runDebt(s.myDebt!.id, a)} />
              {s.kind === 'per_head' && !s.orderedAt && (s.myDebt.status === 'waiting' || s.myDebt.status === 'promised') && (
                <button type="button" className="rd-ghost-btn" disabled={busy} style={{ marginTop: 8 }} onClick={() => run({ type: 'leave' }, 'Вы передумали.')}>
                  Передумал
                </button>
              )}
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

      {/* Создатель тоже может «взять» вещь как все — его долг ляжет сразу received (§ 2.1); своя доля
          в myDebt не попадает, поэтому смотрим список долгов. */}
      {s.isCreator && isActive && s.kind === 'per_head' && !s.orderedAt && !(s.debts ?? []).some((d) => d.debtor.id === viewerId) && (
        <div className="rd-glass" style={{ padding: 16, marginBottom: 14 }}>
          <input
            className="rd-input"
            placeholder="Заметка: размер, вариант (необязательно)"
            value={noteInput}
            onChange={(e) => setNoteInput(e.target.value)}
            maxLength={200}
            style={{ marginBottom: 10 }}
          />
          <button type="button" className="rd-btn-primary" disabled={busy} onClick={() => run({ type: 'join', note: noteInput.trim() || null }, 'Записали и за вами.')}>
            Беру
          </button>
        </div>
      )}

      {/* Создатель: список долгов и кнопки стадии. */}
      {s.isCreator && s.debts && (
        <>
          <div className="rd-section-sub-h">
            {s.kind === 'per_head' ? 'Берут' : 'Кто должен'} <span className="rd-count">· {s.debts.length}</span>
          </div>
          <div className="rd-glass" style={{ padding: '6px 12px', marginBottom: 14 }}>
            {s.debts.length === 0 && <div className="rd-debt-meta" style={{ padding: '10px 4px' }}>Пока никого.</div>}
            {s.debts.map((d) => (
              <DebtRow key={d.id} debt={d} viewerId={viewerId} readOnly={!isActive} busy={busy} onAction={(a) => runDebt(d.id, a)} />
            ))}
          </div>
        </>
      )}

      {isActive && (s.isCreator || s.canCancel) && (
        <div className="rd-form" style={{ marginTop: 4 }}>
          {s.isCreator && s.isEnrolling && (
            <button type="button" className="rd-btn-primary" disabled={busy} onClick={() => run({ type: 'lock' }, 'Список закрыт, долги созданы.', 'Закрыть запись сейчас? Доли посчитаются по тем, кто в деле.')}>
              Закрыть запись
            </button>
          )}
          {s.isCreator && s.kind === 'per_head' && !s.orderedAt && (
            <button
              type="button"
              className="rd-btn-primary"
              disabled={busy}
              onClick={() => run({ type: 'order' }, 'Заказ сделан.', `Заказываю: получено ${s.receivedCount}, не оплатили ${s.openCount - s.claimedCount} — они выбывают.`)}
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
    </div>
  );
};

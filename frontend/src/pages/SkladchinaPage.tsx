import { FC, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import {
  useCloseSkladchinaMutation,
  useDeclineSkladchinaMutation,
  useMarkPaidMutation,
  useSkladchinaQuery,
} from '../queries/skladchina';
import { BrandBackdrop } from '../components/BrandBackdrop';
import { Toast } from '../components/Toast';
import { OrganizerParticipantList } from '../components/skladchina/OrganizerParticipantList';
import type { SkladchinaDetailDto } from '../types/api';

const DEADLINE_FMT = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric',
  month: 'long',
  hour: '2-digit',
  minute: '2-digit',
});

function formatRubles(kopecks: number): string {
  return (Math.floor(kopecks / 100)).toLocaleString('ru-RU');
}

function statusLabel(status: string): string {
  switch (status) {
    case 'active': return 'Активный';
    case 'closed_success': return 'Завершён успешно';
    case 'closed_failed': return 'Закрыт без сбора';
    case 'cancelled': return 'Отменён';
    default: return status;
  }
}

function paymentModeLabel(mode: string): string {
  switch (mode) {
    case 'fixed_equal': return 'Поровну';
    case 'fixed_individual': return 'Индивидуальные суммы';
    case 'voluntary': return 'По желанию';
    default: return mode;
  }
}

export const SkladchinaPage: FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const haptic = useHaptic();
  const query = useSkladchinaQuery(id);
  const markPaidMut = useMarkPaidMutation();
  const declineMut = useDeclineSkladchinaMutation();
  const closeMut = useCloseSkladchinaMutation();

  const [amountInput, setAmountInput] = useState('');
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  if (query.isPending) {
    return (
      <div className="brand-page">
        <BrandBackdrop />
        <div style={{ display: 'flex', justifyContent: 'center', padding: 60 }}>
          <Spinner size="m" />
        </div>
      </div>
    );
  }

  if (query.isError || !query.data) {
    return (
      <div className="brand-page">
        <BrandBackdrop />
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--brand-ink-3)' }}>
          Не удалось загрузить сбор. Попробуйте позже.
        </div>
      </div>
    );
  }

  const s: SkladchinaDetailDto = query.data;
  const isActive = s.status === 'active';
  const isCreator = s.isOrganizerView;
  const isMemberParticipant = s.myStatus !== null;
  const hasGoal = s.totalGoalKopecks != null && s.totalGoalKopecks > 0;
  const percent = hasGoal
    ? Math.min(100, Math.round((s.collectedKopecks / s.totalGoalKopecks!) * 100))
    : null;

  // Mark-paid: prefilled expected for fixed modes, empty for voluntary
  const expectedRub = s.myExpectedAmountKopecks != null
    ? Math.floor(s.myExpectedAmountKopecks / 100)
    : null;
  const prefilledForMode = s.paymentMode === 'voluntary' ? '' : (expectedRub?.toString() ?? '');

  const handleOpenPaymentLink = () => {
    haptic.impact('light');
    window.open(s.paymentLink, '_blank', 'noopener,noreferrer');
  };

  const handleMarkPaid = async () => {
    setActionError(null);
    const valueRaw = (amountInput || prefilledForMode).trim();
    const rub = Number(valueRaw);
    if (!Number.isFinite(rub) || rub <= 0) {
      setActionError('Введите корректную сумму');
      haptic.notify('error');
      return;
    }
    try {
      haptic.impact('medium');
      await markPaidMut.mutateAsync({ id: s.id, declaredAmountKopecks: Math.round(rub * 100) });
      haptic.notify('success');
      setToastMessage('Спасибо! Сбор обновлён.');
      setAmountInput('');
    } catch (e) {
      console.error('markPaid failed', e);
      haptic.notify('error');
      setActionError('Не удалось отметить оплату. Попробуйте ещё раз.');
    }
  };

  const handleDecline = async () => {
    if (!window.confirm('Отказаться от участия в сборе?')) return;
    setActionError(null);
    try {
      haptic.impact('medium');
      await declineMut.mutateAsync(s.id);
      haptic.notify('success');
      setToastMessage('Вы отказались от участия.');
    } catch (e) {
      console.error('decline failed', e);
      haptic.notify('error');
      setActionError('Не удалось отказаться. Попробуйте ещё раз.');
    }
  };

  const handleClose = async () => {
    if (!window.confirm('Закрыть сбор? Дальнейшие оплаты будут невозможны.')) return;
    setActionError(null);
    try {
      haptic.impact('heavy');
      await closeMut.mutateAsync(s.id);
      haptic.notify('success');
      setToastMessage('Сбор закрыт.');
    } catch (e) {
      console.error('close failed', e);
      haptic.notify('error');
      setActionError('Не удалось закрыть сбор.');
    }
  };

  const handleBackToClub = () => {
    haptic.impact('light');
    navigate(`/clubs/${s.clubId}`);
  };

  return (
    <div className="brand-page">
      <BrandBackdrop />

      <header className="sklad-hero">
        <button type="button" className="sklad-club-link" onClick={handleBackToClub}>
          {s.clubName}
        </button>
        <h1 className="sklad-title">{s.title}</h1>
        <div className="sklad-meta">
          <span className={`sklad-status sklad-status-${s.status}`}>{statusLabel(s.status)}</span>
          <span className="sklad-mode">{paymentModeLabel(s.paymentMode)}</span>
        </div>
      </header>

      {s.photoUrl && (
        <div className="sklad-photo">
          <img src={s.photoUrl} alt="Фото сбора" />
        </div>
      )}

      {s.description && <p className="sklad-description">{s.description}</p>}

      {s.rules && (
        <div className="sklad-rules">
          <div className="sklad-block-title">Правила</div>
          <p>{s.rules}</p>
        </div>
      )}

      <div className="sklad-progress-block">
        <div className="sklad-amounts">
          {hasGoal ? (
            <>
              <span className="collected">{formatRubles(s.collectedKopecks)} ₽</span>
              <span className="sep">из</span>
              <span className="goal">{formatRubles(s.totalGoalKopecks!)} ₽</span>
              {percent !== null && <span className="percent">{percent}%</span>}
            </>
          ) : (
            <>
              <span className="collected">{formatRubles(s.collectedKopecks)} ₽</span>
              <span className="voluntary-note">собрано (по желанию)</span>
            </>
          )}
        </div>
        {hasGoal && (
          <div className="progress-bar">
            <div className="fill" style={{ width: `${percent}%` }} />
          </div>
        )}
        <div className="sklad-stats">
          {s.paidCount}/{s.participantCount} оплатили · до {DEADLINE_FMT.format(new Date(s.deadline))}
        </div>
      </div>

      <div className="sklad-payment-block">
        <div className="sklad-block-title">Платёжная ссылка</div>
        {s.paymentMethodNote && <div className="payment-note">{s.paymentMethodNote}</div>}
        <button type="button" className="payment-link-btn" onClick={handleOpenPaymentLink}>
          Открыть в банке
        </button>
        <div className="payment-link-text">{s.paymentLink}</div>
      </div>

      {isActive && isMemberParticipant && s.myStatus === 'pending' && (
        <div className="sklad-action-block">
          <div className="sklad-block-title">Подтвердите оплату</div>
          {s.myExpectedAmountKopecks != null && (
            <div className="expected-note">
              Ожидаемая сумма: {formatRubles(s.myExpectedAmountKopecks)} ₽
            </div>
          )}
          <div className="amount-row">
            <input
              type="number"
              inputMode="decimal"
              min="1"
              step="1"
              placeholder={prefilledForMode || 'Сумма, ₽'}
              value={amountInput}
              onChange={(e) => setAmountInput(e.target.value)}
              readOnly={s.paymentMode !== 'voluntary' && expectedRub !== null}
              className="amount-input"
            />
            <span className="suffix">₽</span>
          </div>
          {actionError && <div className="action-error">{actionError}</div>}
          <div className="action-row">
            <button
              type="button"
              className="primary-btn"
              onClick={handleMarkPaid}
              disabled={markPaidMut.isPending}
            >
              {markPaidMut.isPending ? 'Сохраняем…' : 'Я оплатил'}
            </button>
            <button
              type="button"
              className="ghost-btn"
              onClick={handleDecline}
              disabled={declineMut.isPending}
            >
              Отказаться
            </button>
          </div>
        </div>
      )}

      {isMemberParticipant && s.myStatus !== 'pending' && (
        <div className="sklad-my-status">
          {s.myStatus === 'paid' && (
            <>
              <div className="title">Вы оплатили</div>
              {s.myDeclaredAmountKopecks != null && (
                <div className="sub">{formatRubles(s.myDeclaredAmountKopecks)} ₽</div>
              )}
            </>
          )}
          {s.myStatus === 'declined' && (
            <div className="title">Вы отказались от участия</div>
          )}
          {s.myStatus === 'expired_no_response' && (
            <div className="title">Срок истёк, оплата не зарегистрирована</div>
          )}
        </div>
      )}

      {isCreator && s.participants && (
        <OrganizerParticipantList
          participants={s.participants}
          totalGoalKopecks={s.totalGoalKopecks}
        />
      )}

      {isActive && isCreator && (
        <div className="sklad-action-block">
          <button
            type="button"
            className="ghost-btn"
            onClick={handleClose}
            disabled={closeMut.isPending}
          >
            {closeMut.isPending ? 'Закрываем…' : 'Закрыть сбор'}
          </button>
          {actionError && <div className="action-error">{actionError}</div>}
        </div>
      )}

      {toastMessage && <Toast message={toastMessage} onClose={() => setToastMessage(null)} />}
    </div>
  );
};

import { FC, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useSetClubContext } from '../store/useClubContextStore';
import {
  useCloseSkladchinaMutation,
  useDeclineSkladchinaMutation,
  useMarkPaidMutation,
  useSkladchinaQuery,
} from '../queries/skladchina';
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
  useBackButton(true);
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const haptic = useHaptic();
  const query = useSkladchinaQuery(id);
  useSetClubContext(query.data?.clubId);
  const markPaidMut = useMarkPaidMutation();
  const declineMut = useDeclineSkladchinaMutation();
  const closeMut = useCloseSkladchinaMutation();

  const [amountInput, setAmountInput] = useState('');
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  if (query.isPending) {
    return (
      <div className="rd-page">
        <div className="rd-spinner-row" style={{ paddingTop: 60 }}>
          <Spinner size="m" />
        </div>
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

  const statusCls =
    s.status === 'closed_failed' ? 'rd-decline'
    : s.status === 'cancelled' ? 'rd-neutral2'
    : 'rd-going';

  return (
    <div className="rd-page">
      <button
        type="button"
        className="rd-glass rd-host-row"
        onClick={handleBackToClub}
        aria-label={`Открыть клуб ${s.clubName}`}
        style={{ width: '100%', marginBottom: 14, cursor: 'pointer', fontFamily: 'inherit', textAlign: 'left' }}
      >
        <span className="rd-ico">
          {s.clubAvatarUrl
            ? <img src={s.clubAvatarUrl} alt="" />
            : s.clubName.split(/\s+/).slice(0, 2).map((w) => w.charAt(0).toUpperCase()).join('')}
        </span>
        <div className="rd-info">
          <div className="rd-met">Сбор в клубе</div>
          <div className="rd-ttl">{s.clubName}</div>
        </div>
        <span aria-hidden="true" style={{ color: 'var(--text-faint)', fontSize: 20, lineHeight: 1 }}>›</span>
      </button>

      <div className="rd-ft-eyebrow">Сбор</div>
      <h1 className="rd-page-h" style={{ marginBottom: 10 }}>{s.title}</h1>
      <div className="rd-badges-row" style={{ marginBottom: 16 }}>
        <span className={`rd-badge ${statusCls}`}>{statusLabel(s.status)}</span>
        <span className="rd-badge rd-neutral2">{paymentModeLabel(s.paymentMode)}</span>
        {s.affectsReputation && (
          <span className="rd-badge rd-warn" title="Этот сбор влияет на репутацию">⚠️ С репутацией</span>
        )}
      </div>

      {s.photoUrl && (
        <div className="rd-glass" style={{ overflow: 'hidden', padding: 0, marginBottom: 14 }}>
          <img src={s.photoUrl} alt="Фото сбора" style={{ width: '100%', display: 'block' }} />
        </div>
      )}

      {s.description && (
        <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
          <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{s.description}</div>
        </div>
      )}

      {s.rules && (
        <>
          <div className="rd-section-sub-h">Правила</div>
          <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
            <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{s.rules}</div>
          </div>
        </>
      )}

      <div className="rd-glass" style={{ padding: 16, marginBottom: 14 }}>
        <div className="rd-amounts">
          {hasGoal ? (
            <>
              <span className="rd-collected">{formatRubles(s.collectedKopecks)} ₽</span>
              <span className="rd-sep">из</span>
              <span className="rd-goal">{formatRubles(s.totalGoalKopecks!)} ₽</span>
              {percent !== null && <span className="rd-pct">{percent}%</span>}
            </>
          ) : (
            <>
              <span className="rd-collected">{formatRubles(s.collectedKopecks)} ₽</span>
              <span className="rd-vol-note">собрано (по желанию)</span>
            </>
          )}
        </div>
        {hasGoal && (
          <div className="rd-progress">
            <div className="rd-fill" style={{ width: `${percent}%` }} />
          </div>
        )}
        <div className="rd-sklad-stats">
          {s.paidCount}/{s.participantCount} оплатили · до {DEADLINE_FMT.format(new Date(s.deadline))}
        </div>
      </div>

      <div className="rd-section-sub-h">Платёжная ссылка</div>
      <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
        {s.paymentMethodNote && (
          <div className="rd-body-text" style={{ margin: '0 0 10px', padding: 0 }}>{s.paymentMethodNote}</div>
        )}
        <button type="button" className="rd-btn-primary" onClick={handleOpenPaymentLink}>
          Открыть в банке
        </button>
        <div className="rd-payment-link-text">{s.paymentLink}</div>
      </div>

      {isActive && isMemberParticipant && s.myStatus === 'pending' && (
        <div className="rd-glass" style={{ padding: 16, marginBottom: 14 }}>
          <div className="rd-section-sub-h" style={{ marginTop: 0 }}>Подтвердите оплату</div>
          {s.myExpectedAmountKopecks != null && (
            <div className="rd-hint" style={{ marginBottom: 10 }}>
              Ожидаемая сумма: {formatRubles(s.myExpectedAmountKopecks)} ₽
            </div>
          )}
          <div style={{ position: 'relative', marginBottom: 10 }}>
            <input
              type="number"
              inputMode="decimal"
              min="1"
              step="1"
              placeholder={prefilledForMode || 'Сумма, ₽'}
              value={amountInput}
              onChange={(e) => setAmountInput(e.target.value)}
              readOnly={s.paymentMode !== 'voluntary' && expectedRub !== null}
              className="rd-input"
              style={{ paddingRight: 32 }}
            />
            <span style={{ position: 'absolute', right: 14, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-faint)' }}>₽</span>
          </div>
          {actionError && <div className="rd-error">{actionError}</div>}
          <div className="rd-form-actions">
            <button
              type="button"
              className="rd-btn-primary"
              onClick={handleMarkPaid}
              disabled={markPaidMut.isPending}
            >
              {markPaidMut.isPending ? 'Сохраняем…' : 'Я оплатил'}
            </button>
            <button
              type="button"
              className="rd-btn-outline"
              onClick={handleDecline}
              disabled={declineMut.isPending}
            >
              Отказаться
            </button>
          </div>
        </div>
      )}

      {isMemberParticipant && s.myStatus !== 'pending' && (
        <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
          {s.myStatus === 'paid' && (
            <>
              <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--text)' }}>Вы оплатили</div>
              {s.myDeclaredAmountKopecks != null && (
                <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 2 }}>
                  {formatRubles(s.myDeclaredAmountKopecks)} ₽
                </div>
              )}
            </>
          )}
          {s.myStatus === 'declined' && (
            <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--text)' }}>Вы отказались от участия</div>
          )}
          {s.myStatus === 'expired_no_response' && (
            <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--text)' }}>Срок истёк, оплата не зарегистрирована</div>
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
        <div style={{ marginTop: 4 }}>
          <button
            type="button"
            className="rd-btn-outline"
            onClick={handleClose}
            disabled={closeMut.isPending}
            style={{ color: 'var(--danger)' }}
          >
            {closeMut.isPending ? 'Закрываем…' : 'Закрыть сбор'}
          </button>
          {actionError && <div className="rd-error" style={{ marginTop: 8 }}>{actionError}</div>}
        </div>
      )}

      {toastMessage && <Toast message={toastMessage} onClose={() => setToastMessage(null)} />}
    </div>
  );
};

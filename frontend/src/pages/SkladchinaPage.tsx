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
  useOrganizerMarkPaidMutation,
  useOrganizerUnmarkMutation,
  useRequestDeclineMutation,
  useResolveDeclineMutation,
  useSkladchinaQuery,
} from '../queries/skladchina';
import { Toast } from '../components/Toast';
import { ImageLightbox } from '../components/ImageLightbox';
import { OrganizerParticipantList } from '../components/skladchina/OrganizerParticipantList';
import type { SkladchinaDetailDto, SkladchinaParticipantDto } from '../types/api';

// Формат отображения дедлайна сбора: «5 июля, 18:30» (день + месяц + время, ru-RU).
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
    case 'voluntary': return 'Ваша сумма';
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
  const orgMarkMut = useOrganizerMarkPaidMutation();
  const orgUnmarkMut = useOrganizerUnmarkMutation();
  const requestDeclineMut = useRequestDeclineMutation();
  const resolveDeclineMut = useResolveDeclineMutation();

  const [amountInput, setAmountInput] = useState('');
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [showDeclineForm, setShowDeclineForm] = useState(false);
  const [declineReason, setDeclineReason] = useState('');
  const [photoZoomed, setPhotoZoomed] = useState(false);

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
  const isFixed = s.paymentMode !== 'voluntary';
  const hasGoal = s.totalGoalKopecks != null && s.totalGoalKopecks > 0;

  // A-5: заглавная метрика — прогресс по людям; деньги — декоративная строка под ней.
  const peoplePercent = s.participantCount > 0
    ? Math.round((s.paidCount / s.participantCount) * 100)
    : 0;
  // Сплит «Каждый сам» (voluntary + цель): доли неравные, поэтому осмысленный сигнал — именно деньги
  // к цели: бар заполняется рублями, и в заголовке первыми идут деньги, а не люди.
  const moneyPercent = hasGoal
    ? Math.min(100, Math.round((s.collectedKopecks / s.totalGoalKopecks!) * 100))
    : 0;
  const useMoneyBar = s.paymentMode === 'voluntary' && hasGoal;
  // #3: последний ещё-pending участник voluntary-сбора видит в подсказке поля суммы ровно остаток
  // до цели — и может закрыть сбор одним платежом.
  const remainingToGoalKopecks = hasGoal ? Math.max(0, s.totalGoalKopecks! - s.collectedKopecks) : 0;
  const isLastPending = s.paymentMode === 'voluntary' && hasGoal && s.myStatus === 'pending' && s.pendingCount === 1;

  // A-1: fixed-режимы показывают кнопку в один тап «Я оплатил {доля} ₽» — сервер записывает долю сам.
  const expectedRub = s.myExpectedAmountKopecks != null
    ? Math.floor(s.myExpectedAmountKopecks / 100)
    : null;

  // A-2: строка какого участника сейчас в процессе мутации (её кнопки дизейблим).
  const busyUserId = (
    orgMarkMut.isPending ? orgMarkMut.variables?.userId
    : orgUnmarkMut.isPending ? orgUnmarkMut.variables?.userId
    : resolveDeclineMut.isPending ? resolveDeclineMut.variables?.userId
    : undefined
  ) ?? null;
  const canManagePayments = isActive && isCreator && isFixed;

  const handleOpenPaymentLink = () => {
    haptic.impact('light');
    window.open(s.paymentLink, '_blank', 'noopener,noreferrer');
  };

  const handleMarkPaid = async () => {
    setActionError(null);
    // A-1: fixed-режимы сумму не шлют (сервер записывает назначенную долю); voluntary парсит ввод.
    let declaredAmountKopecks: number | null = null;
    if (!isFixed) {
      const rub = Number(amountInput.trim());
      if (!Number.isFinite(rub) || rub <= 0) {
        setActionError('Введите корректную сумму');
        haptic.notify('error');
        return;
      }
      declaredAmountKopecks = Math.round(rub * 100);
    }
    try {
      haptic.impact('medium');
      await markPaidMut.mutateAsync({ id: s.id, declaredAmountKopecks });
      haptic.notify('success');
      setToastMessage('Спасибо! Сбор обновлён.');
      setAmountInput('');
    } catch (e) {
      console.error('markPaid failed', e);
      haptic.notify('error');
      setActionError('Не удалось отметить оплату. Попробуйте ещё раз.');
    }
  };

  const participantName = (p: SkladchinaParticipantDto) =>
    `${p.firstName}${p.lastName ? ` ${p.lastName}` : ''}`;

  const handleOrgMarkPaid = async (p: SkladchinaParticipantDto) => {
    if (!window.confirm(`Отметить, что ${participantName(p)} оплатил(а)? Деньги получены наличными или переводом.`)) return;
    setActionError(null);
    try {
      haptic.impact('medium');
      await orgMarkMut.mutateAsync({ id: s.id, userId: p.userId });
      haptic.notify('success');
      setToastMessage('Оплата отмечена.');
    } catch (e) {
      console.error('organizer mark-paid failed', e);
      haptic.notify('error');
      setActionError('Не удалось отметить оплату. Попробуйте ещё раз.');
    }
  };

  const handleOrgUnmark = async (p: SkladchinaParticipantDto) => {
    const warn = s.affectsReputation
      ? `Снять отметку оплаты у ${participantName(p)}? Участник вернётся в «ожидает» — в важном сборе это снова подставит его под −40 за молчание до дедлайна.`
      : `Снять отметку оплаты у ${participantName(p)}? Участник вернётся в «ожидает».`;
    if (!window.confirm(warn)) return;
    setActionError(null);
    try {
      haptic.impact('medium');
      await orgUnmarkMut.mutateAsync({ id: s.id, userId: p.userId });
      haptic.notify('success');
      setToastMessage('Отметка снята.');
    } catch (e) {
      console.error('organizer unmark failed', e);
      haptic.notify('error');
      setActionError('Не удалось снять отметку. Попробуйте ещё раз.');
    }
  };

  // V28: шаблоны с одобрением отказа (split_bill) открывают форму причины вместо мгновенного отказа.
  const handleDeclineClick = () => {
    setActionError(null);
    if (s.declineRequiresApproval) {
      setShowDeclineForm(true);
      return;
    }
    void handleInstantDecline();
  };

  const handleInstantDecline = async () => {
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

  const handleRequestDecline = async () => {
    const reason = declineReason.trim();
    if (!reason) {
      setActionError('Укажите причину отказа');
      haptic.notify('error');
      return;
    }
    setActionError(null);
    try {
      haptic.impact('medium');
      await requestDeclineMut.mutateAsync({ id: s.id, reason });
      haptic.notify('success');
      setShowDeclineForm(false);
      setDeclineReason('');
      setToastMessage('Запрос на отказ отправлен организатору.');
    } catch (e) {
      console.error('requestDecline failed', e);
      haptic.notify('error');
      setActionError('Не удалось отправить запрос. Попробуйте ещё раз.');
    }
  };

  // Approve: простой confirm. Reject (#7): причину собирает inline-форма в строке участника и передаёт
  // сюда — организатор обязан обосновать, почему участник всё-таки должен оплатить.
  const handleResolveDecline = async (p: SkladchinaParticipantDto, approve: boolean, rejectReason?: string) => {
    if (approve) {
      const who = `${p.firstName}${p.lastName ? ` ${p.lastName}` : ''}`;
      if (!window.confirm(`Одобрить отказ ${who}? Участник будет освобождён от оплаты.`)) return;
    } else if (!rejectReason || !rejectReason.trim()) {
      setActionError('Укажите причину, по которой участник должен оплатить');
      return;
    }
    setActionError(null);
    try {
      haptic.impact('medium');
      await resolveDeclineMut.mutateAsync({ id: s.id, userId: p.userId, approve, rejectReason: rejectReason?.trim() });
      haptic.notify('success');
      setToastMessage(approve ? 'Отказ одобрен.' : 'Отказ отклонён.');
    } catch (e) {
      console.error('resolveDecline failed', e);
      haptic.notify('error');
      setActionError('Не удалось обработать заявку. Попробуйте ещё раз.');
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
          <span className="rd-badge rd-warn" title="Важный сбор: влияет на репутацию участников">⚠️ Важный сбор</span>
        )}
        {s.template === 'split_bill' && s.eventId && (
          <button
            type="button"
            className="rd-badge rd-neutral2"
            style={{ cursor: 'pointer', border: 'none', font: 'inherit' }}
            onClick={() => { haptic.impact('light'); navigate(`/events/${s.eventId}`); }}
          >
            🧾 Счёт по событию ›
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
        <div style={{ fontSize: 18, fontWeight: 700, color: 'var(--text)', marginBottom: 10 }}>
          {useMoneyBar
            ? `Собрано ${formatRubles(s.collectedKopecks)} ₽ из ${formatRubles(s.totalGoalKopecks!)} ₽`
            : `Скинулись ${s.paidCount} из ${s.participantCount}`}
        </div>
        <div className="rd-progress">
          <div className="rd-fill" style={{ width: `${useMoneyBar ? moneyPercent : peoplePercent}%` }} />
        </div>
        <div className="rd-sklad-stats">
          {useMoneyBar
            ? `Внесли ${s.paidCount} из ${s.participantCount}`
            : hasGoal
              ? `Собрано ${formatRubles(s.collectedKopecks)} ₽ из ${formatRubles(s.totalGoalKopecks!)} ₽`
              : `Собрано ${formatRubles(s.collectedKopecks)} ₽`}
          {' · до '}{DEADLINE_FMT.format(new Date(s.deadline))}
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

          {/* A-1: в fixed-режимах поля суммы нет; voluntary сохраняет ввод. */}
          {!isFixed && (
            <div style={{ position: 'relative', marginBottom: 10 }}>
              <input
                type="number"
                inputMode="decimal"
                min="1"
                step="1"
                placeholder={isLastPending ? `Осталось закрыть ${formatRubles(remainingToGoalKopecks)} ₽` : 'Ваша сумма, ₽'}
                value={amountInput}
                onChange={(e) => setAmountInput(e.target.value)}
                className="rd-input"
                style={{ paddingRight: 32 }}
              />
              <span style={{ position: 'absolute', right: 14, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-faint)' }}>₽</span>
            </div>
          )}
          {actionError && <div className="rd-error">{actionError}</div>}
          {s.affectsReputation && (
            <div className="rd-warn-block">
              Это важный сбор. Оплатите или откажитесь до{' '}
              {DEADLINE_FMT.format(new Date(s.deadline))}: молчание снизит репутацию на 40
            </div>
          )}

          {/* V28: состояния отказа-с-одобрением */}
          {s.myDeclineRejected && (
            <div className="rd-warn-block" style={{ marginBottom: 10 }}>
              Запрос на отказ отклонён — нужно оплатить счёт.
              {s.myDeclineRejectNote && <div style={{ marginTop: 4 }}>Причина: «{s.myDeclineRejectNote}»</div>}
            </div>
          )}
          {s.myDeclineRequested && !s.myDeclineRejected && (
            <div className="rd-hint" style={{ marginBottom: 10 }}>
              ⏳ Запрос на отказ отправлен — ждём решения организатора.
            </div>
          )}

          {showDeclineForm ? (
            <div>
              <textarea
                className="rd-textarea"
                rows={3}
                placeholder="Причина отказа (обязательно)"
                value={declineReason}
                onChange={(e) => setDeclineReason(e.target.value)}
                maxLength={500}
              />
              <div className="rd-form-actions" style={{ marginTop: 10 }}>
                <button
                  type="button"
                  className="rd-btn-primary"
                  onClick={handleRequestDecline}
                  disabled={requestDeclineMut.isPending}
                >
                  {requestDeclineMut.isPending ? 'Отправляем…' : 'Отправить запрос'}
                </button>
                <button
                  type="button"
                  className="rd-btn-outline"
                  onClick={() => { setShowDeclineForm(false); setActionError(null); }}
                >
                  Отмена
                </button>
              </div>
            </div>
          ) : (
            <div className="rd-form-actions">
              <button
                type="button"
                className="rd-btn-primary"
                onClick={handleMarkPaid}
                disabled={markPaidMut.isPending}
              >
                {markPaidMut.isPending
                  ? 'Сохраняем…'
                  : isFixed && expectedRub != null
                    ? `Я оплатил ${expectedRub.toLocaleString('ru-RU')} ₽`
                    : 'Я оплатил'}
              </button>
              {!s.myDeclineRequested && !s.myDeclineRejected && (
                <button
                  type="button"
                  className="rd-btn-outline"
                  onClick={handleDeclineClick}
                  disabled={declineMut.isPending}
                >
                  {s.declineRequiresApproval ? 'Запросить отказ' : 'Отказаться'}
                </button>
              )}
            </div>
          )}
        </div>
      )}

      {isMemberParticipant && s.myStatus !== 'pending' && (
        <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
          {s.myStatus === 'paid' && (
            <>
              <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--success, #22a06b)', display: 'flex', alignItems: 'center', gap: 6 }}>
                <span aria-hidden>✅</span> Вы оплатили
              </div>
              {s.myDeclaredAmountKopecks != null && (
                <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 2 }}>
                  {formatRubles(s.myDeclaredAmountKopecks)} ₽
                </div>
              )}
              {s.affectsReputation && (
                <div style={{ fontSize: 12, color: 'var(--success, #22a06b)', marginTop: 4 }}>
                  + к репутации — засчитается при закрытии сбора
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
          {s.myStatus === 'released' && (
            <>
              <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--text)' }}>
                Сбор закрыли досрочно — ваш ответ не потребовался
              </div>
              <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 2 }}>
                Репутация не изменилась
              </div>
            </>
          )}
        </div>
      )}

      {isCreator && s.participants && (
        <OrganizerParticipantList
          participants={s.participants}
          totalGoalKopecks={s.totalGoalKopecks}
          canManagePayments={canManagePayments}
          busyUserId={busyUserId}
          onMarkPaid={handleOrgMarkPaid}
          onUnmark={handleOrgUnmark}
          onResolveDecline={handleResolveDecline}
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

import { FC, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { ApiError } from '../api/apiClient';
import { useClubEventsQuery, useEventQuery, useEventRespondersQuery } from '../queries/events';
import { useCreateSkladchinaMutation } from '../queries/skladchina';
import { PhotoAttach } from '../components/PhotoAttach';
import type { CreateSkladchinaRequest } from '../types/api';

const DATE_FMT = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit',
});

function rubToKopecks(rub: string): number | null {
  const v = Number(rub.replace(',', '.').trim());
  if (!Number.isFinite(v) || v <= 0) return null;
  return Math.round(v * 100);
}

// split_bill preset: deadline +48h (the bill isn't on fire).
function defaultDeadlineLocal(): string {
  const d = new Date();
  d.setDate(d.getDate() + 2);
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
}

function createErrorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if ((e.status === 400 || e.status === 404 || e.status === 409) && e.message) return e.message;
    if (e.status === 429) return 'Слишком много запросов. Подождите немного и попробуйте снова.';
  }
  return 'Не удалось создать сбор. Проверьте поля и попробуйте снова.';
}

/**
 * "Разделить счёт" (split_bill) creation — entry-agnostic. Reached either from a finished event
 * ("🧾 Разделить счёт" → `?eventId=`) or from the "+" template picker (no eventId → pick a past
 * event here). Participants come from organizer-marked attendance on the backend; this form only
 * needs the bill, the payment link and the deadline.
 */
export const CreateSplitBillPage: FC = () => {
  useBackButton(true);
  const { id: clubId } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const haptic = useHaptic();
  const createMut = useCreateSkladchinaMutation();

  const [selectedEventId, setSelectedEventId] = useState<string | null>(searchParams.get('eventId'));
  const [mode, setMode] = useState<'fixed_equal' | 'voluntary'>('fixed_equal');
  const [title, setTitle] = useState('');
  const [billRub, setBillRub] = useState('');
  const [paymentLink, setPaymentLink] = useState('');
  const [paymentMethodNote, setPaymentMethodNote] = useState('');
  const [photoUrl, setPhotoUrl] = useState<string | null>(null);
  const [deadline, setDeadline] = useState(defaultDeadlineLocal());
  const [submitError, setSubmitError] = useState<string | null>(null);

  const completedQuery = useClubEventsQuery(clubId, { status: 'completed' });
  const eventQuery = useEventQuery(selectedEventId ?? undefined);
  const respondersQuery = useEventRespondersQuery(selectedEventId ?? undefined);

  if (!clubId) {
    return (
      <div className="rd-page">
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--text-dim)' }}>Клуб не найден</div>
      </div>
    );
  }

  const attendedCount = (respondersQuery.data ?? []).filter((r) => r.attendance === 'attended').length;
  const billKopecks = rubToKopecks(billRub);
  const perPersonRub = billKopecks != null && attendedCount > 0
    ? Math.round(billKopecks / attendedCount / 100)
    : null;
  const eventTitle = eventQuery.data?.title ?? '';
  const defaultTitle = eventTitle ? `Счёт: ${eventTitle}` : 'Счёт за событие';

  const fail = (msg: string) => {
    haptic.notify('error');
    setSubmitError(msg);
  };

  const handlePickEvent = (eventId: string) => {
    haptic.select();
    setSelectedEventId(eventId);
    setSubmitError(null);
  };

  const handleSubmit = async () => {
    setSubmitError(null);
    if (!selectedEventId) return fail('Выберите событие');
    if (attendedCount < 2) return fail('Нужно минимум 2 пришедших. Отметьте явку на событии.');
    if (!paymentLink.trim()) return fail('Укажите платёжную ссылку');
    const total = rubToKopecks(billRub);
    if (total === null) return fail('Укажите сумму чека (₽)');

    const body: CreateSkladchinaRequest = {
      title: (title.trim() || defaultTitle).slice(0, 255),
      template: 'split_bill',
      eventId: selectedEventId,
      paymentMode: mode,
      totalGoalKopecks: total,
      paymentLink: paymentLink.trim(),
      paymentMethodNote: paymentMethodNote.trim() || null,
      photoUrl,
      deadline: new Date(deadline).toISOString(),
      affectsReputation: false,
      participants: [],
    };

    try {
      haptic.impact('medium');
      const created = await createMut.mutateAsync({ clubId, body });
      haptic.notify('success');
      navigate(`/skladchina/${created.id}`, { replace: true });
    } catch (e) {
      console.error('createSplitBill failed', e);
      haptic.notify('error');
      setSubmitError(createErrorMessage(e));
    }
  };

  // --- Step 1: pick a past event (only when none was passed in) ---
  if (!selectedEventId) {
    const events = completedQuery.data?.content ?? [];
    return (
      <div className="rd-page">
        <div className="rd-ft-eyebrow">Разделить счёт</div>
        <h1 className="rd-page-h" style={{ marginBottom: 18 }}>Выберите событие</h1>
        {completedQuery.isPending && <Spinner size="s" />}
        {!completedQuery.isPending && events.length === 0 && (
          <div className="rd-hint">Нет прошедших событий. Счёт делится по событию, на котором отмечена явка.</div>
        )}
        {events.length > 0 && (
          <div className="rd-pick-list">
            {events.map((ev) => (
              <button
                key={ev.id}
                type="button"
                className="rd-pick-toggle"
                onClick={() => handlePickEvent(ev.id)}
                style={{ width: '100%' }}
              >
                <span className="rd-pick-name">{ev.title}</span>
                <span className="rd-pick-note">{DATE_FMT.format(new Date(ev.eventDatetime))}</span>
              </button>
            ))}
          </div>
        )}
      </div>
    );
  }

  // --- Step 2: the bill form for the chosen event ---
  const attendanceLoading = eventQuery.isPending || respondersQuery.isPending;
  const notEnoughAttended = !attendanceLoading && attendedCount < 2;

  return (
    <div className="rd-page">
      <div className="rd-ft-eyebrow">Разделить счёт</div>
      <h1 className="rd-page-h" style={{ marginBottom: 14 }}>{eventTitle || 'Сбор по событию'}</h1>

      {attendanceLoading ? (
        <div className="rd-spinner-row" style={{ paddingTop: 20 }}><Spinner size="s" /></div>
      ) : (
        <div className="rd-form">
          <div className="rd-glass" style={{ padding: '12px 16px', marginBottom: 4 }}>
            <div className="rd-sklad-stats">Пришли: {attendedCount} {attendedCount === 1 ? 'человек' : 'чел.'}</div>
            {notEnoughAttended && (
              <div className="rd-warn-block" style={{ marginTop: 8 }}>
                Нужно минимум 2 пришедших. Отметьте явку на событии, потом делите счёт.
              </div>
            )}
          </div>

          <div className="rd-field">
            <span className="rd-label">Как делим <span className="rd-req">*</span></span>
            <div className="rd-mode-list">
              <label className={mode === 'fixed_equal' ? 'rd-mode-option rd-active' : 'rd-mode-option'}>
                <input
                  type="radio"
                  name="split-mode"
                  checked={mode === 'fixed_equal'}
                  onChange={() => { haptic.select(); setMode('fixed_equal'); }}
                />
                <div>
                  <div className="rd-mo-title">Поровну</div>
                  <div className="rd-mo-desc">Сумма чека делится на всех пришедших поровну</div>
                </div>
              </label>
              <label className={mode === 'voluntary' ? 'rd-mode-option rd-active' : 'rd-mode-option'}>
                <input
                  type="radio"
                  name="split-mode"
                  checked={mode === 'voluntary'}
                  onChange={() => { haptic.select(); setMode('voluntary'); }}
                />
                <div>
                  <div className="rd-mo-title">Каждый сам</div>
                  <div className="rd-mo-desc">Каждый вводит свою сумму при оплате; собираем до цели</div>
                </div>
              </label>
            </div>
          </div>

          <label className="rd-field">
            <span className="rd-label">Название</span>
            <input
              className="rd-input"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder={defaultTitle}
              maxLength={255}
            />
          </label>

          <label className="rd-field">
            <span className="rd-label">
              {mode === 'voluntary' ? 'Сумма чека — цель (₽)' : 'Сумма чека (₽)'} <span className="rd-req">*</span>
            </span>
            <input
              className="rd-input"
              type="number"
              inputMode="decimal"
              min="1"
              value={billRub}
              onChange={(e) => setBillRub(e.target.value)}
              placeholder="Например, 4000"
            />
            {mode === 'fixed_equal' && perPersonRub != null && attendedCount >= 2 && (
              <span className="rd-hint">≈ по {perPersonRub.toLocaleString('ru-RU')} ₽ с каждого ({attendedCount} чел.)</span>
            )}
            {mode === 'voluntary' && (
              <span className="rd-hint">Прогресс-бар заполняется до этой суммы; каждый вносит свою часть сам</span>
            )}
          </label>

          <label className="rd-field">
            <span className="rd-label">Платёжная ссылка <span className="rd-req">*</span></span>
            <input
              className="rd-input"
              value={paymentLink}
              onChange={(e) => setPaymentLink(e.target.value)}
              placeholder="https://www.tinkoff.ru/cf/…"
            />
            <span className="rd-hint">⚠️ Ссылка будет видна всем участникам сбора</span>
          </label>

          <label className="rd-field">
            <span className="rd-label">Банк / способ (опц.)</span>
            <input
              className="rd-input"
              value={paymentMethodNote}
              onChange={(e) => setPaymentMethodNote(e.target.value)}
              placeholder="Тинькофф, СБП, ВТБ…"
            />
          </label>

          <div className="rd-field">
            <span className="rd-label">Чек / фото (опц.)</span>
            <PhotoAttach value={photoUrl} onChange={setPhotoUrl} addLabel="Прикрепить чек" />
            <span className="rd-hint">Участники откроют чек на весь экран и посчитают свою часть</span>
          </div>

          <label className="rd-field">
            <span className="rd-label">Срок до <span className="rd-req">*</span></span>
            <input
              className="rd-input"
              type="datetime-local"
              value={deadline}
              onChange={(e) => setDeadline(e.target.value)}
            />
          </label>

          {submitError && <div className="rd-error">{submitError}</div>}

          <div className="rd-form-actions">
            <button type="button" className="rd-btn-outline" onClick={() => navigate(-1)}>Отмена</button>
            <button
              type="button"
              className="rd-btn-primary"
              onClick={handleSubmit}
              disabled={createMut.isPending || notEnoughAttended}
            >
              {createMut.isPending ? 'Создаём…' : 'Создать сбор'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

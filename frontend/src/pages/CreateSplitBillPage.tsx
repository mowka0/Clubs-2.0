import { FC, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { ApiError } from '../api/apiClient';
import { useEventQuery, useEventRespondersQuery } from '../queries/events';
import { useCreateSkladchinaMutation, useSplittableEventsQuery } from '../queries/skladchina';
import { PhotoAttach } from '../components/PhotoAttach';
import { useAuthStore } from '../store/useAuthStore';
import type { CreateSkladchinaRequest } from '../types/api';

const DATE_FMT = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit',
});

function rubToKopecks(rub: string): number | null {
  const v = Number(rub.replace(',', '.').trim());
  if (!Number.isFinite(v) || v <= 0) return null;
  return Math.round(v * 100);
}

// Пресет для split_bill: срок +48ч (счёт не горит).
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
 * Создание "Разделить счёт" (split_bill) — не зависит от точки входа. Попасть сюда можно либо
 * с завершённого события ("🧾 Разделить счёт" → `?eventId=`), либо из пикера шаблонов "+"
 * (без eventId → выбор прошедшего события здесь). Участники берутся из явки, отмеченной
 * организатором на бэкенде; этой форме нужны только сумма чека, платёжная ссылка и срок.
 */
export const CreateSplitBillPage: FC = () => {
  useBackButton(true);
  const { id: clubId } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const haptic = useHaptic();
  const createMut = useCreateSkladchinaMutation();

  const myId = useAuthStore((s) => s.user?.id);
  const [selectedEventId, setSelectedEventId] = useState<string | null>(searchParams.get('eventId'));
  const [mode, setMode] = useState<'fixed_equal' | 'voluntary'>('fixed_equal');
  const [excludeSelf, setExcludeSelf] = useState(false);
  // Сколько организатор уже закрыл своими деньгами — работает только вместе с "исключить себя".
  const [selfPaidRub, setSelfPaidRub] = useState('');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [billRub, setBillRub] = useState('');
  const [paymentLink, setPaymentLink] = useState('');
  const [paymentMethodNote, setPaymentMethodNote] = useState('');
  const [photoUrl, setPhotoUrl] = useState<string | null>(null);
  const [deadline, setDeadline] = useState(defaultDeadlineLocal());
  const [submitError, setSubmitError] = useState<string | null>(null);

  // Список отбирает бэкенд: показываем только встречи, по которым сбор реально создастся.
  const splittableQuery = useSplittableEventsQuery(clubId);
  const eventQuery = useEventQuery(selectedEventId ?? undefined);
  const respondersQuery = useEventRespondersQuery(selectedEventId ?? undefined);

  if (!clubId) {
    return (
      <div className="rd-page">
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--text-dim)' }}>Клуб не найден</div>
      </div>
    );
  }

  const attended = (respondersQuery.data ?? []).filter((r) => r.attendance === 'attended');
  const attendedCount = attended.length;
  // "Исключить себя": the organizer drops out of the charged set (only if they actually attended).
  const organizerAttended = myId != null && attended.some((r) => r.userId === myId);
  const chargedCount = attendedCount - (excludeSelf && organizerAttended ? 1 : 0);
  const billKopecks = rubToKopecks(billRub);
  const selfPaidKopecks = excludeSelf && organizerAttended ? rubToKopecks(selfPaidRub) : null;
  // Взнос организатора до остальных не доезжает — поровну делится остаток чека.
  const toSplitKopecks = billKopecks != null ? billKopecks - (selfPaidKopecks ?? 0) : null;
  const perPersonRub = toSplitKopecks != null && toSplitKopecks > 0 && chargedCount > 0
    ? Math.round(toSplitKopecks / chargedCount / 100)
    : null;
  // С предоплатой сбор осмыслен и с одним должником: «я заплатил 2000, ты должен 1000».
  const minPayers = selfPaidKopecks != null ? 1 : 2;
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
    if (chargedCount < minPayers) {
      return fail(minPayers === 1
        ? 'Нужен хотя бы один участник к оплате. Отметьте явку на событии.'
        : 'Нужно минимум 2 участника к оплате. Отметьте явку на событии.');
    }
    if (!paymentLink.trim()) return fail('Укажите платёжную ссылку');
    const total = rubToKopecks(billRub);
    if (total === null) return fail('Укажите сумму чека (₽)');
    if (selfPaidKopecks !== null && selfPaidKopecks >= total) {
      return fail('Ваша сумма должна быть меньше суммы чека — иначе собирать нечего');
    }

    const body: CreateSkladchinaRequest = {
      title: (title.trim() || defaultTitle).slice(0, 255),
      description: description.trim() || null,
      template: 'split_bill',
      eventId: selectedEventId,
      excludeSelf,
      selfPaidKopecks,
      paymentMode: mode,
      totalGoalKopecks: total,
      paymentLink: paymentLink.trim(),
      paymentMethodNote: paymentMethodNote.trim() || null,
      photoUrl,
      deadline: new Date(deadline).toISOString(),
      // split всегда влияет на репутацию (сервер форсит для verified-шаблона); поле декоративно.
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

  // --- Шаг 1: выбор прошедшего события (только если оно не передано заранее) ---
  if (!selectedEventId) {
    const events = splittableQuery.data ?? [];
    return (
      <div className="rd-page">
        <div className="rd-ft-eyebrow">Разделить счёт</div>
        <h1 className="rd-page-h" style={{ marginBottom: 18 }}>Выберите встречу</h1>
        {splittableQuery.isPending && <Spinner size="s" />}
        {!splittableQuery.isPending && events.length === 0 && (
          <div className="rd-hint">
            Нет встреч, по которым можно разделить счёт. Нужна прошедшая встреча не старше 30 дней
            с отмеченной явкой и минимум двумя пришедшими, по которой счёт ещё не делили.
          </div>
        )}
        {events.length > 0 && (
          <div className="rd-pick-list">
            {events.map((ev) => (
              <button
                key={ev.eventId}
                type="button"
                className="rd-pick-toggle"
                onClick={() => handlePickEvent(ev.eventId)}
                style={{ width: '100%' }}
              >
                <span className="rd-pick-name">{ev.title}</span>
                <span className="rd-pick-note">
                  {DATE_FMT.format(new Date(ev.eventDatetime))} · пришли {ev.attendedCount}
                </span>
              </button>
            ))}
          </div>
        )}
      </div>
    );
  }

  // --- Шаг 2: форма счёта для выбранного события ---
  const attendanceLoading = eventQuery.isPending || respondersQuery.isPending;
  const notEnoughAttended = !attendanceLoading && chargedCount < minPayers;

  return (
    <div className="rd-page">
      <div className="rd-ft-eyebrow">Разделить счёт</div>
      <h1 className="rd-page-h" style={{ marginBottom: 14 }}>{eventTitle || 'Сбор по событию'}</h1>

      {attendanceLoading ? (
        <div className="rd-spinner-row" style={{ paddingTop: 20 }}><Spinner size="s" /></div>
      ) : (
        <div className="rd-form">
          <div className="rd-glass" style={{ padding: '12px 16px', marginBottom: 4 }}>
            <div className="rd-sklad-stats">
              Пришли: {attendedCount} {attendedCount === 1 ? 'человек' : 'чел.'}
              {excludeSelf && organizerAttended && ` · к оплате ${chargedCount}`}
            </div>
            {organizerAttended && (
              <label className="rd-check" style={{ marginTop: 8 }}>
                <input
                  type="checkbox"
                  checked={excludeSelf}
                  onChange={(e) => {
                    haptic.select();
                    setExcludeSelf(e.target.checked);
                    if (!e.target.checked) setSelfPaidRub('');
                  }}
                />
                <span>Исключить себя из счёта</span>
              </label>
            )}
            {excludeSelf && organizerAttended && (
              <label className="rd-field" style={{ marginTop: 10 }}>
                <span className="rd-label">Я уже внёс (₽)</span>
                <input
                  className="rd-input"
                  type="number"
                  inputMode="decimal"
                  min="1"
                  value={selfPaidRub}
                  onChange={(e) => setSelfPaidRub(e.target.value)}
                  placeholder="Например, 1500"
                />
                <span className="rd-hint">
                  Сумма зачтётся в сбор, вас сразу отметим оплатившим, остальные разделят остаток.
                  Можно не заполнять.
                </span>
              </label>
            )}
            {notEnoughAttended && (
              <div className="rd-warn-block" style={{ marginTop: 8 }}>
                {minPayers === 1
                  ? 'Нужен хотя бы один участник к оплате. Отметьте явку на событии, потом делите счёт.'
                  : 'Нужно минимум 2 участника к оплате. Отметьте явку на событии, потом делите счёт.'}
              </div>
            )}
          </div>

          <div className="rd-warn-block" style={{ marginBottom: 4 }}>
            ⚠️ Влияет на репутацию: оплата укрепляет её, молчание до срока — снижает на 40.
            Участники увидят это в сборе.
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
            <span className="rd-label">За что скидываемся (опц.)</span>
            <textarea
              className="rd-textarea"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              placeholder="Например: еда и напитки на компанию, кальян не входит"
            />
            <span className="rd-hint">Участники увидят это в сборе и в сообщении от бота</span>
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
            {mode === 'fixed_equal' && perPersonRub != null && chargedCount >= minPayers && (
              <span className="rd-hint">
                {selfPaidKopecks != null
                  ? `Ваши ${(selfPaidKopecks / 100).toLocaleString('ru-RU')} ₽ зачтены · остальные ≈ по ${perPersonRub.toLocaleString('ru-RU')} ₽ (${chargedCount} чел.)`
                  : `≈ по ${perPersonRub.toLocaleString('ru-RU')} ₽ с каждого (${chargedCount} чел.)`}
              </span>
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

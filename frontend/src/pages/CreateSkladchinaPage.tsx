import { FC, useMemo, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { PhotoAttach } from '../components/PhotoAttach';
import { ApiError } from '../api/apiClient';
import { useClubMembersQuery } from '../queries/members';
import { useCreateSkladchinaMutation, useSplittableEventsQuery } from '../queries/skladchina';
import { useAuthStore } from '../store/useAuthStore';
import type { CreateSkladchinaRequest, MemberListItemDto, SkladchinaKind } from '../types/api';
import { rubToKopecks } from '../utils/money';
import { DATE_FMT, KIND_EMOJI, KIND_LABEL } from '../utils/skladchinaKind';

/** Откуда берётся список должников у «Скинуться» (§ 3.1–3.2). */
type SharedSource = 'list' | 'enroll' | 'event';

const KIND_HINT: Record<SkladchinaKind, string> = {
  shared: 'Сумма делится на людей, каждому — свой долг до срока. Влияет на репутацию.',
  per_head: 'Цена за штуку. Каждый жмёт «Беру», при «Заказываю» неоплатившие выбывают без долга.',
  voluntary: 'Сколько хотите, без срока и долгов. Можно скрыть от одного человека (подарок).',
};

function toLocalInput(d: Date): string {
  const c = new Date(d);
  c.setMinutes(c.getMinutes() - c.getTimezoneOffset());
  return c.toISOString().slice(0, 16);
}

function plusDays(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return toLocalInput(d);
}

function createErrorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    // Бизнес-правила бэкенда возвращаются как 400/403/404/409 с готовым русским сообщением.
    if ((e.status === 400 || e.status === 403 || e.status === 404 || e.status === 409) && e.message) return e.message;
    if (e.status === 429) return 'Слишком много запросов. Подождите немного и попробуйте снова.';
  }
  return 'Не удалось создать сбор. Проверьте поля и попробуйте снова.';
}

function isKind(v: string | null): v is SkladchinaKind {
  return v === 'shared' || v === 'per_head' || v === 'voluntary';
}

/**
 * Одна форма на три вида сбора (skladchina-v3 § 9): `?kind=shared|per_head|voluntary`, у
 * «Скинуться» ещё `&eventId=` со страницы встречи. Создать может любой участник клуба.
 */
export const CreateSkladchinaPage: FC = () => {
  useBackButton(true);
  const { id: clubId } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const haptic = useHaptic();
  const myId = useAuthStore((st) => st.user?.id);
  const createMut = useCreateSkladchinaMutation();

  const kindParam = searchParams.get('kind');
  const kind: SkladchinaKind = isKind(kindParam) ? kindParam : 'shared';
  const presetEventId = searchParams.get('eventId');

  const membersQuery = useClubMembersQuery(clubId);
  const splittableQuery = useSplittableEventsQuery(kind === 'shared' ? clubId : undefined);

  // Без доступа (frozen/expired) в сбор не попадают: видны, но неактивны, и уходят в конец списка.
  const members = useMemo(() => {
    const rows = membersQuery.data ?? [];
    const locked = (m: MemberListItemDto) => (m.accessStatus === 'frozen' || m.accessStatus === 'expired' ? 1 : 0);
    return [...rows].sort((a, b) => locked(a) - locked(b));
  }, [membersQuery.data]);

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [photoUrl, setPhotoUrl] = useState<string | null>(null);
  const [amountRub, setAmountRub] = useState('');
  const [paymentLink, setPaymentLink] = useState('');
  const [paymentMethodNote, setPaymentMethodNote] = useState('');
  const [deadline, setDeadline] = useState(plusDays(kind === 'per_head' ? 5 : 3));
  const [noDeadline, setNoDeadline] = useState(kind === 'voluntary');
  const [source, setSource] = useState<SharedSource>(presetEventId ? 'event' : 'list');
  const [eventId, setEventId] = useState<string | null>(presetEventId);
  const [enrollmentUntil, setEnrollmentUntil] = useState(plusDays(1));
  const [minParticipants, setMinParticipants] = useState('');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [perPerson, setPerPerson] = useState(false);
  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const [hiddenFrom, setHiddenFrom] = useState<string | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);

  if (!clubId) {
    return (
      <div className="rd-page">
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--text-dim)' }}>Клуб не найден</div>
      </div>
    );
  }

  const fail = (msg: string) => {
    haptic.notify('error');
    setSubmitError(msg);
  };

  const toggleMember = (m: MemberListItemDto) => {
    if (m.accessStatus === 'frozen' || m.accessStatus === 'expired') return;
    haptic.select();
    const next = new Set(selectedIds);
    if (next.has(m.userId)) next.delete(m.userId);
    else next.add(m.userId);
    setSelectedIds(next);
  };

  const handleSubmit = async () => {
    setSubmitError(null);
    if (!title.trim()) return fail('Введите название');
    if (!paymentLink.trim()) return fail('Укажите реквизиты — ссылку или номер для перевода');
    const amountKopecks = amountRub.trim() ? rubToKopecks(amountRub) : null;
    if (amountRub.trim() && amountKopecks === null) return fail('Сумма должна быть числом больше нуля');
    if (kind !== 'voluntary' && amountKopecks === null) return fail(kind === 'per_head' ? 'Укажите цену за человека' : 'Укажите сумму');
    const withDeadline = kind !== 'voluntary' || !noDeadline;
    if (withDeadline && !deadline) return fail('Укажите срок');

    const body: CreateSkladchinaRequest = {
      title: title.trim(),
      description: description.trim() || null,
      photoUrl,
      kind,
      amountKopecks,
      paymentLink: paymentLink.trim(),
      paymentMethodNote: paymentMethodNote.trim() || null,
      deadline: withDeadline ? new Date(deadline).toISOString() : null,
    };

    if (kind === 'shared') {
      if (source === 'event') {
        if (!eventId) return fail('Выберите встречу');
        body.eventId = eventId;
      } else if (source === 'enroll') {
        if (!enrollmentUntil) return fail('Укажите, до когда открыта запись');
        body.enrollmentUntil = new Date(enrollmentUntil).toISOString();
        const min = minParticipants.trim() ? Number(minParticipants) : null;
        if (min !== null && (!Number.isInteger(min) || min < 1)) return fail('Минимум — целое число от 1');
        body.minParticipants = min;
      } else {
        if (selectedIds.size === 0) return fail('Выберите хотя бы одного человека');
        const debtors = Array.from(selectedIds).map((userId) => ({
          userId,
          amountKopecks: perPerson ? rubToKopecks(amounts[userId] ?? '') : null,
        }));
        if (perPerson && debtors.some((d) => d.amountKopecks === null)) return fail('Укажите сумму каждому');
        body.debtors = debtors;
      }
    }
    if (kind === 'voluntary') body.hiddenFromUserId = hiddenFrom;

    try {
      haptic.impact('medium');
      const created = await createMut.mutateAsync({ clubId, body });
      haptic.notify('success');
      navigate(`/skladchina/${created.id}`, { replace: true });
    } catch (e) {
      console.error('createSkladchina failed', e);
      haptic.notify('error');
      setSubmitError(createErrorMessage(e));
    }
  };

  const events = splittableQuery.data ?? [];
  const selectedEvent = events.find((ev) => ev.eventId === eventId);
  const perPersonHint = (() => {
    const total = rubToKopecks(amountRub);
    if (!total) return null;
    const n = source === 'event' ? selectedEvent?.attendedCount ?? 0 : selectedIds.size;
    if (source === 'enroll' || n === 0 || perPerson) return null;
    return `≈ по ${Math.round(total / n / 100).toLocaleString('ru-RU')} ₽ с каждого (${n} чел.)`;
  })();

  return (
    <div className="rd-page">
      <div className="rd-ft-eyebrow">Новый сбор</div>
      <h1 className="rd-page-h" style={{ marginBottom: 6 }}>{KIND_EMOJI[kind]} {KIND_LABEL[kind]}</h1>
      <div className="rd-hint" style={{ marginBottom: 18 }}>{KIND_HINT[kind]}</div>

      <div className="rd-form">
        <label className="rd-field">
          <span className="rd-label">Название <span className="rd-req">*</span></span>
          <input className="rd-input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={255} placeholder={kind === 'per_head' ? 'Билеты на матч' : kind === 'voluntary' ? 'Подарок Маше' : 'Ужин после игры'} />
        </label>

        <label className="rd-field">
          <span className="rd-label">Описание</span>
          <textarea className="rd-textarea" value={description} onChange={(e) => setDescription(e.target.value)} rows={2} />
        </label>

        <label className="rd-field">
          <span className="rd-label">
            {kind === 'shared' ? 'Сумма (₽)' : kind === 'per_head' ? 'Цена за человека (₽)' : 'Ориентир (₽)'}
            {kind !== 'voluntary' && <> <span className="rd-req">*</span></>}
          </span>
          <input className="rd-input" type="number" inputMode="decimal" min="1" value={amountRub} onChange={(e) => setAmountRub(e.target.value)} placeholder="Например, 6000" />
          {perPersonHint && <span className="rd-hint">{perPersonHint}</span>}
          {kind === 'voluntary' && <span className="rd-hint">Необязательно: участники увидят, сколько получено</span>}
        </label>

        {kind === 'shared' && !presetEventId && (
          <div className="rd-field">
            <span className="rd-label">Кто платит <span className="rd-req">*</span></span>
            <div className="rd-seg rd-seg-flush" role="tablist">
              {([['list', 'Список'], ['enroll', 'Кто в деле?'], ['event', 'После встречи']] as [SharedSource, string][]).map(([key, label]) => (
                <button key={key} type="button" role="tab" aria-selected={source === key} className={source === key ? 'rd-seg-btn rd-active' : 'rd-seg-btn'} onClick={() => { haptic.select(); setSource(key); }}>
                  {label}
                </button>
              ))}
            </div>
          </div>
        )}

        {kind === 'shared' && source === 'event' && (
          <div className="rd-field">
            <span className="rd-label">Встреча <span className="rd-req">*</span></span>
            {presetEventId && selectedEvent && (
              <div className="rd-hint">{selectedEvent.title} · {DATE_FMT.format(new Date(selectedEvent.eventDatetime))} · пришли {selectedEvent.attendedCount}</div>
            )}
            {!presetEventId && splittableQuery.isPending && <Spinner size="s" />}
            {!presetEventId && !splittableQuery.isPending && events.length === 0 && (
              <div className="rd-hint">Нет встреч, по которым можно скинуться: нужна прошедшая встреча не старше 30 дней с отмеченной явкой и минимум двумя пришедшими.</div>
            )}
            {!presetEventId && events.length > 0 && (
              <div className="rd-pick-list">
                {events.map((ev) => (
                  <button key={ev.eventId} type="button" className={`rd-pick-toggle${eventId === ev.eventId ? ' rd-selected' : ''}`} onClick={() => { haptic.select(); setEventId(ev.eventId); }} style={{ width: '100%' }}>
                    <span className="rd-check-box">{eventId === ev.eventId ? '✓' : ''}</span>
                    <span className="rd-pick-name">{ev.title}</span>
                    <span className="rd-pick-note">{DATE_FMT.format(new Date(ev.eventDatetime))} · пришли {ev.attendedCount}</span>
                  </button>
                ))}
              </div>
            )}
            <span className="rd-hint">Список — пришедшие на встречу, поровну. Ваша доля сразу считается полученной.</span>
          </div>
        )}

        {kind === 'shared' && source === 'enroll' && (
          <>
            <label className="rd-field">
              <span className="rd-label">Отметиться до <span className="rd-req">*</span></span>
              <input className="rd-input" type="datetime-local" value={enrollmentUntil} onChange={(e) => setEnrollmentUntil(e.target.value)} />
              <span className="rd-hint">Потом список замораживается, сумма делится поровну между теми, кто в деле. Вы в деле по умолчанию.</span>
            </label>
            <label className="rd-field">
              <span className="rd-label">Минимум людей</span>
              <input className="rd-input" type="number" inputMode="numeric" min="1" value={minParticipants} onChange={(e) => setMinParticipants(e.target.value)} placeholder="Необязательно" />
              <span className="rd-hint">Не наберётся — сбор отменится сам, денег никто не переводит.</span>
            </label>
          </>
        )}

        {kind === 'shared' && source === 'list' && (
          <div className="rd-field">
            <span className="rd-label">
              Люди <span className="rd-req">*</span> <span className="rd-count">· выбрано {selectedIds.size}</span>
            </span>
            <label className="rd-check" style={{ marginBottom: 8 }}>
              <input type="checkbox" checked={perPerson} onChange={(e) => setPerPerson(e.target.checked)} />
              <span>Суммы по людям (иначе поровну)</span>
            </label>
            {membersQuery.isPending && <Spinner size="s" />}
            {!membersQuery.isPending && members.length === 0 && <div className="rd-hint">В клубе пока нет активных участников.</div>}
            {members.length > 0 && (
              <div className="rd-pick-list">
                {members.map((m) => {
                  const isSelected = selectedIds.has(m.userId);
                  const isFrozen = m.accessStatus === 'frozen' || m.accessStatus === 'expired';
                  return (
                    <div key={m.userId} className="rd-pick-row">
                      <button type="button" className={`rd-pick-toggle${isSelected ? ' rd-selected' : ''}${isFrozen ? ' rd-frozen' : ''}`} onClick={() => toggleMember(m)} disabled={isFrozen} aria-disabled={isFrozen}>
                        <span className="rd-check-box">{isSelected ? '✓' : ''}</span>
                        <span className="rd-pick-name">
                          {m.firstName}{m.lastName ? ` ${m.lastName}` : ''}{m.userId === myId ? ' (вы)' : ''}
                        </span>
                        {isFrozen && <span className="rd-pick-note">{m.accessStatus === 'expired' ? '⛔ Доступ истёк' : '❄️ Доступ закрыт'}</span>}
                      </button>
                      {!isFrozen && perPerson && isSelected && (
                        <input type="number" inputMode="decimal" min="1" placeholder="₽" className="rd-input rd-pick-amount" value={amounts[m.userId] ?? ''} onChange={(e) => setAmounts((prev) => ({ ...prev, [m.userId]: e.target.value }))} />
                      )}
                    </div>
                  );
                })}
              </div>
            )}
            <span className="rd-hint">Себя добавлять можно: ваша доля сразу считается полученной.</span>
          </div>
        )}

        {kind === 'voluntary' && (
          <div className="rd-field">
            <span className="rd-label">Скрыть от</span>
            {membersQuery.isPending && <Spinner size="s" />}
            {members.length > 0 && (
              <div className="rd-pick-list">
                {members.filter((m) => m.userId !== myId).map((m) => (
                  <button key={m.userId} type="button" className={`rd-pick-toggle${hiddenFrom === m.userId ? ' rd-selected' : ''}`} onClick={() => { haptic.select(); setHiddenFrom(hiddenFrom === m.userId ? null : m.userId); }} style={{ width: '100%' }}>
                    <span className="rd-check-box">{hiddenFrom === m.userId ? '✓' : ''}</span>
                    <span className="rd-pick-name">{m.firstName}{m.lastName ? ` ${m.lastName}` : ''}</span>
                  </button>
                ))}
              </div>
            )}
            <span className="rd-hint">Тихий сбор: в чат не постится, скрытый не увидит его нигде.</span>
          </div>
        )}

        <label className="rd-field">
          <span className="rd-label">Реквизиты <span className="rd-req">*</span></span>
          <input className="rd-input" value={paymentLink} onChange={(e) => setPaymentLink(e.target.value)} placeholder="Ссылка СБП или номер телефона" />
          <span className="rd-hint">Увидят все, кому вы собираете</span>
        </label>

        <label className="rd-field">
          <span className="rd-label">Банк / способ</span>
          <input className="rd-input" value={paymentMethodNote} onChange={(e) => setPaymentMethodNote(e.target.value)} placeholder="Тинькофф, СБП, ВТБ…" />
        </label>

        {kind === 'voluntary' && (
          <label className="rd-check">
            <input type="checkbox" checked={noDeadline} onChange={(e) => setNoDeadline(e.target.checked)} />
            <span>Без срока</span>
          </label>
        )}
        {(kind !== 'voluntary' || !noDeadline) && (
          <label className="rd-field">
            <span className="rd-label">
              {kind === 'per_head' ? 'Покупаю' : 'Срок оплаты'} <span className="rd-req">*</span>
            </span>
            <input className="rd-input" type="datetime-local" value={deadline} onChange={(e) => setDeadline(e.target.value)} />
            {kind === 'shared' && <span className="rd-hint">Срок не стена: заплатить можно и после, но просрочка дольше 3 недель стоит −40 к репутации.</span>}
            {kind === 'per_head' && <span className="rd-hint">Автозаказа нет: в срок бот напомнит вам нажать «Заказываю».</span>}
          </label>
        )}

        <div className="rd-field">
          <span className="rd-label">Фото / чек</span>
          <PhotoAttach value={photoUrl} onChange={setPhotoUrl} addLabel="Прикрепить" />
        </div>

        {submitError && <div className="rd-error">{submitError}</div>}

        <div className="rd-form-actions">
          <button type="button" className="rd-btn-outline" onClick={() => { haptic.impact('light'); navigate(-1); }}>Отмена</button>
          <button type="button" className="rd-btn-primary" onClick={handleSubmit} disabled={createMut.isPending}>
            {createMut.isPending ? 'Создаём…' : 'Создать сбор'}
          </button>
        </div>
      </div>
    </div>
  );
};

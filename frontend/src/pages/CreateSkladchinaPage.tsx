import { FC, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { PhotoAttach } from '../components/PhotoAttach';
import { ApiError } from '../api/apiClient';
import { useClubMembersQuery } from '../queries/members';
import { useCreateSkladchinaMutation, useSplittableEventsQuery } from '../queries/skladchina';
import { useAuthStore } from '../store/useAuthStore';
import type { CreateSkladchinaRequest, MemberListItemDto } from '../types/api';
import { rubToKopecks } from '../utils/money';
import { DATE_FMT, FLOW_EMOJI, FLOW_KIND, FLOW_LABEL, FLOW_SUBTITLE, isSkladchinaFlow, type SkladchinaFlow } from '../utils/skladchinaKind';

const TITLE_PLACEHOLDER: Record<SkladchinaFlow, string> = {
  split: 'Ужин после игры',
  enroll: 'Тренер на субботу',
  per_head: 'Билеты на матч',
  voluntary: 'Подарок Маше',
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

/** `?flow=` — четыре входа; старые ссылки `?kind=` (страница встречи, закладки) читаются как раньше. */
function resolveFlow(params: URLSearchParams): SkladchinaFlow {
  const flow = params.get('flow');
  if (isSkladchinaFlow(flow)) return flow;
  const kind = params.get('kind');
  if (kind === 'per_head' || kind === 'voluntary') return kind;
  return 'split';
}

/**
 * Одна форма на четыре входа в сбор (skladchina-v3 § 9, § 13 п. 32–33): «Кто сколько должен?»,
 * «Кто в деле?», «Кто берёт?», «Кто сколько хочет?». Каждый вход — плоская форма без
 * переключателей режима; единственный выбор внутри — «поровну / суммы по людям». Встреча в форму
 * приходит только ссылкой со страницы встречи (`eventId`) и показывается строкой контекста.
 * Создать может любой участник клуба.
 */
export const CreateSkladchinaPage: FC = () => {
  useBackButton(true);
  const { id: clubId } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const haptic = useHaptic();
  const myId = useAuthStore((st) => st.user?.id);
  const createMut = useCreateSkladchinaMutation();

  const flow = resolveFlow(searchParams);
  const kind = FLOW_KIND[flow];
  const presetEventId = searchParams.get('eventId');
  // Список людей есть у двух входов: «кто сколько должен» и «кто сколько хочет».
  const usesPeople = flow === 'split' || flow === 'voluntary';

  const membersQuery = useClubMembersQuery(clubId);
  const splittableQuery = useSplittableEventsQuery(presetEventId ? clubId : undefined);

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
  const [deadline, setDeadline] = useState(plusDays(flow === 'per_head' ? 5 : 3));
  const [noDeadline, setNoDeadline] = useState(flow === 'voluntary');
  const eventId = presetEventId;
  const [enrollmentUntil, setEnrollmentUntil] = useState(plusDays(1));
  const [minParticipants, setMinParticipants] = useState('');
  const [enrollCreator, setEnrollCreator] = useState(true);
  const [takeCreator, setTakeCreator] = useState(true);
  const [creatorQuantity, setCreatorQuantity] = useState('1');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [perPerson, setPerPerson] = useState(false);
  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const [hiddenFrom, setHiddenFrom] = useState<string | null>(null);
  const [ownContribution, setOwnContribution] = useState(false);
  const [ownContributionRub, setOwnContributionRub] = useState('');
  const [submitError, setSubmitError] = useState<string | null>(null);

  const events = splittableQuery.data ?? [];
  const selectedEvent = events.find((ev) => ev.eventId === eventId);
  const attendedIds = useMemo(() => new Set(selectedEvent?.attendedUserIds ?? []), [selectedEvent]);
  // После встречи: сначала пришедшие, ниже остальные участники клуба; своей строки у создателя нет.
  const orderedMembers = useMemo(() => {
    const others = members.filter((m) => m.userId !== myId);
    if (attendedIds.size === 0) return others;
    return [...others.filter((m) => attendedIds.has(m.userId)), ...others.filter((m) => !attendedIds.has(m.userId))];
  }, [members, attendedIds, myId]);

  // Встреча задана ссылкой со страницы встречи: явка подтягивается, когда список встреч загрузился.
  const prefilledEventRef = useRef<string | null>(null);
  useEffect(() => {
    if (!selectedEvent || prefilledEventRef.current === selectedEvent.eventId) return;
    prefilledEventRef.current = selectedEvent.eventId;
    setSelectedIds(new Set(selectedEvent.attendedUserIds));
  }, [selectedEvent]);

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

  const perPersonMode = flow === 'split' && perPerson;
  const includeMe = Boolean(myId && selectedIds.has(myId));

  const toggleMember = (m: MemberListItemDto) => {
    if (m.accessStatus === 'frozen' || m.accessStatus === 'expired') return;
    haptic.select();
    const next = new Set(selectedIds);
    if (next.has(m.userId)) next.delete(m.userId);
    else next.add(m.userId);
    setSelectedIds(next);
  };

  const toggleMe = () => {
    if (!myId) return;
    haptic.select();
    const next = new Set(selectedIds);
    if (next.has(myId)) next.delete(myId);
    else next.add(myId);
    setSelectedIds(next);
  };

  // Поле суммы видно у всех, и введённая сумма сама отмечает человека — иначе набитая сумма
  // у невыбранного молча пропадала бы при отправке.
  const setPersonAmount = (m: MemberListItemDto, value: string) => {
    setAmounts((prev) => ({ ...prev, [m.userId]: value }));
    if (value.trim() && !selectedIds.has(m.userId)) setSelectedIds(new Set(selectedIds).add(m.userId));
  };

  const handleSubmit = async () => {
    setSubmitError(null);
    if (!title.trim()) return fail('Введите название');
    if (!paymentLink.trim()) return fail('Укажите реквизиты — ссылку или номер для перевода');
    // «Суммы по людям»: общая сумма = сумма долей, отдельное поле не нужно.
    const perPersonTotal = perPersonMode ? Array.from(selectedIds).reduce((acc, id) => acc + (rubToKopecks(amounts[id] ?? '') ?? 0), 0) : 0;
    const amountKopecks = perPersonMode ? (perPersonTotal > 0 ? perPersonTotal : null) : amountRub.trim() ? rubToKopecks(amountRub) : null;
    if (!perPersonMode && amountRub.trim() && amountKopecks === null) return fail('Сумма должна быть числом больше нуля');
    if (!perPersonMode && kind !== 'voluntary' && amountKopecks === null) return fail(flow === 'per_head' ? 'Укажите цену за штуку' : 'Укажите сумму');
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

    if (flow === 'split') {
      if (eventId) body.eventId = eventId;
      if (Array.from(selectedIds).every((id) => id === myId)) return fail('Выберите хотя бы одного человека кроме себя');
      const debtors = Array.from(selectedIds).map((userId) => ({
        userId,
        amountKopecks: perPerson ? rubToKopecks(amounts[userId] ?? '') : null,
      }));
      if (perPerson && debtors.some((d) => d.amountKopecks === null)) return fail('Укажите сумму каждому');
      body.debtors = debtors;
    }
    if (flow === 'enroll') {
      if (!enrollmentUntil) return fail('Укажите, до когда открыта запись');
      body.enrollmentUntil = new Date(enrollmentUntil).toISOString();
      const min = minParticipants.trim() ? Number(minParticipants) : null;
      if (min !== null && (!Number.isInteger(min) || min < 1)) return fail('Минимум — целое число от 1');
      body.minParticipants = min;
      body.enrollCreator = enrollCreator;
    }
    if (flow === 'per_head') {
      const qty = Number(creatorQuantity);
      if (takeCreator && (!/^\d+$/.test(creatorQuantity.trim()) || qty < 1 || qty > 50)) return fail('Сколько штук берёте себе: от 1 до 50');
      body.takeCreator = takeCreator;
      body.creatorQuantity = takeCreator ? qty : 1;
    }
    if (flow === 'voluntary') {
      if (eventId) body.eventId = eventId;
      // Никого не выбрали — зовём всех участников клуба (бэк так и читает пустой список).
      body.invitedUserIds = Array.from(selectedIds).filter((id) => id !== myId);
      body.hiddenFromUserId = eventId ? null : hiddenFrom;
      if (ownContribution) {
        const own = rubToKopecks(ownContributionRub);
        if (own === null) return fail('Укажите, сколько скидываетесь сами');
        body.creatorContributionKopecks = own;
      }
    }

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

  const perPersonHint = (() => {
    if (flow !== 'split' || perPerson) return null;
    const total = rubToKopecks(amountRub);
    const n = selectedIds.size;
    if (!total || n === 0) return null;
    return `≈ по ${Math.round(total / n / 100).toLocaleString('ru-RU')} ₽ с каждого (${n} чел.)`;
  })();

  const amountLabel = flow === 'per_head' ? 'Цена за штуку (₽)' : flow === 'voluntary' ? (eventId ? 'Всего потратили (₽)' : 'Ориентир (₽)') : 'Сумма (₽)';

  // Встреча приходит только со страницы встречи (§ 13 п. 33): в форме она строка контекста, не поле.
  const eventLine = presetEventId && (
    <div className="rd-field">
      <span className="rd-label">За встречу</span>
      <div className="rd-hint">
        {selectedEvent
          ? `${selectedEvent.title} · ${DATE_FMT.format(new Date(selectedEvent.eventDatetime))} · пришли ${selectedEvent.attendedCount} — они отмечены ниже, состав можно поправить`
          : 'Пришедшие будут отмечены ниже, состав можно поправить'}
      </div>
    </div>
  );

  const peopleList = (
    <div className="rd-field">
      <span className="rd-label">
        {flow === 'split' ? 'Люди' : 'Кого позвать'}
        {flow === 'split' && <> <span className="rd-req">*</span></>}
        <span className="rd-count"> · выбрано {selectedIds.size}</span>
      </span>
      {flow === 'split' && (
        <label className="rd-check" style={{ marginBottom: 8 }}>
          <input type="checkbox" checked={perPerson} onChange={(e) => setPerPerson(e.target.checked)} />
          <span>Суммы по людям (иначе поровну)</span>
        </label>
      )}
      {membersQuery.isPending && <Spinner size="s" />}
      {!membersQuery.isPending && orderedMembers.length === 0 && <div className="rd-hint">В клубе пока нет других участников.</div>}
      {orderedMembers.length > 0 && (
        <div className="rd-pick-list">
          {orderedMembers.map((m) => {
            const isSelected = selectedIds.has(m.userId);
            const isFrozen = m.accessStatus === 'frozen' || m.accessStatus === 'expired';
            return (
              <div key={m.userId} className="rd-debtor-row">
                <button type="button" className={`rd-pick-toggle${isSelected ? ' rd-selected' : ''}${isFrozen ? ' rd-frozen' : ''}`} onClick={() => toggleMember(m)} disabled={isFrozen} aria-disabled={isFrozen}>
                  <span className="rd-check-box">{isSelected ? '✓' : ''}</span>
                  <span className="rd-pick-name">{m.firstName}{m.lastName ? ` ${m.lastName}` : ''}</span>
                  {isFrozen && <span className="rd-pick-note">{m.accessStatus === 'expired' ? '⛔ Доступ истёк' : '❄️ Доступ закрыт'}</span>}
                  {!isFrozen && attendedIds.has(m.userId) && <span className="rd-pick-note">был</span>}
                </button>
                {!isFrozen && perPersonMode && (
                  <input
                    type="number"
                    inputMode="decimal"
                    min="1"
                    placeholder="₽"
                    className="rd-input rd-pick-amount"
                    aria-label={`Сумма для ${m.firstName}`}
                    value={amounts[m.userId] ?? ''}
                    onChange={(e) => setPersonAmount(m, e.target.value)}
                  />
                )}
              </div>
            );
          })}
        </div>
      )}
      {flow === 'voluntary' && <span className="rd-hint">Никого не выбрали — позовём всех участников клуба.</span>}

      {/* Создатель — одной фразой во всех входах; рядом только то, что нужно входу. */}
      {flow === 'split' ? (
        <>
          <label className="rd-check" style={{ marginTop: 8 }}>
            <input type="checkbox" checked={includeMe} disabled={!myId} onChange={toggleMe} />
            <span>Я тоже участвую · моя доля сразу считается полученной</span>
          </label>
          {includeMe && perPersonMode && myId && (
            <label className="rd-take-row">
              <span className="rd-hint">Моя сумма (₽)</span>
              <input className="rd-input rd-take-qty" type="number" inputMode="decimal" min="1" aria-label="Моя сумма (₽)" placeholder="₽" value={amounts[myId] ?? ''} onChange={(e) => setAmounts((prev) => ({ ...prev, [myId]: e.target.value }))} />
            </label>
          )}
        </>
      ) : (
        <>
          <label className="rd-check" style={{ marginTop: 8 }}>
            <input type="checkbox" checked={ownContribution} onChange={(e) => setOwnContribution(e.target.checked)} />
            <span>Я тоже участвую · мой взнос сразу считается полученным</span>
          </label>
          {ownContribution && (
            <label className="rd-take-row">
              <span className="rd-hint">Сколько (₽)</span>
              <input className="rd-input rd-take-qty" type="number" inputMode="decimal" min="1" aria-label="Мой взнос (₽)" placeholder="500" value={ownContributionRub} onChange={(e) => setOwnContributionRub(e.target.value)} />
            </label>
          )}
        </>
      )}
    </div>
  );

  return (
    <div className="rd-page">
      <div className="rd-ft-eyebrow">Новый сбор</div>
      <h1 className="rd-page-h" style={{ marginBottom: 6 }}>{FLOW_EMOJI[flow]} {FLOW_LABEL[flow]}</h1>
      <div className="rd-hint" style={{ marginBottom: 18 }}>{FLOW_SUBTITLE[flow]}</div>

      <div className="rd-form">
        <label className="rd-field">
          <span className="rd-label">Название <span className="rd-req">*</span></span>
          <input className="rd-input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={255} placeholder={TITLE_PLACEHOLDER[flow]} />
        </label>

        <label className="rd-field">
          <span className="rd-label">Описание</span>
          <textarea className="rd-textarea" value={description} onChange={(e) => setDescription(e.target.value)} rows={2} />
        </label>

        {!perPersonMode && (
          <label className="rd-field">
            <span className="rd-label">
              {amountLabel}
              {kind !== 'voluntary' && <> <span className="rd-req">*</span></>}
            </span>
            <input className="rd-input" type="number" inputMode="decimal" min="1" value={amountRub} onChange={(e) => setAmountRub(e.target.value)} placeholder="Например, 6000" />
            {perPersonHint && <span className="rd-hint">{perPersonHint}</span>}
            {kind === 'voluntary' && <span className="rd-hint">Необязательно: люди увидят, сколько получено и сколько всего</span>}
          </label>
        )}

        {eventLine}

        {flow === 'enroll' && (
          <>
            <label className="rd-field">
              <span className="rd-label">Отметиться до <span className="rd-req">*</span></span>
              <input className="rd-input" type="datetime-local" value={enrollmentUntil} onChange={(e) => setEnrollmentUntil(e.target.value)} />
              <span className="rd-hint">Потом список замораживается, сумма делится поровну между теми, кто в деле.</span>
            </label>
            <label className="rd-check">
              <input type="checkbox" checked={enrollCreator} onChange={(e) => setEnrollCreator(e.target.checked)} />
              <span>Я тоже участвую · доля посчитается вместе со всеми и сразу считается полученной</span>
            </label>
            <label className="rd-field">
              <span className="rd-label">Минимум людей</span>
              <input className="rd-input" type="number" inputMode="numeric" min="1" value={minParticipants} onChange={(e) => setMinParticipants(e.target.value)} placeholder="Необязательно" />
              <span className="rd-hint">Не наберётся — сбор отменится сам, денег никто не переводит.</span>
            </label>
          </>
        )}

        {usesPeople && peopleList}

        {flow === 'voluntary' && !presetEventId && (
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
              {flow === 'per_head' ? 'Покупаю' : 'Срок оплаты'} <span className="rd-req">*</span>
            </span>
            <input className="rd-input" type="datetime-local" value={deadline} onChange={(e) => setDeadline(e.target.value)} />
            {kind === 'shared' && <span className="rd-hint">Срок не стена: заплатить можно и после, но просрочка дольше 3 недель стоит −40 к репутации.</span>}
            {flow === 'per_head' && <span className="rd-hint">Автозаказа нет: в срок бот напомнит вам нажать «Заказываю».</span>}
          </label>
        )}

        {flow === 'per_head' && (
          <div className="rd-field">
            <label className="rd-check">
              <input type="checkbox" checked={takeCreator} onChange={(e) => setTakeCreator(e.target.checked)} />
              <span>Я тоже участвую · моя доля сразу считается полученной</span>
            </label>
            {takeCreator && (
              <label className="rd-take-row">
                <span className="rd-hint">Сколько штук себе</span>
                <input className="rd-input rd-take-qty" type="number" inputMode="numeric" min="1" max="50" aria-label="Сколько штук себе" value={creatorQuantity} onChange={(e) => setCreatorQuantity(e.target.value)} />
              </label>
            )}
          </div>
        )}

        <div className="rd-field">
          <span className="rd-label">Фото / чек</span>
          <PhotoAttach value={photoUrl} onChange={setPhotoUrl} addLabel="Прикрепить фото" />
        </div>

        {submitError && <div className="rd-error">{submitError}</div>}

        <div className="rd-form-actions">
          <button type="button" className="rd-btn-outline" onClick={() => { haptic.impact('light'); navigate(-1); }}>Отмена</button>
          <button type="button" className="rd-btn-primary" disabled={createMut.isPending} onClick={handleSubmit}>
            {createMut.isPending ? 'Создаём…' : 'Создать сбор'}
          </button>
        </div>
      </div>
    </div>
  );
};

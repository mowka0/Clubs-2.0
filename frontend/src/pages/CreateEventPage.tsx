import { FC, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { BrandStepper } from '../components/BrandStepper';
import { AvatarUpload } from '../components/AvatarUpload';
import { LocationPickerSheet } from '../components/event/LocationPickerSheet';
import { useCreateEventMutation } from '../queries/events';
import type { CreateEventBody } from '../api/events';
import type { GeoPoint } from '../utils/yandexMaps';

const TITLE_MAX = 255;
// Лимит адреса (location_text в БД); адрес приходит из геокодера, но подрезаем защитно.
const LOCATION_MAX = 500;
// Лимит поля «Уточнение к месту» — зеркалит @Size(max=200) на locationHint бэкенда.
const LOCATION_HINT_MAX = 200;
const PARTICIPANT_MIN = 1;
const PARTICIPANT_MAX = 1000;

// Пресеты интервала Этапа 2 (за сколько до старта открывается подтверждение мест), в минутах.
// Значения зеркалят CHECK 360..2880 (V67) и @Min/@Max бэкенда; дефолт 1080 = 18 ч зеркалит
// events.stage2-trigger-minutes-before.
const STAGE2_LEAD_PRESETS: { minutes: number; label: string }[] = [
  { minutes: 360, label: 'за 6 ч' },
  { minutes: 720, label: 'за 12 ч' },
  { minutes: 1080, label: 'за 18 ч' },
  { minutes: 1440, label: 'за 24 ч' },
  { minutes: 2880, label: 'за 2 дня' },
];
const STAGE2_LEAD_DEFAULT = 1080;

// Выбранное в пикере место: точка на карте + адрес из обратного геокодера.
interface PickedLocation {
  point: GeoPoint;
  address: string;
}

const CalendarIcon: FC = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="3" y="4" width="18" height="18" rx="2" />
    <path d="M16 2v4M8 2v4M3 10h18" />
  </svg>
);

export const CreateEventPage: FC = () => {
  useBackButton(true);
  const { id: clubId } = useParams<{ id: string }>();
  // Открытая встреча (V62, решение PO 2026-07-21): ?format=open из шага формата в пикере
  // создания. Лимита нет (participantLimit = null на бэке) — степпер скрыт, заголовок другой.
  const [searchParams] = useSearchParams();
  const isOpenEvent = searchParams.get('format') === 'open';
  const navigate = useNavigate();
  const haptic = useHaptic();
  const createMut = useCreateEventMutation();

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [photoUrl, setPhotoUrl] = useState<string | null>(null);
  const [location, setLocation] = useState<PickedLocation | null>(null);
  const [locationHint, setLocationHint] = useState('');
  const [pickerOpen, setPickerOpen] = useState(false);
  const [eventDatetime, setEventDatetime] = useState('');
  const [participantLimit, setParticipantLimit] = useState(20);
  // null = организатор интервал не трогал → поле НЕ уходит в body, событие несёт NULL в БД и
  // следует серверному дефолту (в т.ч. staging-ужимке STAGE2_TRIGGER_MINUTES_BEFORE — она жива
  // только для NULL-событий). STAGE2_LEAD_DEFAULT здесь — лишь визуальный маркер активного чипа,
  // фактический дефолт применяет бэкенд.
  const [stage2LeadMinutes, setStage2LeadMinutes] = useState<number | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const effectiveStage2Lead = stage2LeadMinutes ?? STAGE2_LEAD_DEFAULT;

  // Встреча ближе выбранного интервала → Этап 2 стартует сразу после создания. Разрешаем
  // осознанно (решение PO 2026-07-23), но честно предупреждаем прямо в форме.
  const eventTimeMs = eventDatetime ? new Date(eventDatetime).getTime() : null;
  const stage2StartsImmediately =
    !isOpenEvent &&
    eventTimeMs !== null &&
    !Number.isNaN(eventTimeMs) &&
    eventTimeMs - Date.now() <= effectiveStage2Lead * 60_000;

  if (!clubId) {
    return (
      <div className="rd-page">
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--text-dim)' }}>
          Клуб не найден
        </div>
      </div>
    );
  }

  const fail = (msg: string) => {
    haptic.notify('error');
    setSubmitError(msg);
  };

  const handleSubmit = async () => {
    setSubmitError(null);
    if (!title.trim()) return fail('Укажите название');
    if (title.trim().length > TITLE_MAX) return fail(`Название: максимум ${TITLE_MAX} символов`);
    // Правило PO (V58): место опционально, но хоть какое-то указание нужно —
    // либо точка на карте, либо текстовое уточнение («в зуме», «место скинем в чат»).
    if (!location && !locationHint.trim()) {
      return fail('Укажите место на карте или заполните уточнение к месту');
    }
    if (locationHint.trim().length > LOCATION_HINT_MAX) {
      return fail(`Уточнение к месту: максимум ${LOCATION_HINT_MAX} символов`);
    }
    if (!eventDatetime) return fail('Укажите дату и время');
    const eventDate = new Date(eventDatetime);
    if (Number.isNaN(eventDate.getTime())) return fail('Некорректная дата');
    if (eventDate.getTime() <= Date.now()) {
      return fail('Дата события должна быть в будущем');
    }
    if (!isOpenEvent && (!Number.isInteger(participantLimit) || participantLimit < PARTICIPANT_MIN)) {
      return fail('Лимит участников: целое число больше нуля');
    }

    const body: CreateEventBody = {
      title: title.trim(),
      description: description.trim() || undefined,
      locationText: location ? location.address.trim().slice(0, LOCATION_MAX) : undefined,
      locationLat: location?.point.lat,
      locationLon: location?.point.lon,
      locationHint: locationHint.trim() || undefined,
      eventDatetime: eventDate.toISOString(),
      // Открытая встреча (V62): лимита нет + явный флаг формата — бэкенд валидирует их согласованность.
      participantLimit: isOpenEvent ? null : participantLimit,
      isOpenEvent,
      // Интервал Этапа 2 — только у событий с местами и только при ЯВНОМ выборе организатора
      // (null = серверный дефолт); открытая встреча вне двухэтапки.
      stage2LeadMinutes: isOpenEvent || stage2LeadMinutes === null ? undefined : stage2LeadMinutes,
      photoUrl: photoUrl ?? undefined,
    };

    try {
      haptic.impact('medium');
      await createMut.mutateAsync({ clubId, body });
      haptic.notify('success');
      navigate('/events', {
        replace: true,
        state: { toast: 'Событие создано' },
      });
    } catch (e) {
      console.error('createEvent failed', e);
      haptic.notify('error');
      const msg = e instanceof Error ? e.message : 'Не удалось создать событие';
      setSubmitError(msg);
    }
  };

  const handleCancel = () => {
    haptic.impact('light');
    navigate(-1);
  };

  return (
    <div className="rd-page">
      <div className="rd-ft-eyebrow">Создание</div>
      <h1 className="rd-page-h" style={{ marginBottom: 18 }}>
        {isOpenEvent ? 'Открытая встреча' : 'Новое событие'}
      </h1>
      {isOpenEvent && (
        <div className="rd-hint" style={{ marginTop: -10, marginBottom: 14 }}>
          Без лимита участников — приходят все, кто подтвердил. Репутация здесь не считается
          совсем: ни плюсов за посещение, ни штрафов за отказ или неявку.
        </div>
      )}

      <div className="rd-form">
        <label className="rd-field">
          <span className="rd-label">Название <span className="rd-req">*</span></span>
          <input
            className="rd-input"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            maxLength={TITLE_MAX}
            placeholder="Например: Йога в парке"
          />
        </label>

        <div className="rd-field">
          <span className="rd-label">Фото (опц.)</span>
          <AvatarUpload value={photoUrl} onChange={setPhotoUrl} />
        </div>

        <label className="rd-field">
          <span className="rd-label">Описание</span>
          <textarea
            className="rd-textarea"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={3}
            placeholder="Дополнительные детали для участников"
          />
        </label>

        <div className="rd-field">
          <span className="rd-label">Место</span>
          {location ? (
            <div className="rd-place-chip">
              <span className="rd-place-ic" aria-hidden="true">📍</span>
              <span className="rd-place-txt">
                <b>{location.address}</b>
                <span>точка уточнена на карте</span>
              </span>
              <button
                type="button"
                className="rd-place-edit"
                onClick={() => { haptic.impact('light'); setPickerOpen(true); }}
              >
                Изменить
              </button>
              <button
                type="button"
                className="rd-place-edit"
                onClick={() => { haptic.impact('light'); setLocation(null); }}
              >
                Убрать
              </button>
            </div>
          ) : (
            <button
              type="button"
              className="rd-invite-row"
              style={{ marginBottom: 0 }}
              onClick={() => { haptic.impact('light'); setPickerOpen(true); }}
            >
              <span className="rd-invite-plus" aria-hidden="true">📍</span>
              <span className="rd-invite-txt">
                <b>Добавить место</b>
                <span>Найдите адрес и уточните точку на карте</span>
              </span>
            </button>
          )}
        </div>

        <label className="rd-field">
          <span className="rd-label">Уточнение к месту</span>
          <input
            className="rd-input"
            value={locationHint}
            onChange={(e) => setLocationHint(e.target.value)}
            maxLength={LOCATION_HINT_MAX}
            placeholder="Вход со двора, домофон 12"
          />
        </label>

        <label className="rd-field">
          <span className="rd-label">Дата и время <span className="rd-req">*</span></span>
          <div className="rd-datetime">
            <input
              className="rd-input"
              type="datetime-local"
              value={eventDatetime}
              onChange={(e) => setEventDatetime(e.target.value)}
            />
            <span className="rd-dt-ico" aria-hidden="true"><CalendarIcon /></span>
          </div>
        </label>

        {/* Открытая встреча: лимита нет — степпер не рендерится вовсе. */}
        {!isOpenEvent && (
          <div className="rd-field">
            <span className="rd-label">Лимит участников <span className="rd-req">*</span></span>
            <BrandStepper
              value={participantLimit}
              onChange={setParticipantLimit}
              min={PARTICIPANT_MIN}
              max={PARTICIPANT_MAX}
              ariaLabel="Лимит участников"
            />
          </div>
        )}

        {/* Интервал Этапа 2 (V67): у открытой встречи подтверждения мест нет — контрол скрыт. */}
        {!isOpenEvent && (
          <div className="rd-field">
            <span className="rd-label">Подтверждение мест</span>
            <div className="rd-cat-chips" style={{ marginBottom: 0 }}>
              {STAGE2_LEAD_PRESETS.map((p) => (
                <button
                  key={p.minutes}
                  type="button"
                  className={`rd-cat-chip${effectiveStage2Lead === p.minutes ? ' rd-active' : ''}`}
                  onClick={() => { haptic.select(); setStage2LeadMinutes(p.minutes); }}
                >
                  {p.label}
                </button>
              ))}
            </div>
            {stage2StartsImmediately ? (
              <span className="rd-hint">
                ⚠️ До встречи меньше выбранного интервала — подтверждение мест начнётся сразу
                после создания. Чтобы сначала прошло голосование, выберите интервал короче
                времени до встречи (минимум — за 6 часов).
              </span>
            ) : (
              <span className="rd-hint">
                До этого момента идёт голосование «Пойду / Возможно», затем участники
                подтверждают свои места.
              </span>
            )}
          </div>
        )}

        {submitError && <div className="rd-error">{submitError}</div>}

        <div className="rd-form-actions">
          <button type="button" className="rd-btn-outline" onClick={handleCancel}>
            Отмена
          </button>
          <button
            type="button"
            className="rd-btn-primary"
            onClick={handleSubmit}
            disabled={createMut.isPending}
          >
            {createMut.isPending ? 'Создаём…' : 'Создать событие'}
          </button>
        </div>
      </div>

      {pickerOpen && (
        <LocationPickerSheet
          initial={location?.point ?? null}
          onSelect={(point, address) => {
            setLocation({ point, address });
            setPickerOpen(false);
          }}
          onClose={() => setPickerOpen(false)}
        />
      )}
    </div>
  );
};

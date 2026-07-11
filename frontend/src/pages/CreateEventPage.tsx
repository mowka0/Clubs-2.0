import { FC, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
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
  const [submitError, setSubmitError] = useState<string | null>(null);

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
    // Fail-closed (решение PO): место — только точка на карте, текстового фолбэка нет.
    if (!location) return fail('Укажите место на карте');
    if (locationHint.trim().length > LOCATION_HINT_MAX) {
      return fail(`Уточнение к месту: максимум ${LOCATION_HINT_MAX} символов`);
    }
    if (!eventDatetime) return fail('Укажите дату и время');
    const eventDate = new Date(eventDatetime);
    if (Number.isNaN(eventDate.getTime())) return fail('Некорректная дата');
    if (eventDate.getTime() <= Date.now()) {
      return fail('Дата события должна быть в будущем');
    }
    if (!Number.isInteger(participantLimit) || participantLimit < PARTICIPANT_MIN) {
      return fail('Лимит участников: целое число больше нуля');
    }

    const body: CreateEventBody = {
      title: title.trim(),
      description: description.trim() || undefined,
      locationText: location.address.trim().slice(0, LOCATION_MAX),
      locationLat: location.point.lat,
      locationLon: location.point.lon,
      locationHint: locationHint.trim() || undefined,
      eventDatetime: eventDate.toISOString(),
      participantLimit,
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
      <h1 className="rd-page-h" style={{ marginBottom: 18 }}>Новое событие</h1>

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
          <span className="rd-label">Место <span className="rd-req">*</span></span>
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
          <span className="rd-label">Уточнение к месту (необязательно)</span>
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

import { FC, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { BrandBackdrop } from '../components/BrandBackdrop';
import { BrandStepper } from '../components/BrandStepper';
import { AvatarUpload } from '../components/AvatarUpload';
import { useCreateEventMutation } from '../queries/events';
import type { CreateEventBody } from '../api/events';

const TITLE_MAX = 255;
const LOCATION_MAX = 500;
const PARTICIPANT_MIN = 1;
const PARTICIPANT_MAX = 1000;

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
  const [locationText, setLocationText] = useState('');
  const [eventDatetime, setEventDatetime] = useState('');
  const [participantLimit, setParticipantLimit] = useState(20);
  const [submitError, setSubmitError] = useState<string | null>(null);

  if (!clubId) {
    return (
      <div className="brand-page">
        <BrandBackdrop />
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--brand-ink-3)' }}>
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
    if (!locationText.trim()) return fail('Укажите место');
    if (locationText.trim().length > LOCATION_MAX) {
      return fail(`Место: максимум ${LOCATION_MAX} символов`);
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
      locationText: locationText.trim(),
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
    <div className="brand-page">
      <BrandBackdrop />

      <header className="mc-hero">
        <div className="mc-hero-row">
          <h1>Новое <span className="accent">событие</span></h1>
        </div>
      </header>

      <div className="sklad-create-modal sklad-create-as-page">
        <label className="field">
          <span className="label">Название *</span>
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            maxLength={TITLE_MAX}
            placeholder="Например: Йога в парке"
          />
        </label>

        <div className="field">
          <span className="label">Фото (опц.)</span>
          <AvatarUpload value={photoUrl} onChange={setPhotoUrl} />
        </div>

        <label className="field">
          <span className="label">Описание</span>
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={3}
            placeholder="Дополнительные детали для участников"
          />
        </label>

        <label className="field">
          <span className="label">Место *</span>
          <input
            value={locationText}
            onChange={(e) => setLocationText(e.target.value)}
            maxLength={LOCATION_MAX}
            placeholder="Адрес или описание"
          />
        </label>

        <label className="field">
          <span className="label">Дата и время *</span>
          <div className="brand-datetime">
            <input
              type="datetime-local"
              value={eventDatetime}
              onChange={(e) => setEventDatetime(e.target.value)}
            />
            <span className="brand-datetime-ico" aria-hidden="true"><CalendarIcon /></span>
          </div>
        </label>

        <div className="field">
          <span className="label">Лимит участников *</span>
          <BrandStepper
            value={participantLimit}
            onChange={setParticipantLimit}
            min={PARTICIPANT_MIN}
            max={PARTICIPANT_MAX}
            ariaLabel="Лимит участников"
          />
        </div>

        {submitError && <div className="submit-error">{submitError}</div>}

        <div className="modal-actions">
          <button type="button" className="ghost-btn" onClick={handleCancel}>
            Отмена
          </button>
          <button
            type="button"
            className="primary-btn"
            onClick={handleSubmit}
            disabled={createMut.isPending}
          >
            {createMut.isPending ? 'Создаём…' : 'Создать событие'}
          </button>
        </div>
      </div>
    </div>
  );
};

import { FC, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useHaptic } from '../../hooks/useHaptic';
import { BrandStepper } from '../BrandStepper';
import { AvatarUpload } from '../AvatarUpload';
import { LocationPickerSheet } from './LocationPickerSheet';
import { useCreateEventMutation } from '../../queries/events';
import { useSaveEventTemplateMutation } from '../../queries/eventTemplates';
import { formatLeadInterval } from '../../utils/formatters';
import { isoWeekdayOf, localTimeOf, nextOccurrenceLocal } from '../../utils/eventTemplate';
import type { CreateEventBody } from '../../api/events';
import type { EventTemplateDto, SaveEventTemplateBody } from '../../api/eventTemplates';
import type { GeoPoint } from '../../utils/yandexMaps';

const TITLE_MAX = 255;
// Лимит адреса (location_text в БД); адрес приходит из геокодера, но подрезаем защитно.
const LOCATION_MAX = 500;
// Лимит поля «Уточнение к месту» — зеркалит @Size(max=200) на locationHint бэкенда.
const LOCATION_HINT_MAX = 200;
const PARTICIPANT_MIN = 1;
const PARTICIPANT_MAX = 1000;
// Лимит имени шаблона — зеркалит VARCHAR(60) и @Size(max=60) бэкенда.
const TEMPLATE_NAME_MAX = 60;

// Формат встречи: с местами / открытая (V62) / срочная (PO 2026-07-23). Тот же набор ключей,
// что у шага пикера «+» и у шаблона встречи.
export type EventFormat = 'limited' | 'open' | 'urgent';

// Дни недели для выбора расписания шаблона: индекс + 1 = ISO-номер (понедельник = 1).
const WEEKDAYS: string[] = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];

// Пресеты интервала Этапа 2 (за сколько до старта открывается подтверждение мест), в минутах.
// Значения зеркалят CHECK 1080..7200 (V68) и @Min/@Max бэкенда; дефолт 1080 = 18 ч зеркалит
// events.stage2-trigger-minutes-before. Короче 18 часов не бывает — этот случай закрывает
// формат «Срочная встреча». short — подпись насечки на шкале-таймлайне.
const STAGE2_LEAD_PRESETS: { minutes: number; short: string }[] = [
  { minutes: 1080, short: '18 ч' },
  { minutes: 2160, short: '36 ч' },
  { minutes: 4320, short: '3 дня' },
  { minutes: 7200, short: '5 дней' },
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

/**
 * Форма встречи — ОДИН источник полей для двух экранов: создания события и правки шаблона.
 *
 * Разделять их нельзя: поля события уже продублированы шитом редактирования на странице
 * встречи, и третья копия (место с картой, фото, шкала интервала Этапа 2, степпер лимита)
 * гарантированно разъехалась бы. Поэтому режим — проп, а не отдельный компонент.
 *
 * Отличия режима `template` (правка шаблона):
 *  - вместо даты — день недели и время повторов (у шаблона даты нет по построению);
 *  - имя шаблона обязательно и правится тут же, отдельного шага переименования нет;
 *  - формат встречи не меняется: он определяет механику мест и репутации, и менять его
 *    у заготовки значило бы менять её смысл — для другого формата заводится другой шаблон;
 *  - предупреждения «Сделать срочной» / «Этап 2 начнётся сразу» не показываются: они про
 *    конкретную дату, которой у шаблона нет.
 * Спека: docs/modules/event-templates.md § 7.3.
 */

export interface EventFormProps {
  /**
   * `event` — создание встречи (шаблон, если задан, только предзаполняет поля).
   * `template` — правка самого шаблона: на выходе PUT шаблона, встреча не создаётся.
   */
  mode: 'event' | 'template';
  clubId: string;
  template: EventTemplateDto | null;
  initialFormat: EventFormat;
  templateMissing?: boolean;
}

export const EventForm: FC<EventFormProps> = ({
  mode,
  clubId,
  template,
  initialFormat,
  templateMissing = false,
}) => {
  const isTemplateMode = mode === 'template';
  const navigate = useNavigate();
  const haptic = useHaptic();
  const createMut = useCreateEventMutation();
  const saveTemplateMut = useSaveEventTemplateMutation();

  // Формат живёт в состоянии, а не в query-параметре: кнопка «Сделать срочной» иначе
  // переписывала бы строку запроса и роняла бы ?template, вместе с ним — предзаполнение.
  const [format, setFormat] = useState<EventFormat>(initialFormat);
  const isOpenEvent = format === 'open';
  const isUrgentEvent = format === 'urgent';

  const [title, setTitle] = useState(template?.title ?? '');
  const [description, setDescription] = useState(template?.description ?? '');
  const [photoUrl, setPhotoUrl] = useState<string | null>(template?.photoUrl ?? null);
  const [location, setLocation] = useState<PickedLocation | null>(() =>
    template && template.locationLat !== null && template.locationLon !== null
      ? {
          point: { lat: template.locationLat, lon: template.locationLon },
          address: template.locationText ?? '',
        }
      : null,
  );
  const [locationHint, setLocationHint] = useState(template?.locationHint ?? '');
  const [pickerOpen, setPickerOpen] = useState(false);
  // Дата — единственное, чего в шаблоне нет: подставляем ближайшее совпадение «день недели + время».
  const [eventDatetime, setEventDatetime] = useState(() =>
    template ? nextOccurrenceLocal(template.defaultWeekday, template.defaultTime) : '',
  );
  const [participantLimit, setParticipantLimit] = useState(template?.participantLimit ?? 20);
  // Сохранить введённое как шаблон (или перезаписать применённый). По умолчанию выключено:
  // молча плодить шаблоны при каждом создании встречи — не то, чего ждёт организатор.
  // В режиме правки шаблона галочки нет: сохранение шаблона там и есть действие формы.
  const [saveAsTemplate, setSaveAsTemplate] = useState(false);
  const [templateName, setTemplateName] = useState(template?.name ?? '');
  // Расписание повторов — только в режиме правки шаблона: у события вместо этого дата.
  // null = день недели не задан, форма создания оставит дату пустой.
  const [defaultWeekday, setDefaultWeekday] = useState<number | null>(template?.defaultWeekday ?? null);
  const [defaultTime, setDefaultTime] = useState(template?.defaultTime?.slice(0, 5) ?? '');
  // null = организатор интервал не трогал → поле НЕ уходит в body, событие несёт NULL в БД и
  // следует серверному дефолту (в т.ч. staging-ужимке STAGE2_TRIGGER_MINUTES_BEFORE — она жива
  // только для NULL-событий). STAGE2_LEAD_DEFAULT здесь — лишь визуальный маркер активного чипа,
  // фактический дефолт применяет бэкенд. Шаблон переносит СВОЁ значение (у него та же
  // семантика null = «следовать серверному дефолту»), поэтому подставляется как есть.
  const [stage2LeadMinutes, setStage2LeadMinutes] = useState<number | null>(
    template?.stage2LeadMinutes ?? null,
  );
  // Раскрыта ли шкала выбора интервала (дизайн PO 2026-07-23: свёрнутая строка-факт под датой).
  const [leadEditorOpen, setLeadEditorOpen] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const effectiveStage2Lead = stage2LeadMinutes ?? STAGE2_LEAD_DEFAULT;
  const activeLeadIdx = Math.max(0, STAGE2_LEAD_PRESETS.findIndex((p) => p.minutes === effectiveStage2Lead));

  // Ряд насечек — по нему считается ближайшая к пальцу отметка. Мерим именно насечки, а не
  // дорожку: у ряда `justify-content: space-between`, поэтому центры насечек не совпадают
  // с процентами дорожки, и счёт «по ширине трека» промахивался бы на краях.
  const leadTicksRef = useRef<HTMLDivElement>(null);
  // Тянут прямо сейчас — на время протяжки гасим плавность заливки, иначе она догоняет палец.
  const [leadDragging, setLeadDragging] = useState(false);

  /** Индекс отметки, ближайшей к точке X (координата viewport). */
  const leadIndexAtX = (clientX: number): number => {
    const row = leadTicksRef.current;
    if (row === null) return activeLeadIdx;
    let nearest = 0;
    let nearestDistance = Number.POSITIVE_INFINITY;
    Array.from(row.children).forEach((tick, i) => {
      const box = tick.getBoundingClientRect();
      const distance = Math.abs(clientX - (box.left + box.width / 2));
      if (distance < nearestDistance) {
        nearestDistance = distance;
        nearest = i;
      }
    });
    return nearest;
  };

  /** Ставит отметку по индексу. Тик отдаём только при смене значения — иначе протяжка тарахтит. */
  const applyLeadIndex = (index: number) => {
    const preset = STAGE2_LEAD_PRESETS[index];
    if (preset === undefined || preset.minutes === effectiveStage2Lead) return;
    haptic.select();
    setStage2LeadMinutes(preset.minutes);
  };

  const eventTimeMs = eventDatetime ? new Date(eventDatetime).getTime() : null;
  const msToEvent = eventTimeMs !== null && !Number.isNaN(eventTimeMs) ? eventTimeMs - Date.now() : null;
  // Оба предупреждения — про КОНКРЕТНУЮ дату, которой у шаблона нет: в режиме правки молчат.
  // Встреча ближе минимума (18 ч) — такому событию место в формате «срочная» (PO 2026-07-23):
  // предлагаем переключиться кнопкой, не блокируя создание.
  const suggestUrgent =
    !isTemplateMode && !isOpenEvent && !isUrgentEvent &&
    msToEvent !== null && msToEvent < STAGE2_LEAD_DEFAULT * 60_000;
  // Встреча дальше 18 ч, но ближе ВЫБРАННОГО интервала — Этап 2 стартует сразу после
  // создания; предупреждаем и подсказываем отметку короче.
  const stage2StartsImmediately =
    !isTemplateMode && !isOpenEvent && !isUrgentEvent && !suggestUrgent &&
    msToEvent !== null && msToEvent <= effectiveStage2Lead * 60_000;

  const handleMakeUrgent = () => {
    haptic.impact('medium');
    setFormat('urgent');
  };

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
    // У шаблона это НЕ требуется: заготовка «Поход» с плавающей точкой старта законна,
    // а требование остаётся на форме создания встречи (docs/modules/event-templates.md § 5.1).
    if (!isTemplateMode && !location && !locationHint.trim()) {
      return fail('Укажите место на карте или заполните уточнение к месту');
    }
    if (locationHint.trim().length > LOCATION_HINT_MAX) {
      return fail(`Уточнение к месту: максимум ${LOCATION_HINT_MAX} символов`);
    }
    if (!isOpenEvent && (!Number.isInteger(participantLimit) || participantLimit < PARTICIPANT_MIN)) {
      return fail('Лимит участников: целое число больше нуля');
    }
    if (isTemplateMode) return submitTemplate();
    if (!eventDatetime) return fail('Укажите дату и время');
    const eventDate = new Date(eventDatetime);
    if (Number.isNaN(eventDate.getTime())) return fail('Некорректная дата');
    if (eventDate.getTime() <= Date.now()) {
      return fail('Дата события должна быть в будущем');
    }
    if (saveAsTemplate && !templateName.trim()) return fail('Укажите имя шаблона');
    if (saveAsTemplate && templateName.trim().length > TEMPLATE_NAME_MAX) {
      return fail(`Имя шаблона: максимум ${TEMPLATE_NAME_MAX} символов`);
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
      isUrgentEvent,
      // Интервал Этапа 2 — только у обычных событий с местами и только при ЯВНОМ выборе
      // организатора (null = серверный дефолт); open — вне двухэтапки, urgent — сразу в Этапе 2.
      stage2LeadMinutes:
        isOpenEvent || isUrgentEvent || stage2LeadMinutes === null ? undefined : stage2LeadMinutes,
      photoUrl: photoUrl ?? undefined,
    };

    try {
      haptic.impact('medium');
      await createMut.mutateAsync({ clubId, body });
      haptic.notify('success');
      // Шаблон сохраняем ПОСЛЕ встречи и отдельной попыткой: встреча — главное действие, и
      // упавшее сохранение шаблона не должно ни отменять её, ни притворяться, что всё прошло.
      const templateFailed = saveAsTemplate ? !(await persistTemplate(eventDate)) : false;
      navigate('/events', {
        replace: true,
        state: {
          toast: templateFailed
            ? 'Событие создано, но шаблон сохранить не удалось'
            : 'Событие создано',
        },
      });
    } catch (e) {
      console.error('createEvent failed', e);
      haptic.notify('error');
      const msg = e instanceof Error ? e.message : 'Не удалось создать событие';
      setSubmitError(msg);
    }
  };

  /**
   * Тело шаблона из текущего состояния формы. Расписание приходит параметрами, потому что
   * берётся из разных мест: при создании встречи — из выбранной даты, при правке шаблона —
   * из собственных полей расписания.
   */
  const templateBody = (weekday: number | null, time: string | null): SaveEventTemplateBody => ({
    name: templateName.trim(),
    title: title.trim(),
    description: description.trim() || null,
    locationText: location ? location.address.trim().slice(0, LOCATION_MAX) : null,
    locationLat: location?.point.lat ?? null,
    locationLon: location?.point.lon ?? null,
    locationHint: locationHint.trim() || null,
    participantLimit: isOpenEvent ? null : participantLimit,
    isOpenEvent,
    isUrgentEvent,
    stage2LeadMinutes: isOpenEvent || isUrgentEvent ? null : stage2LeadMinutes,
    photoUrl: photoUrl ?? null,
    defaultWeekday: weekday,
    defaultTime: time,
  });

  /**
   * Попутное сохранение шаблона при создании встречи: день недели и время берутся из выбранной
   * даты — в локальной зоне, как их видит организатор. Возвращает признак успеха; ошибку не
   * пробрасывает, чтобы не отменить уже созданную встречу.
   */
  const persistTemplate = async (eventDate: Date): Promise<boolean> => {
    try {
      await saveTemplateMut.mutateAsync({
        clubId,
        templateId: template?.id,
        body: templateBody(isoWeekdayOf(eventDate), localTimeOf(eventDate)),
      });
      return true;
    } catch (e) {
      console.error('saveEventTemplate failed', e);
      return false;
    }
  };

  /**
   * Режим правки шаблона: сохранение шаблона и ЕСТЬ действие формы, поэтому ошибка тут не
   * глотается (в отличие от попутного [persistTemplate]), а показывается в форме.
   */
  const submitTemplate = async () => {
    const name = templateName.trim();
    if (!name) return fail('Укажите имя шаблона');
    if (name.length > TEMPLATE_NAME_MAX) {
      return fail(`Имя шаблона: максимум ${TEMPLATE_NAME_MAX} символов`);
    }
    // День недели без времени подставил бы полночь — почти наверняка не то, что имели в виду.
    if (defaultWeekday !== null && !defaultTime) {
      return fail('Укажите время повтора или уберите день недели');
    }
    try {
      haptic.impact('medium');
      await saveTemplateMut.mutateAsync({
        clubId,
        templateId: template?.id,
        body: templateBody(defaultWeekday, defaultTime ? `${defaultTime}:00` : null),
      });
      haptic.notify('success');
      navigate(-1);
    } catch (e) {
      console.error('saveEventTemplate failed', e);
      haptic.notify('error');
      setSubmitError(e instanceof Error ? e.message : 'Не удалось сохранить шаблон');
    }
  };

  const handleCancel = () => {
    haptic.impact('light');
    navigate(-1);
  };

  return (
    <div className="rd-page">
      <div className="rd-ft-eyebrow">{isTemplateMode ? 'Шаблон встречи' : 'Создание'}</div>
      <h1 className="rd-page-h" style={{ marginBottom: 18 }}>
        {isTemplateMode
          ? 'Правка шаблона'
          : isOpenEvent ? 'Открытая встреча' : isUrgentEvent ? 'Срочная встреча' : 'Новое событие'}
      </h1>
      {isTemplateMode && (
        <div className="rd-hint" style={{ marginTop: -10, marginBottom: 14 }}>
          Правки в шаблоне не трогают уже созданные по нему встречи. Формат встречи
          ({isOpenEvent ? 'открытая' : isUrgentEvent ? 'срочная' : 'с местами'}) не меняется —
          для другого формата заведите отдельный шаблон.
        </div>
      )}
      {!isTemplateMode && isOpenEvent && (
        <div className="rd-hint" style={{ marginTop: -10, marginBottom: 14 }}>
          Без лимита участников — приходят все, кто подтвердил. Репутация здесь не считается
          совсем: ни плюсов за посещение, ни штрафов за отказ или неявку.
        </div>
      )}
      {!isTemplateMode && isUrgentEvent && (
        <div className="rd-hint" style={{ marginTop: -10, marginBottom: 14 }}>
          Без этапа голосования — участники сразу подтверждают места, уведомление уйдёт
          немедленно. Репутация работает как у обычного события с местами.
        </div>
      )}
      {!isTemplateMode && template && (
        <div className="rd-hint" style={{ marginTop: -10, marginBottom: 14 }}>
          📋 Заполнено по шаблону «{template.name}». Правьте что угодно — на сам шаблон это
          не влияет.
        </div>
      )}
      {templateMissing && (
        <div className="rd-hint" style={{ marginTop: -10, marginBottom: 14 }}>
          Шаблон не найден — возможно, его удалили. Форма открыта пустой.
        </div>
      )}

      <div className="rd-form">
        {/* Имя шаблона — ярлык списка выбора, отдельный от названия встречи. В режиме правки
            это обычное поле формы: отдельного шага переименования больше нет. */}
        {isTemplateMode && (
          <label className="rd-field">
            <span className="rd-label">Имя шаблона <span className="rd-req">*</span></span>
            <input
              className="rd-input"
              value={templateName}
              onChange={(e) => setTemplateName(e.target.value)}
              maxLength={TEMPLATE_NAME_MAX}
              placeholder="Например: Разговорный клуб (вторники)"
            />
            <span className="rd-hint">Под этим именем шаблон виден в списке «Готовые шаблоны».</span>
          </label>
        )}

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

        {/* У шаблона даты нет по построению — вместо неё расписание повторов, из которого
            форма создания подставит ближайшее будущее совпадение. */}
        {isTemplateMode ? (
          <div className="rd-field" style={!isOpenEvent && !isUrgentEvent ? { marginBottom: 0 } : undefined}>
            <span className="rd-label">Когда обычно проходит</span>
            <div className="rd-seg rd-seg-flush" role="group" aria-label="День недели">
              {WEEKDAYS.map((label, i) => {
                const iso = i + 1;
                const active = defaultWeekday === iso;
                return (
                  <button
                    key={label}
                    type="button"
                    className={active ? 'rd-seg-btn rd-active' : 'rd-seg-btn'}
                    aria-pressed={active}
                    onClick={() => {
                      haptic.select();
                      // Повторный тап снимает день — тогда дата в форме создания останется пустой.
                      setDefaultWeekday(active ? null : iso);
                    }}
                  >
                    {label}
                  </button>
                );
              })}
            </div>
            <input
              className="rd-input"
              style={{ marginTop: 10 }}
              type="time"
              value={defaultTime}
              onChange={(e) => setDefaultTime(e.target.value)}
              aria-label="Время начала"
            />
            <span className="rd-hint">
              {defaultWeekday === null
                ? 'День не выбран — при создании встречи дата останется пустой.'
                : 'При создании встречи подставится ближайший такой день с этим временем.'}
            </span>
          </div>
        ) : (
          <label className="rd-field" style={!isOpenEvent && !isUrgentEvent ? { marginBottom: 0 } : undefined}>
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
        )}

        {/* Интервал Этапа 2 (V67/V68) — визуально привязан к дате; у открытой встречи Этапа 2
            нет, у срочной он не настраивается (сразу stage_2). Свёрнуто: строка-факт.
            По «Изменить»: шкала-таймлайн с насечками-пресетами. */}
        {!isOpenEvent && !isUrgentEvent && (
          <div className="rd-field">
            <button
              type="button"
              className="rd-s2-note"
              onClick={() => { haptic.impact('light'); setLeadEditorOpen((v) => !v); }}
            >
              <span className="rd-s2-dot" aria-hidden="true">🎟</span>
              <span className="rd-s2-txt">
                <span>Подтверждение мест</span>
                <b>за {formatLeadInterval(effectiveStage2Lead)}</b>
              </span>
              <span className="rd-s2-edit">{leadEditorOpen ? 'Скрыть' : 'Изменить'}</span>
            </button>
            {leadEditorOpen && (
              <div className="rd-s2-timeline">
                {/*
                  Полоса тянется пальцем и прилипает к ближайшей отметке. Жест снят только с самой
                  шкалы, не со всего блока: подпись под ней прокручивает страницу как обычный текст.

                  `data-swipe-nav="off"` обязателен — навигационный свайп ловится со всего экрана,
                  и без пометки протяжка по шкале уносила бы со страницы создания вместе с уже
                  введённой формой. `touch-action: none` (в CSS) глушит вертикальную прокрутку
                  под пальцем, иначе браузер перехватывает жест и значение прыгает.
                */}
                <div
                  className={leadDragging ? 'rd-s2-scale rd-s2-dragging' : 'rd-s2-scale'}
                  data-swipe-nav="off"
                  onPointerDown={(e) => {
                    e.currentTarget.setPointerCapture(e.pointerId);
                    setLeadDragging(true);
                    applyLeadIndex(leadIndexAtX(e.clientX));
                  }}
                  // Признак «палец ещё на шкале» берём у захвата указателя, а не из состояния:
                  // setLeadDragging применяется асинхронно, и первое движение после нажатия
                  // читало бы ещё false — начало протяжки терялось бы.
                  onPointerMove={(e) => {
                    if (!e.currentTarget.hasPointerCapture(e.pointerId)) return;
                    applyLeadIndex(leadIndexAtX(e.clientX));
                  }}
                  onPointerUp={(e) => {
                    e.currentTarget.releasePointerCapture(e.pointerId);
                    setLeadDragging(false);
                  }}
                  onPointerCancel={() => setLeadDragging(false)}
                >
                  <div className="rd-s2-track">
                    <span
                      className="rd-s2-fill"
                      style={{ width: `${(activeLeadIdx / (STAGE2_LEAD_PRESETS.length - 1)) * 100}%` }}
                    />
                  </div>
                  <div className="rd-s2-ticks" ref={leadTicksRef}>
                    {STAGE2_LEAD_PRESETS.map((p, i) => (
                      <button
                        key={p.minutes}
                        type="button"
                        className={`rd-s2-tick${effectiveStage2Lead === p.minutes ? ' rd-active' : ''}`}
                        // Тап по насечке остаётся отдельным обработчиком ради клавиатуры: указателем
                        // значение уже поставил onPointerDown выше, и повторный вызов гасится
                        // проверкой «значение не изменилось» внутри applyLeadIndex.
                        onClick={() => applyLeadIndex(i)}
                      >
                        <span className="rd-s2-knob" aria-hidden="true" />
                        <span>{p.short}</span>
                      </button>
                    ))}
                  </div>
                </div>
                <span className="rd-hint">
                  До этого момента идёт голосование «Пойду / Возможно», затем участники
                  подтверждают свои места.
                </span>
              </div>
            )}
            {suggestUrgent && (
              <span className="rd-hint rd-s2-warn">
                ⚡️ До встречи меньше 18 часов — такому событию лучше быть срочной встречей:
                без голосования, сразу подтверждение мест.
                <button type="button" className="rd-s2-switch" onClick={handleMakeUrgent}>
                  Сделать срочной
                </button>
              </span>
            )}
            {stage2StartsImmediately && (
              <span className="rd-hint rd-s2-warn">
                ⚡️ До встречи меньше выбранного интервала — подтверждение мест начнётся сразу
                после создания. Чтобы сначала прошло голосование, выберите отметку короче
                времени до встречи.
              </span>
            )}
          </div>
        )}

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

        {/* Попутное сохранение шаблона при создании встречи: завести новый из этой встречи
            или перезаписать применённый. В режиме правки шаблона галочки нет — там сохранение
            шаблона и есть действие формы (docs/modules/event-templates.md § 7.1). */}
        {!isTemplateMode && (
        <div className="rd-field">
          <label className="rd-check">
            <input
              type="checkbox"
              checked={saveAsTemplate}
              onChange={(e) => {
                haptic.impact('light');
                setSaveAsTemplate(e.target.checked);
              }}
            />
            <span>
              {template ? `Обновить шаблон «${template.name}»` : 'Сохранить как шаблон'}
            </span>
          </label>
          {saveAsTemplate && (
            <>
              <input
                className="rd-input"
                style={{ marginTop: 10 }}
                value={templateName}
                onChange={(e) => setTemplateName(e.target.value)}
                maxLength={TEMPLATE_NAME_MAX}
                placeholder="Например: Разговорный клуб (вторники)"
              />
              <span className="rd-hint">
                Сохранится всё, кроме даты: вместо неё шаблон запомнит день недели и время.
              </span>
            </>
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
            disabled={createMut.isPending || saveTemplateMut.isPending}
          >
            {isTemplateMode
              ? (saveTemplateMut.isPending ? 'Сохраняем…' : 'Сохранить шаблон')
              : (createMut.isPending ? 'Создаём…' : 'Создать событие')}
          </button>
        </div>
      </div>

      {pickerOpen && (
        <LocationPickerSheet
          initial={location?.point ?? null}
          clubId={clubId}
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
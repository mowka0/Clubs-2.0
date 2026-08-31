import { FC, useEffect, useRef, useState } from 'react';
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
import type { EventFormat } from '../../types/api';

const TITLE_MAX = 255;
// Лимит адреса (location_text в БД); адрес приходит из геокодера, но подрезаем защитно.
const LOCATION_MAX = 500;
// Лимит поля «Уточнение к месту» — зеркалит @Size(max=200) на locationHint бэкенда.
const LOCATION_HINT_MAX = 200;
const PARTICIPANT_MIN = 1;
const PARTICIPANT_MAX = 1000;
// Лимит имени шаблона — зеркалит VARCHAR(60) и @Size(max=60) бэкенда.
const TEMPLATE_NAME_MAX = 60;


// Дни недели для выбора расписания шаблона: индекс + 1 = ISO-номер (понедельник = 1).
const WEEKDAYS: string[] = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];

// Пресеты дедлайна НАБОРА СОСТАВА (за сколько до старта набор закрывается), в минутах.
// Значения зеркалят @Min(360)/@Max(7200) бэкенда; дефолт 1080 = 18 ч зеркалит
// events.stage2-trigger-minutes-before. Нижняя граница опущена с 18 ч до 6 ч (V83): под смысл
// «набор» 18 часов велики. CHECK в БД ещё шире (60..7200) — короче 6 ч интервал рождается только
// продлением набора из DM организатору. short — подпись насечки на шкале-таймлайне.
const STAGE2_LEAD_PRESETS: { minutes: number; short: string }[] = [
  { minutes: 360, short: '6 ч' },
  { minutes: 720, short: '12 ч' },
  { minutes: 1080, short: '18 ч' },
  { minutes: 2160, short: '36 ч' },
  { minutes: 4320, short: '3 дня' },
];
const STAGE2_LEAD_DEFAULT = 1080;
// Подписи формата в форме: заголовок экрана, поле лимита и правило под степпером. В отличие от
// пикера здесь число уже выбрано, поэтому правило называет его вслух — это ровно то обещание,
// которое система исполнит в дедлайн набора.
const FORMAT_TEXTS: Record<EventFormat, {
  pageTitle: string;
  limitLabel: string;
  rule: (limit: number) => string;
}> = {
  min: {
    pageTitle: 'Минимум участников',
    limitLabel: 'Минимум участников',
    rule: (n) => `Собираемся, если будет минимум ${n} ${plural(n, 'человек', 'человека', 'человек')}. `
      + 'Не наберём к закрытию набора — встреча отменится.',
  },
  max: {
    pageTitle: 'Максимум участников',
    limitLabel: 'Сколько всего мест',
    rule: (n) => `Мест ${n}. Встреча состоится в любом случае, кто не успел — встанет в очередь на замену.`,
  },
  any: {
    pageTitle: 'Сколько придёт',
    limitLabel: '',
    rule: () => '',
  },
};

/** Русская форма числительного для «минимум 5 человек». */
function plural(n: number, one: string, few: string, many: string): string {
  const mod100 = n % 100;
  const mod10 = n % 10;
  if (mod100 >= 11 && mod100 <= 14) return many;
  if (mod10 === 1) return one;
  if (mod10 >= 2 && mod10 <= 4) return few;
  return many;
}

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
 *  - правила «до встречи меньше 6 часов» не показываются: они про конкретную дату, которой
 *    у шаблона нет.
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

  // Формат живёт в состоянии, а не в query-параметре: кнопка «Выбрать максимум» иначе
  // переписывала бы строку запроса и роняла бы ?template, вместе с ним — предзаполнение.
  const [format, setFormat] = useState<EventFormat>(initialFormat);
  const hasLimit = format !== 'any';
  const texts = FORMAT_TEXTS[format];

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
    // Насечка дальше оставшегося до встречи времени недоступна (V83): такой набор закрылся бы
    // ещё до создания встречи, и организатор мгновенно получал бы «состав не набрался».
    if (isLeadDisabled(preset.minutes)) return;
    haptic.select();
    setStage2LeadMinutes(preset.minutes);
  };

  const eventTimeMs = eventDatetime ? new Date(eventDatetime).getTime() : null;
  const msToEvent = eventTimeMs !== null && !Number.isNaN(eventTimeMs) ? eventTimeMs - Date.now() : null;
  // Набор не помещается до начала встречи. Сравниваем с ВЫБРАННЫМ интервалом, а не с самым
  // коротким пресетом: сервер проверяет ровно его (`EventService.requireRosterFitsBeforeStart`),
  // и захардкоженные 6 часов пропускали бы, например, встречу через 10 часов с дефолтным
  // интервалом 18 ч — форма сказала бы «ок», а бэкенд ответил 400.
  // Правило про КОНКРЕТНУЮ дату, которой у шаблона нет, — в режиме правки молчит. Смысл разный по
  // форматам: «минимум» так создать нельзя (дедлайн в прошлом отменил бы встречу немедленно),
  // «максимум» — можно, и это ровно бывший формат «срочная»: состав закроется сразу, места
  // займут те, кто откликнется первым.
  const rosterTooLate =
    !isTemplateMode && hasLimit && msToEvent !== null && msToEvent < effectiveStage2Lead * 60_000;

  /**
   * Насечка недоступна, если её интервал не помещается в оставшееся до встречи время: набор,
   * который закрывается в прошлом, — это мгновенный недобор, а не выбор. Заменяет прежнее
   * предупреждение «Этап 2 начнётся сразу после создания»: случай стал невыбираемым (V83).
   * У шаблона даты нет — там доступны все насечки.
   */
  const isLeadDisabled = (minutes: number): boolean =>
    !isTemplateMode && msToEvent !== null && msToEvent <= minutes * 60_000;

  // Выбранное значение перестало помещаться (сдвинули дату назад) — опускаем до ближайшего
  // допустимого, иначе форма молча отправила бы заведомо просроченный набор.
  useEffect(() => {
    if (isTemplateMode || !hasLimit || msToEvent === null) return;
    if (!isLeadDisabled(effectiveStage2Lead)) return;
    const fits = [...STAGE2_LEAD_PRESETS].reverse().find((p) => !isLeadDisabled(p.minutes));
    if (fits !== undefined && fits.minutes !== effectiveStage2Lead) setStage2LeadMinutes(fits.minutes);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [eventDatetime, effectiveStage2Lead, hasLimit, isTemplateMode]);

  const handleSwitchToMax = () => {
    haptic.impact('medium');
    setFormat('max');
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
    if (hasLimit && (!Number.isInteger(participantLimit) || participantLimit < PARTICIPANT_MIN)) {
      return fail(`${texts.limitLabel}: целое число больше нуля`);
    }
    if (isTemplateMode) return submitTemplate();
    if (!eventDatetime) return fail('Укажите дату и время');
    const eventDate = new Date(eventDatetime);
    if (Number.isNaN(eventDate.getTime())) return fail('Некорректная дата');
    if (eventDate.getTime() <= Date.now()) {
      return fail('Дата события должна быть в будущем');
    }
    // Зеркалит серверный гард: у «минимума» набор обязан помещаться до начала встречи, иначе
    // ближайший тик отменит её, не дав никому проголосовать.
    if (format === 'min' && rosterTooLate) {
      return fail(
        `Набор длится ${formatLeadInterval(effectiveStage2Lead)}, а до встречи меньше — `
        + 'выберите «Максимум участников» или сдвиньте дату',
      );
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
      // Пара «лимит + формат»: бэкенд валидирует их согласованность (any ⟺ лимита нет).
      participantLimit: hasLimit ? participantLimit : null,
      format,
      // Интервал набора — только у форматов с лимитом и только при ЯВНОМ выборе организатора
      // (null = серверный дефолт); у «сколько придёт» набора нет вовсе.
      stage2LeadMinutes: !hasLimit || stage2LeadMinutes === null ? undefined : stage2LeadMinutes,
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
    participantLimit: hasLimit ? participantLimit : null,
    format,
    stage2LeadMinutes: hasLimit ? stage2LeadMinutes : null,
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
        {isTemplateMode ? 'Правка шаблона' : texts.pageTitle}
      </h1>
      {isTemplateMode && (
        <div className="rd-hint" style={{ marginTop: -10, marginBottom: 14 }}>
          Правки в шаблоне не трогают уже созданные по нему встречи. Формат встречи
          ({texts.pageTitle.toLowerCase()}) не меняется — для другого формата заведите
          отдельный шаблон.
        </div>
      )}
      {!isTemplateMode && !hasLimit && (
        <div className="rd-hint" style={{ marginTop: -10, marginBottom: 14 }}>
          Без ограничений — приходят все желающие. Репутация здесь не считается совсем:
          ни плюсов за посещение, ни штрафов за отказ или неявку.
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
          <div className="rd-field" style={hasLimit ? { marginBottom: 0 } : undefined}>
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
          <label className="rd-field" style={hasLimit ? { marginBottom: 0 } : undefined}>
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

        {/* Интервал набора состава — визуально привязан к дате; у формата «сколько придёт»
            набора нет вовсе. Свёрнуто: строка-факт. По «Изменить»: шкала-таймлайн с насечками. */}
        {hasLimit && (
          <div className="rd-field">
            <button
              type="button"
              className="rd-s2-note"
              onClick={() => { haptic.impact('light'); setLeadEditorOpen((v) => !v); }}
            >
              <span className="rd-s2-dot" aria-hidden="true">{format === 'min' ? '🎯' : '🎟'}</span>
              <span className="rd-s2-txt">
                <span>Набор состава</span>
                <b>закрывается за {formatLeadInterval(effectiveStage2Lead)}</b>
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
                        disabled={isLeadDisabled(p.minutes)}
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
                  {msToEvent !== null && isLeadDisabled(STAGE2_LEAD_PRESETS[STAGE2_LEAD_PRESETS.length - 1].minutes)
                    ? `До встречи ${formatLeadInterval(Math.floor(msToEvent / 60_000))} — более длинные интервалы не помещаются.`
                    : format === 'min'
                      ? 'До этого момента идёт набор. Наберётся состав — встреча состоится, не наберётся — отменится.'
                      : 'До этого момента идёт набор. В этот момент состав закроется — тем, кто успел.'}
                </span>
              </div>
            )}
            {rosterTooLate && format === 'min' && (
              <span className="rd-hint rd-s2-warn">
                До встречи меньше {formatLeadInterval(effectiveStage2Lead)} — набор не успеет
                закрыться, и встреча отменится сразу после создания. Выберите «Максимум
                участников», сдвиньте дату или укоротите набор.
                <button type="button" className="rd-s2-switch" onClick={handleSwitchToMax}>
                  Выбрать «Максимум участников»
                </button>
              </span>
            )}
            {rosterTooLate && format === 'max' && (
              <span className="rd-hint">
                До встречи меньше {formatLeadInterval(effectiveStage2Lead)} — набор закроется
                сразу, места займут те, кто откликнется первым.
              </span>
            )}
          </div>
        )}

        {/* «Сколько придёт»: лимита нет — степпер не рендерится вовсе. */}
        {hasLimit && (
          <div className="rd-field">
            {/* Подпись и правило зависят от формата: одно и то же число значит «нужно минимум
                столько» либо «всего столько мест». Правило под степпером — то самое обещание,
                которое система исполнит в дедлайн набора. */}
            <span className="rd-label">
              {texts.limitLabel} <span className="rd-req">*</span>
            </span>
            <BrandStepper
              value={participantLimit}
              onChange={setParticipantLimit}
              min={PARTICIPANT_MIN}
              max={PARTICIPANT_MAX}
              ariaLabel={texts.limitLabel}
            />
            <span className="rd-hint">{texts.rule(participantLimit)}</span>
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
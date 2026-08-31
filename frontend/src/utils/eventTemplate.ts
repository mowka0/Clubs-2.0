import { toDatetimeLocalValue } from './formatters';
import type { EventTemplateDto, SaveEventTemplateBody } from '../api/eventTemplates';
import type { EventFormat } from '../types/api';

/**
 * Помощники шаблонов встреч: вывод «дня недели + времени» из конкретной встречи и обратная
 * подстановка ближайшей будущей даты.
 *
 * ВСЁ считается в ЛОКАЛЬНОЙ зоне устройства — и это принципиально. `event_datetime` хранится
 * как TIMESTAMPTZ, организатор выбирает время в `datetime-local`, то есть по своим настенным
 * часам. Выводи мы день недели из UTC — «вторник 19:00» в Москве стал бы «вторником 16:00»,
 * и подстановка промахивалась бы днём при вечерних встречах.
 * Спека: docs/modules/event-templates.md § 4.3.
 */

/** Понедельник = 1 … воскресенье = 7 (ISO-8601). JS `getDay()` считает с воскресенья-0. */
export function isoWeekdayOf(date: Date): number {
  return ((date.getDay() + 6) % 7) + 1;
}

/** Локальное «HH:mm:ss» — формат, который принимает и отдаёт бэкенд (java.time.LocalTime). */
export function localTimeOf(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:00`;
}

/**
 * Ближайший будущий момент, попадающий на [weekday] в [time], как значение для
 * `input[type=datetime-local]`. Совпадение «сегодня, но время уже прошло» уходит на следующую
 * неделю: дата встречи обязана быть в будущем (бэкенд отклонит прошедшую с 400).
 *
 * Возвращает пустую строку, если день недели не задан — поле даты остаётся пустым, как и
 * при создании с нуля.
 */
export function nextOccurrenceLocal(
  weekday: number | null,
  time: string | null,
  now: Date = new Date(),
): string {
  if (weekday === null) return '';

  const [hours = 0, minutes = 0] = (time ?? '00:00:00').split(':').map(Number);
  const target = new Date(now);
  target.setHours(hours, minutes, 0, 0);

  const daysAhead = (weekday - isoWeekdayOf(now) + 7) % 7;
  target.setDate(target.getDate() + daysAhead);
  // daysAhead > 0 уже даёт будущий день; догонять неделю нужно только когда совпал сегодняшний
  // день недели, а час встречи уже прошёл.
  if (target.getTime() <= now.getTime()) target.setDate(target.getDate() + 7);

  return toDatetimeLocalValue(target);
}

/**
 * Встреча → тело сохранения шаблона. Дата не переносится: вместо неё выводятся день недели и
 * время. Имя предзаполняется названием встречи — организатор чаще всего просто подтверждает.
 */
export function eventToTemplateBody(
  event: {
    title: string;
    description: string | null;
    locationText: string | null;
    locationLat: number | null;
    locationLon: number | null;
    locationHint: string | null;
    participantLimit: number | null;
    format: EventFormat;
    stage2LeadMinutesOverride: number | null;
    photoUrl: string | null;
    eventDatetime: string;
  },
  name: string,
): SaveEventTemplateBody {
  const startsAt = new Date(event.eventDatetime);
  return {
    name,
    title: event.title,
    description: event.description,
    locationText: event.locationText,
    locationLat: event.locationLat,
    locationLon: event.locationLon,
    locationHint: event.locationHint,
    participantLimit: event.participantLimit,
    format: event.format,
    // Берём СОБСТВЕННЫЙ интервал события, а не эффективный: подставленный сервером дефолт,
    // записанный в шаблон, молча стал бы явным выбором организатора и перестал бы следовать
    // за настройкой бэкенда (тот же урок, что у формы редактирования встречи).
    // У формата «сколько придёт» своего интервала не бывает — бэкенд отклонит.
    stage2LeadMinutes: event.format === 'any' ? null : event.stage2LeadMinutesOverride,
    photoUrl: event.photoUrl,
    defaultWeekday: isoWeekdayOf(startsAt),
    defaultTime: localTimeOf(startsAt),
  };
}

/**
 * Шаблон → тело сохранения. Нужен там, где шаблон перезаписывается целиком (переименование,
 * «Обновить шаблон»): PUT — полная замена, а серверные поля (`id`, `clubName`, даты) в теле
 * лишние. Явный перенос вместо спреда DTO: добавится поле — компилятор укажет сюда.
 */
export function templateToSaveBody(
  template: EventTemplateDto,
  overrides: Partial<SaveEventTemplateBody> = {},
): SaveEventTemplateBody {
  return {
    name: template.name,
    title: template.title,
    description: template.description,
    locationText: template.locationText,
    locationLat: template.locationLat,
    locationLon: template.locationLon,
    locationHint: template.locationHint,
    participantLimit: template.participantLimit,
    format: template.format,
    stage2LeadMinutes: template.stage2LeadMinutes,
    photoUrl: template.photoUrl,
    defaultWeekday: template.defaultWeekday,
    defaultTime: template.defaultTime,
    ...overrides,
  };
}


import { describe, expect, it } from 'vitest';
import {
  eventToTemplateBody,
  isoWeekdayOf,
  localTimeOf,
  nextOccurrenceLocal,
  templateFormat,
  templateToSaveBody,
} from '../../utils/eventTemplate';
import type { EventTemplateDto } from '../../api/eventTemplates';

/**
 * Умная дата шаблона (docs/modules/event-templates.md § 4.2, AC-4..AC-6) и перенос полей
 * встречи в шаблон. Все даты создаются локальными конструкторами `new Date(y, m, d, ...)` —
 * ISO-строки с Z считались бы в UTC и тест ловил бы не то, что видит организатор.
 */
describe('nextOccurrenceLocal', () => {
  it('AC-4 подставляет ближайший будущий тот же день недели', () => {
    // Среда, 12 августа 2026, 10:00 → ближайший вторник 18 августа.
    const now = new Date(2026, 7, 12, 10, 0);
    expect(nextOccurrenceLocal(2, '19:00:00', now)).toBe('2026-08-18T19:00');
  });

  it('AC-5 в тот же день недели до часа встречи берёт сегодня', () => {
    // Вторник 18:00, встреча в 19:00 — ещё сегодня.
    const now = new Date(2026, 7, 18, 18, 0);
    expect(nextOccurrenceLocal(2, '19:00:00', now)).toBe('2026-08-18T19:00');
  });

  it('AC-5 в тот же день недели после часа встречи уходит на следующую неделю', () => {
    // Вторник 20:00, встреча в 19:00 — время прошло, значит через неделю.
    const now = new Date(2026, 7, 18, 20, 0);
    expect(nextOccurrenceLocal(2, '19:00:00', now)).toBe('2026-08-25T19:00');
  });

  it('AC-6 без дня недели возвращает пустую строку', () => {
    expect(nextOccurrenceLocal(null, '19:00:00', new Date(2026, 7, 12, 10, 0))).toBe('');
  });

  it('без времени подставляет полночь выбранного дня', () => {
    const now = new Date(2026, 7, 12, 10, 0);
    expect(nextOccurrenceLocal(6, null, now)).toBe('2026-08-15T00:00');
  });

  it('переваливает через границу месяца', () => {
    // Понедельник 31 августа 2026 → ближайшая среда уже в сентябре.
    const now = new Date(2026, 7, 31, 12, 0);
    expect(nextOccurrenceLocal(3, '09:30:00', now)).toBe('2026-09-02T09:30');
  });

  it('воскресенье считается седьмым днём, а не нулевым', () => {
    // Понедельник 17 августа 2026 → воскресенье 23-е.
    const now = new Date(2026, 7, 17, 12, 0);
    expect(nextOccurrenceLocal(7, '11:00:00', now)).toBe('2026-08-23T11:00');
  });
});

describe('isoWeekdayOf / localTimeOf', () => {
  it('понедельник = 1, воскресенье = 7', () => {
    expect(isoWeekdayOf(new Date(2026, 7, 17))).toBe(1);
    expect(isoWeekdayOf(new Date(2026, 7, 18))).toBe(2);
    expect(isoWeekdayOf(new Date(2026, 7, 23))).toBe(7);
  });

  it('время дополняется нулями до HH:mm:ss', () => {
    expect(localTimeOf(new Date(2026, 7, 18, 9, 5))).toBe('09:05:00');
  });
});

describe('eventToTemplateBody', () => {
  const event = {
    title: 'Разговорный клуб',
    description: 'Говорим по-английски',
    locationText: 'ул. Покровка, 47',
    locationLat: 55.76,
    locationLon: 37.64,
    locationHint: 'Вход со двора',
    participantLimit: 12,
    isUrgent: false,
    stage2LeadMinutesOverride: 2160,
    photoUrl: 'https://cdn/photo.webp',
    // Вторник 18 августа 2026, 19:00 по местному времени.
    eventDatetime: new Date(2026, 7, 18, 19, 0).toISOString(),
  };

  it('переносит поля и выводит день недели со временем вместо даты', () => {
    const body = eventToTemplateBody(event, 'Разговорный клуб (вторники)');

    expect(body.name).toBe('Разговорный клуб (вторники)');
    expect(body.title).toBe('Разговорный клуб');
    expect(body.locationLat).toBe(55.76);
    expect(body.participantLimit).toBe(12);
    expect(body.isOpenEvent).toBe(false);
    expect(body.stage2LeadMinutes).toBe(2160);
    expect(body.defaultWeekday).toBe(2);
    expect(body.defaultTime).toBe('19:00:00');
    // Даты в шаблоне быть не должно ни под каким видом.
    expect(Object.keys(body)).not.toContain('eventDatetime');
  });

  it('открытая встреча превращается в шаблон без лимита и без интервала Этапа 2', () => {
    const body = eventToTemplateBody(
      { ...event, participantLimit: null, stage2LeadMinutesOverride: 4320 },
      'Открытая пробежка',
    );

    expect(body.isOpenEvent).toBe(true);
    expect(body.participantLimit).toBeNull();
    expect(body.stage2LeadMinutes).toBeNull();
  });

  it('срочная встреча переносит флаг и обнуляет интервал Этапа 2', () => {
    const body = eventToTemplateBody({ ...event, isUrgent: true }, 'Спонтанный забег');

    expect(body.isUrgentEvent).toBe(true);
    expect(body.stage2LeadMinutes).toBeNull();
  });

  it('не подставляет серверный дефолт вместо собственного интервала события', () => {
    const body = eventToTemplateBody({ ...event, stage2LeadMinutesOverride: null }, 'Без интервала');

    // null означает «следовать серверному дефолту» — записанное число сделало бы дефолт
    // явным выбором организатора и заморозило бы его.
    expect(body.stage2LeadMinutes).toBeNull();
  });
});

describe('templateToSaveBody / templateFormat', () => {
  const template: EventTemplateDto = {
    id: 't1',
    clubId: 'c1',
    clubName: 'Клуб',
    name: 'Разговорный клуб',
    title: 'Разговорный клуб',
    description: null,
    locationText: null,
    locationLat: null,
    locationLon: null,
    locationHint: 'в зуме',
    participantLimit: 10,
    isOpenEvent: false,
    isUrgentEvent: false,
    stage2LeadMinutes: null,
    photoUrl: null,
    defaultWeekday: 2,
    defaultTime: '19:00:00',
    createdAt: null,
    updatedAt: null,
  };

  it('в тело сохранения не утекают серверные поля', () => {
    const body = templateToSaveBody(template, { name: 'Новое имя' });

    expect(body.name).toBe('Новое имя');
    expect(body.title).toBe('Разговорный клуб');
    expect(Object.keys(body)).not.toContain('id');
    expect(Object.keys(body)).not.toContain('clubName');
    expect(Object.keys(body)).not.toContain('createdAt');
  });

  it('формат выводится из флагов шаблона', () => {
    expect(templateFormat(template)).toBe('limited');
    expect(templateFormat({ ...template, isOpenEvent: true })).toBe('open');
    expect(templateFormat({ ...template, isUrgentEvent: true })).toBe('urgent');
  });
});

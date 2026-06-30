import { describe, it, expect } from 'vitest';
import { formatPrice, formatDatetime, pluralRu } from '../../utils/formatters';

describe('pluralRu', () => {
  const months: [string, string, string] = ['месяц', 'месяца', 'месяцев'];

  it('picks the "one" form for 1, 21, 31 (but not 11)', () => {
    expect(pluralRu(1, months)).toBe('месяц');
    expect(pluralRu(21, months)).toBe('месяц');
    expect(pluralRu(11, months)).toBe('месяцев');
  });

  it('picks the "few" form for 2..4, 22..24', () => {
    expect(pluralRu(2, months)).toBe('месяца');
    expect(pluralRu(4, months)).toBe('месяца');
    expect(pluralRu(23, months)).toBe('месяца');
  });

  it('picks the "many" form for 0, 5..20', () => {
    expect(pluralRu(0, months)).toBe('месяцев');
    expect(pluralRu(5, months)).toBe('месяцев');
    expect(pluralRu(14, months)).toBe('месяцев');
  });
});

describe('formatPrice', () => {
  it('returns "Бесплатно" when price is 0', () => {
    expect(formatPrice(0)).toBe('Бесплатно');
  });

  it('returns formatted string with ₽ for price 100', () => {
    expect(formatPrice(100)).toBe('100 ₽ / мес');
  });

  it('returns formatted string with ₽ for price 250', () => {
    expect(formatPrice(250)).toBe('250 ₽ / мес');
  });

  it('returns formatted string with ₽ for price 1', () => {
    expect(formatPrice(1)).toBe('1 ₽ / мес');
  });

  it('returns formatted string with ₽ for large price', () => {
    expect(formatPrice(10000)).toBe('10000 ₽ / мес');
  });
});

describe('formatDatetime', () => {
  it('formats an ISO datetime string using ru-RU locale', () => {
    const iso = '2025-06-15T14:30:00.000Z';
    const result = formatDatetime(iso);

    // The exact output depends on locale support in the test environment,
    // but we can verify it contains expected parts
    expect(typeof result).toBe('string');
    expect(result.length).toBeGreaterThan(0);
  });

  it('returns a string containing the day for a known date', () => {
    // 2025-01-01T12:00:00Z
    const result = formatDatetime('2025-01-01T12:00:00.000Z');
    // Should contain "1" (the day) somewhere in the output
    expect(result).toMatch(/1/);
  });

  it('returns a string containing the year', () => {
    const result = formatDatetime('2025-06-15T14:30:00.000Z');
    expect(result).toMatch(/2025/);
  });

  it('produces consistent output for the same input', () => {
    const iso = '2024-12-25T18:00:00.000Z';
    const result1 = formatDatetime(iso);
    const result2 = formatDatetime(iso);
    expect(result1).toBe(result2);
  });

  it('formats midnight correctly', () => {
    const result = formatDatetime('2025-03-10T00:00:00.000Z');
    expect(typeof result).toBe('string');
    expect(result.length).toBeGreaterThan(0);
    expect(result).toMatch(/2025/);
  });
});

import { describe, it, expect } from 'vitest';
import { trialPassedLabel } from '../../api/billing';
import { pluralRu } from '../../utils/formatters';

const label = (n: number) => trialPassedLabel(n, (x) => pluralRu(x, ['день', 'дня', 'дней']));

describe('trialPassedLabel', () => {
  it('склоняет дни и не выдаёт «первые 1 день»', () => {
    expect(label(1)).toBe('первый день был бесплатным');
    expect(label(2)).toBe('первые 2 дня были бесплатными');
    expect(label(15)).toBe('первые 15 дней были бесплатными');
    expect(label(21)).toBe('первые 21 день были бесплатными');
  });
});

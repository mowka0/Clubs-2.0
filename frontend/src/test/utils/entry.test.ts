import { describe, it, expect } from 'vitest';
import { shouldShowLanding } from '../../entry';

describe('shouldShowLanding', () => {
  it('вне Telegram корень и любой адрес приложения показывают лендинг', () => {
    expect(shouldShowLanding('/', false)).toBe(true);
    expect(shouldShowLanding('/clubs/abc', false)).toBe(true);
  });

  it('публичные веб-адреса рендерит роутер, а не лендинг', () => {
    expect(shouldShowLanding('/about', false)).toBe(false);
    expect(shouldShowLanding('/about/', false)).toBe(false);
    expect(shouldShowLanding('/pay/return', false)).toBe(false);
    expect(shouldShowLanding('/pay/fail', false)).toBe(false);
  });

  it('из Telegram лендинг не показывается никогда', () => {
    expect(shouldShowLanding('/', true)).toBe(false);
    expect(shouldShowLanding('/about', true)).toBe(false);
  });
});

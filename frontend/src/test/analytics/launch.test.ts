import { describe, it, expect, vi, beforeEach } from 'vitest';

const { trackMock, launch } = vi.hoisted(() => ({
  trackMock: vi.fn(),
  launch: { startParam: null as string | null, platform: 'ios' },
}));

vi.mock('../../analytics/track', () => ({ track: trackMock }));
vi.mock('../../telegram/sdk', () => ({ getStartParam: () => launch.startParam }));
vi.mock('../../telegram/platform', () => ({ platformName: () => launch.platform }));

import { trackAppOpened, resetLaunchForTests } from '../../analytics/launch';
import { deepLinkKind } from '../../telegram/deepLink';

const UUID = '7c2e1d2a-0000-4000-8000-000000000001';

/** `app_opened` — один раз за запуск, с видом ссылки запуска и платформой (AC-10). */
describe('trackAppOpened', () => {
  beforeEach(() => {
    trackMock.mockReset();
    resetLaunchForTests();
    launch.startParam = null;
    launch.platform = 'ios';
  });

  it('пишет один раз за запуск, повторные вызовы молчат', () => {
    trackAppOpened();
    trackAppOpened();
    expect(trackMock).toHaveBeenCalledTimes(1);
    expect(trackMock).toHaveBeenCalledWith('app_opened', { entry: 'none', platform: 'ios' });
  });

  it('вид ссылки запуска становится entry', () => {
    launch.startParam = `club_${UUID}`;
    launch.platform = 'android';
    trackAppOpened();
    expect(trackMock).toHaveBeenCalledWith('app_opened', { entry: 'club', platform: 'android' });
  });
});

describe('deepLinkKind', () => {
  it('различает пять видов ссылок, остальное — none', () => {
    expect(deepLinkKind(`club_${UUID}`)).toBe('club');
    expect(deepLinkKind(`event_${UUID}`)).toBe('event');
    expect(deepLinkKind(`skladchina_${UUID}`)).toBe('skladchina');
    expect(deepLinkKind(`billing_${UUID}`)).toBe('billing');
    expect(deepLinkKind('invite_0123456789abcdef')).toBe('invite');
    expect(deepLinkKind('ad_vk1')).toBe('none');
    expect(deepLinkKind('club_notauuid')).toBe('none');
    expect(deepLinkKind(null)).toBe('none');
  });
});

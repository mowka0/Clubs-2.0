import { describe, it, expect, vi } from 'vitest';

// Предмет теста — таблица экранов против списка маршрутов роутера; сами экраны и Layout не нужны.
vi.mock('../../components/Layout', () => ({ Layout: () => null, PageFallback: () => null }));
vi.mock('../../components/HomeRoute', () => ({ HomeRoute: () => null }));
vi.mock('../../components/ConnectChatScreen', () => ({ ConnectChatScreen: () => null }));
vi.mock('../../pages/LandingPage', () => ({ LandingPage: () => null }));
vi.mock('../../pages/PrivacyPage', () => ({ PrivacyPage: () => null }));
vi.mock('../../pages/DiscoveryPage', () => ({ DiscoveryPage: () => null }));
vi.mock('../../pages/MyClubsPage', () => ({ MyClubsPage: () => null }));
vi.mock('../../pages/ActivitiesPage', () => ({ ActivitiesPage: () => null }));
vi.mock('../../pages/ProfilePage', () => ({ ProfilePage: () => null }));

import { resolveScreen } from '../../analytics/screens';
import { router } from '../../router';

const UUID = '7c2e1d2a-0000-4000-8000-000000000001';

/** Пути, которые экранами не считаются (product-analytics.md § 3.4): редиректы и страницы вне Layout. */
const NOT_SCREENS = new Set(['/organizer', '/clubs/:id/interior', '/pay/return', '/pay/fail', '/about', '/privacy']);

function concrete(pattern: string): string {
  return pattern
    .replace(':id', UUID)
    .replace(':templateId', 'tpl-1')
    .replace(':userId', UUID)
    .replace(':code', '0123456789abcdef');
}

function routePaths(): string[] {
  const paths: string[] = [];
  const walk = (routes: { path?: string; children?: unknown[] }[]) => {
    routes.forEach((r) => {
      if (r.path) paths.push(r.path);
      if (r.children) walk(r.children as { path?: string; children?: unknown[] }[]);
    });
  };
  walk(router.routes as { path?: string; children?: unknown[] }[]);
  return paths;
}

/** Имена экранов (AC-9): у каждого маршрута роутера есть имя, идентификаторы не утекают в имя. */
describe('resolveScreen', () => {
  it('каждый маршрут роутера известен таблице: имя экрана или осознанное исключение', () => {
    const paths = routePaths();
    expect(paths.length).toBeGreaterThan(15);
    paths.forEach((pattern) => {
      const info = resolveScreen(concrete(pattern));
      if (NOT_SCREENS.has(pattern)) {
        expect(info, pattern).toBeNull();
      } else {
        expect(info?.screen, pattern).toBeDefined();
        expect(info?.screen, pattern).not.toBe('unknown');
      }
    });
  });

  it('клуб идёт в clubId, встреча и сбор — в props, чужой userId и код приглашения не пишутся', () => {
    expect(resolveScreen(`/clubs/${UUID}/manage`)).toEqual({ screen: 'club_manage', clubId: UUID });
    expect(resolveScreen(`/clubs/${UUID.toUpperCase()}`)).toEqual({ screen: 'club', clubId: UUID.toUpperCase() });
    expect(resolveScreen(`/events/${UUID}`)).toEqual({ screen: 'event', props: { eventId: UUID } });
    expect(resolveScreen(`/skladchina/${UUID}`)).toEqual({ screen: 'skladchina', props: { skladchinaId: UUID } });
    expect(resolveScreen(`/debts/with/${UUID}`)).toEqual({ screen: 'debt_pair' });
    expect(resolveScreen('/invite/0123456789abcdef')).toEqual({ screen: 'invite' });
    expect(resolveScreen('/events')).toEqual({ screen: 'activities' });
    expect(resolveScreen('/my-clubs/')).toEqual({ screen: 'my_clubs' });
  });

  it('неизвестный путь — unknown без самого пути', () => {
    expect(resolveScreen('/что-то/секретное')).toEqual({ screen: 'unknown' });
    expect(resolveScreen('/clubs/not-a-uuid')).toEqual({ screen: 'unknown' });
  });
});

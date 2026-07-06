import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { MemberListItemDto, MembershipDto } from '../../types/api';

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));
vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

import { ClubMembersTab } from '../../components/club/ClubMembersTab';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const CLUB_ID = 'club-1';
const DAY = 86_400_000;
const inDays = (n: number) => new Date(Date.now() + n * DAY).toISOString();
const agoDays = (n: number) => new Date(Date.now() - n * DAY).toISOString();

function member(over: Partial<MemberListItemDto> & { userId: string; firstName: string }): MemberListItemDto {
  return {
    lastName: null,
    avatarUrl: null,
    role: 'member',
    joinedAt: agoDays(10),
    trust: 70,
    promiseFulfillmentPct: 90,
    totalConfirmations: 4,
    awards: [],
    accessStatus: 'active',
    subscriptionExpiresAt: null,
    ...over,
  };
}

// One member per bucket: organizer (calm), active-far (calm), expiring-soon, expired, frozen (awaiting).
const ORGANIZER = member({ userId: 'org', firstName: 'Анна', role: 'organizer', trust: null });
const FAR = member({ userId: 'far', firstName: 'Дмитрий', subscriptionExpiresAt: inDays(40) });
const EXPIRING = member({ userId: 'exp', firstName: 'Игорь', subscriptionExpiresAt: inDays(3) });
const EXPIRED = member({ userId: 'expd', firstName: 'Пётр', subscriptionExpiresAt: agoDays(5) });
const FROZEN = member({ userId: 'frz', firstName: 'Мария', accessStatus: 'frozen', joinedAt: agoDays(2) });

function mockMembers(rows: MemberListItemDto[]) {
  server.use(http.get(`*/api/clubs/${CLUB_ID}/members`, () => HttpResponse.json(rows)));
}

describe('ClubMembersTab — de-Stars dashboard', () => {
  it('management view splits members into «Доступ истёк» / «Скоро закончится» / «Оплата вступления» / «Участники»', async () => {
    mockMembers([ORGANIZER, FAR, EXPIRING, EXPIRED, FROZEN]);
    renderWithProviders(<ClubMembersTab clubId={CLUB_ID} isOrganizer managementView />);

    expect(await screen.findByText(/Доступ истёк/)).toBeInTheDocument();
    expect(screen.getByText(/Скоро закончится/)).toBeInTheDocument();
    expect(screen.getByText(/Оплата вступления/)).toBeInTheDocument();
    expect(screen.getByText(/^Участники/)).toBeInTheDocument();

    // Expired + frozen + expiring each expose a «Взнос получен» action; the calm members do not.
    expect(screen.getAllByRole('button', { name: /Взнос получен/ })).toHaveLength(3);
    expect(screen.getByText('Игорь')).toBeInTheDocument();
    expect(screen.getByText('Пётр')).toBeInTheDocument();
    expect(screen.getByText('Мария')).toBeInTheDocument();
  });

  it('an already-expired subscription lands in «Доступ истёк», NOT in «Скоро закончится»', async () => {
    // Смысловой фикс (PO 2026-07-06): «скоро закончится» врал для уже истёкшего окна.
    mockMembers([EXPIRED]);
    renderWithProviders(<ClubMembersTab clubId={CLUB_ID} isOrganizer managementView />);

    expect(await screen.findByText(/Доступ истёк/)).toBeInTheDocument();
    expect(screen.queryByText(/Скоро закончится/)).not.toBeInTheDocument();
    expect(screen.getByText(/истекла .+ назад/)).toBeInTheDocument();
  });

  it('regular-member view shows a flat list with no buckets or actions', async () => {
    // Backend returns null access fields to non-organizers → every row is calm.
    const asMember = (m: MemberListItemDto) => ({ ...m, accessStatus: null, subscriptionExpiresAt: null });
    mockMembers([ORGANIZER, FAR, EXPIRING].map(asMember));
    renderWithProviders(<ClubMembersTab clubId={CLUB_ID} isOrganizer={false} />);

    expect(await screen.findByText(/^Участники/)).toBeInTheDocument();
    expect(screen.queryByText(/Скоро закончится/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Оплата вступления/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Взнос получен/ })).not.toBeInTheDocument();
  });

  it('hides «Новичок» from a non-organizer but shows it to the organizer (asymmetric trust #94)', async () => {
    // trust=null неотличим «нет истории» ↔ «скрыто от чужого зрителя». Обычному участнику фолбэк
    // «Новичок» не показываем (иначе врём тому, у кого история есть); организатору — показываем.
    const newbie = member({ userId: 'nb', firstName: 'Новобранец', trust: null, totalConfirmations: 0, accessStatus: null });

    mockMembers([newbie]);
    const { unmount } = renderWithProviders(<ClubMembersTab clubId={CLUB_ID} isOrganizer={false} />);
    expect(await screen.findByText('Новобранец')).toBeInTheDocument();
    expect(screen.queryByText('Новичок')).not.toBeInTheDocument();
    unmount();

    mockMembers([newbie]);
    renderWithProviders(<ClubMembersTab clubId={CLUB_ID} isOrganizer />);
    expect(await screen.findByText('Новобранец')).toBeInTheDocument();
    expect(screen.getByText('Новичок')).toBeInTheDocument();
  });

  it('organizer on the club page (no managementView) sees a plain roster — no management buckets', async () => {
    // Same data as the management view, but without managementView the attention blocks are suppressed
    // (they live only in Управление → Участники, not duplicated on the club page).
    mockMembers([ORGANIZER, FAR, EXPIRING, FROZEN]);
    renderWithProviders(<ClubMembersTab clubId={CLUB_ID} isOrganizer />);

    expect(await screen.findByText(/^Участники/)).toBeInTheDocument();
    expect(screen.queryByText(/Скоро закончится/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Оплата вступления/)).not.toBeInTheDocument();
  });

  it('on the club page a frozen member is marked «Доступ закрыт» and sinks to the bottom of the list', async () => {
    // Frozen is placed second in the payload — the component must still float it to the end of the flat
    // roster (and ice-mark it) so the organizer spots the no-access member without a management bucket.
    mockMembers([ORGANIZER, FROZEN, FAR, EXPIRING]);
    const { container } = renderWithProviders(<ClubMembersTab clubId={CLUB_ID} isOrganizer />);

    expect(await screen.findByText(/Доступ закрыт/)).toBeInTheDocument();
    const titles = Array.from(container.querySelectorAll('.rd-rep-row .rd-ttl')).map((e) => e.textContent ?? '');
    expect(titles[titles.length - 1]).toContain('Мария'); // frozen member last
  });

  it('shows club-award chips on a member roster card (public, R3)', async () => {
    const decorated = member({
      userId: 'dec', firstName: 'Олег',
      awards: [{ id: 'a-1', emoji: '🔥', label: 'Активист' }],
    });
    mockMembers([decorated]);
    renderWithProviders(<ClubMembersTab clubId={CLUB_ID} isOrganizer={false} />);

    expect(await screen.findByText('Олег')).toBeInTheDocument();
    expect(screen.getByText('Активист')).toBeInTheDocument();
  });

  it('«Взнос получен» calls the dues-paid endpoint and confirms with a toast', async () => {
    mockMembers([FROZEN]);
    let duesPaidCalled = false;
    server.use(
      http.post(`*/api/clubs/${CLUB_ID}/members/${FROZEN.userId}/dues-paid`, () => {
        duesPaidCalled = true;
        return HttpResponse.json({
          id: 'm-1', userId: FROZEN.userId, clubId: CLUB_ID,
          status: 'active', role: 'member', joinedAt: FROZEN.joinedAt, subscriptionExpiresAt: inDays(30),
        } as MembershipDto);
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(<ClubMembersTab clubId={CLUB_ID} isOrganizer managementView />);

    await user.click(await screen.findByRole('button', { name: /Взнос получен/ }));

    await waitFor(() => expect(duesPaidCalled).toBe(true));
    expect(await screen.findByText(/Взнос принят/)).toBeInTheDocument();
  });
});

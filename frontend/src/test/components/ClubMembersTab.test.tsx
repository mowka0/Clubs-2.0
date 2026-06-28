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

// One member per bucket: organizer (calm), active-far (calm), expiring-soon, frozen (awaiting).
const ORGANIZER = member({ userId: 'org', firstName: 'Анна', role: 'organizer', trust: null });
const FAR = member({ userId: 'far', firstName: 'Дмитрий', subscriptionExpiresAt: inDays(40) });
const EXPIRING = member({ userId: 'exp', firstName: 'Игорь', subscriptionExpiresAt: inDays(3) });
const FROZEN = member({ userId: 'frz', firstName: 'Мария', accessStatus: 'frozen', joinedAt: agoDays(2) });

function mockMembers(rows: MemberListItemDto[]) {
  server.use(http.get(`*/api/clubs/${CLUB_ID}/members`, () => HttpResponse.json(rows)));
}

describe('ClubMembersTab — de-Stars dashboard', () => {
  it('organizer view splits members into «Скоро закончится» / «Ждут оплаты» / «Участники»', async () => {
    mockMembers([ORGANIZER, FAR, EXPIRING, FROZEN]);
    renderWithProviders(<ClubMembersTab clubId={CLUB_ID} isOrganizer />);

    expect(await screen.findByText(/Скоро закончится/)).toBeInTheDocument();
    expect(screen.getByText(/Ждут оплаты/)).toBeInTheDocument();
    expect(screen.getByText(/^Участники/)).toBeInTheDocument();

    // Frozen + expiring each expose a «Взнос получен» action; the calm members do not.
    expect(screen.getAllByRole('button', { name: /Взнос получен/ })).toHaveLength(2);
    expect(screen.getByText('Игорь')).toBeInTheDocument();
    expect(screen.getByText('Мария')).toBeInTheDocument();
  });

  it('regular-member view shows a flat list with no buckets or actions', async () => {
    // Backend returns null access fields to non-organizers → every row is calm.
    const asMember = (m: MemberListItemDto) => ({ ...m, accessStatus: null, subscriptionExpiresAt: null });
    mockMembers([ORGANIZER, FAR, EXPIRING].map(asMember));
    renderWithProviders(<ClubMembersTab clubId={CLUB_ID} isOrganizer={false} />);

    expect(await screen.findByText(/^Участники/)).toBeInTheDocument();
    expect(screen.queryByText(/Скоро закончится/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Ждут оплаты/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Взнос получен/ })).not.toBeInTheDocument();
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
    renderWithProviders(<ClubMembersTab clubId={CLUB_ID} isOrganizer />);

    await user.click(await screen.findByRole('button', { name: /Взнос получен/ }));

    await waitFor(() => expect(duesPaidCalled).toBe(true));
    expect(await screen.findByText(/Взнос принят/)).toBeInTheDocument();
  });
});

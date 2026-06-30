import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));
vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));
vi.mock('../../telegram/sdk', () => ({ initTelegramSdk: vi.fn(), getInitDataRaw: () => 'test' }));

import { DuesPaymentSheet } from '../../components/club/DuesPaymentSheet';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const CLUB = 'club-1';

const membershipStub = {
  id: 'm-1', userId: 'u-1', clubId: CLUB, status: 'frozen', role: 'member',
  joinedAt: null, subscriptionExpiresAt: null,
};

const yearAgo = new Date(Date.now() - 400 * 86_400_000).toISOString();

/** Default organizer-card mock (established organizer) so the trust card renders deterministically. */
function mockOrganizer(over: Partial<{ clubsCount: number; trustedMembers: number; onPlatformSince: string; username: string | null }> = {}) {
  server.use(
    http.get(`*/api/clubs/${CLUB}/organizer-card`, () => HttpResponse.json({
      firstName: 'Иван', lastName: 'Петров', username: over.username === undefined ? 'ivan_p' : over.username,
      avatarUrl: null, onPlatformSince: over.onPlatformSince ?? yearAgo,
      clubsCount: over.clubsCount ?? 3, trustedMembers: over.trustedMembers ?? 40,
    })),
  );
}

describe('DuesPaymentSheet', () => {
  it('offers cash only when the club has no SBP link, and confirms a cash claim', async () => {
    mockOrganizer();
    let posted: { method: string; proofUrl: string | null } | undefined;
    server.use(
      http.post(`*/api/clubs/${CLUB}/dues-claim`, async ({ request }) => {
        posted = (await request.json()) as { method: string; proofUrl: string | null };
        return HttpResponse.json({ ...membershipStub, duesClaimedAt: '2026-06-27T00:00:00Z', duesClaimMethod: 'cash' });
      }),
    );
    const onClaimed = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <DuesPaymentSheet clubId={CLUB} price={100} paymentLink={null} paymentMethodNote={null} onClose={() => {}} onClaimed={onClaimed} />,
    );

    // No method switch — cash is the only option.
    expect(screen.queryByRole('tab', { name: /По СБП/ })).not.toBeInTheDocument();

    const confirm = screen.getByRole('button', { name: /Подтвердить оплату/ });
    expect(confirm).toBeDisabled();

    await user.click(screen.getByRole('checkbox'));
    expect(confirm).toBeEnabled();
    await user.click(confirm);

    await waitFor(() => expect(posted?.method).toBe('cash'));
    expect(posted?.proofUrl).toBeNull();
    expect(onClaimed).toHaveBeenCalled();
  });

  it('offers SBP when the club has a link; confirm is blocked until a screenshot is attached', async () => {
    mockOrganizer();
    renderWithProviders(
      <DuesPaymentSheet clubId={CLUB} price={100} paymentLink="https://sbp.example/pay" paymentMethodNote="Сбербанк" onClose={() => {}} onClaimed={() => {}} />,
    );

    expect(screen.getByRole('button', { name: /Оплатить по СБП/ })).toBeInTheDocument();
    // Mandatory screenshot — confirm stays disabled with no proof attached.
    expect(screen.getByRole('button', { name: /Подтвердить оплату/ })).toBeDisabled();
  });

  it('forces cash-only + a safety note when the organizer has no Telegram username (item 2)', async () => {
    // Established organizer (has clubs/trust) but no Telegram → can't be messaged → СБП hidden, cash-only.
    mockOrganizer({ username: null });
    renderWithProviders(
      <DuesPaymentSheet clubId={CLUB} price={100} paymentLink="https://sbp.example/pay" paymentMethodNote="Сбербанк" onClose={() => {}} onClaimed={() => {}} />,
    );

    // Once the organizer card loads with no username, the safety note appears and СБП is removed.
    expect(await screen.findByText(/Оплата только наличными/)).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: /По СБП/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Оплатить по СБП/ })).not.toBeInTheDocument();
    // Cash attestation is the only path forward.
    expect(screen.getByRole('checkbox')).toBeInTheDocument();
  });

  it('shows the organizer trust card (established): identity + facts, no zeros', async () => {
    mockOrganizer({ clubsCount: 3, trustedMembers: 40 });
    renderWithProviders(
      <DuesPaymentSheet clubId={CLUB} price={100} paymentLink={null} paymentMethodNote={null} onClose={() => {}} onClaimed={() => {}} />,
    );

    expect(await screen.findByText('Иван Петров')).toBeInTheDocument();
    expect(screen.getByText(/@ivan_p/)).toBeInTheDocument();
    expect(screen.getByText(/Ведёт/)).toBeInTheDocument();
    expect(screen.getByText(/доверяют/)).toBeInTheDocument();
  });

  it('fresh organizer: collapses to «недавно» + contact-first nudge, no fabricated metrics', async () => {
    mockOrganizer({ clubsCount: 1, trustedMembers: 0, onPlatformSince: new Date().toISOString() });
    renderWithProviders(
      <DuesPaymentSheet clubId={CLUB} price={100} paymentLink={null} paymentMethodNote={null} onClose={() => {}} onClaimed={() => {}} />,
    );

    expect(await screen.findByText(/На платформе недавно/)).toBeInTheDocument();
    expect(screen.getByText(/напишите ему перед первым переводом/i)).toBeInTheDocument();
    // No fabricated zeros for a fresh account.
    expect(screen.queryByText(/Ведёт/)).not.toBeInTheDocument();
    expect(screen.queryByText(/доверяют/)).not.toBeInTheDocument();
  });
});

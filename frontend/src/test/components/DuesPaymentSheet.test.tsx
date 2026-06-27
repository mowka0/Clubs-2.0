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

describe('DuesPaymentSheet', () => {
  it('offers cash only when the club has no SBP link, and confirms a cash claim', async () => {
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
    renderWithProviders(
      <DuesPaymentSheet clubId={CLUB} price={100} paymentLink="https://sbp.example/pay" paymentMethodNote="Сбербанк" onClose={() => {}} onClaimed={() => {}} />,
    );

    expect(screen.getByRole('button', { name: /Оплатить по СБП/ })).toBeInTheDocument();
    // Mandatory screenshot — confirm stays disabled with no proof attached.
    expect(screen.getByRole('button', { name: /Подтвердить оплату/ })).toBeDisabled();
  });
});

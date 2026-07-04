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

// id клуба-фикстуры, общий для всех моков
const CLUB = 'club-1';

// Заготовка frozen-membership — её возвращает мок ответа на dues-claim
const membershipStub = {
  id: 'm-1', userId: 'u-1', clubId: CLUB, status: 'frozen', role: 'member',
  joinedAt: null, subscriptionExpiresAt: null,
};

// Дата «больше года назад» — организатор давно на платформе (не «свежий»)
const yearAgo = new Date(Date.now() - 400 * 86_400_000).toISOString();

/** Дефолтный мок organizer-card (устоявшийся организатор), чтобы карточка доверия рендерилась детерминированно. */
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

    // Переключателя способа оплаты нет — наличные единственный вариант.
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
    // Скриншот обязателен — без приложенного пруфа подтверждение остаётся disabled.
    expect(screen.getByRole('button', { name: /Подтвердить оплату/ })).toBeDisabled();
  });

  it('forces cash-only + a safety note when the organizer has no Telegram username (item 2)', async () => {
    // Устоявшийся организатор (есть клубы/доверие), но без Telegram-username → написать ему нельзя → СБП скрыт, только наличные.
    mockOrganizer({ username: null });
    renderWithProviders(
      <DuesPaymentSheet clubId={CLUB} price={100} paymentLink="https://sbp.example/pay" paymentMethodNote="Сбербанк" onClose={() => {}} onClaimed={() => {}} />,
    );

    // Как только карточка организатора загрузилась без username, появляется предупреждение о безопасности и СБП убирается.
    expect(await screen.findByText(/Оплата только наличными/)).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: /По СБП/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Оплатить по СБП/ })).not.toBeInTheDocument();
    // Подтверждение оплаты наличными — единственный оставшийся путь.
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
    // Никаких нарисованных нулей для свежего аккаунта.
    expect(screen.queryByText(/Ведёт/)).not.toBeInTheDocument();
    expect(screen.queryByText(/доверяют/)).not.toBeInTheDocument();
  });
});

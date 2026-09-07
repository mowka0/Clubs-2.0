import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { BillingStatusDto } from '../../api/billing';

const { openLinkMock } = vi.hoisted(() => ({
  openLinkMock: Object.assign(vi.fn(), { isAvailable: () => true }),
}));

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
  openLink: openLinkMock,
  openTelegramLink: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));
vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

import { BillingSheet } from '../../components/billing/BillingSheet';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => { server.resetHandlers(); vi.clearAllMocks(); });
afterAll(() => server.close());

const CLUB_ID = '7c2e1d2a-0000-4000-8000-000000000001';

function status(over: Partial<BillingStatusDto> = {}): BillingStatusDto {
  return {
    state: 'FREE_MEETING_USED',
    priceKopecks: 19900,
    currentPeriodEnd: null,
    graceUntil: null,
    autopay: true,
    autopayPossible: false,
    pendingCheckout: false,
    recipientName: 'Варламов Иван Иванович',
    ...over,
  };
}

/** Статус отдаём по очереди: первый ответ — до оплаты, следующие — после (опрос). */
function mockBilling(...responses: BillingStatusDto[]) {
  let i = 0;
  server.use(
    http.get(`*/api/clubs/${CLUB_ID}`, () => HttpResponse.json({ id: CLUB_ID, name: 'Бег по средам', ownerId: 'u1' })),
    http.get(`*/api/clubs/${CLUB_ID}/billing`, () => HttpResponse.json(responses[Math.min(i++, responses.length - 1)])),
  );
}

describe('BillingSheet', () => {
  it('показывает цену и чат, чекаут открывает страницу оплаты и переводит в «проверяем оплату»', async () => {
    mockBilling(status());
    const checkoutBodies: unknown[] = [];
    server.use(
      http.post(`*/api/clubs/${CLUB_ID}/billing/checkout`, async ({ request }) => {
        checkoutBodies.push(await request.json());
        return HttpResponse.json({ paymentUrl: 'https://rk.example/pay?inv=100001', invId: 100001 });
      }),
    );
    renderWithProviders(<BillingSheet clubId={CLUB_ID} reason="FREE_MEETING_USED" onClose={() => {}} />);

    expect(await screen.findByText('199 ₽')).toBeInTheDocument();
    expect(screen.getByText(/первая встреча была бесплатной/)).toBeInTheDocument();
    // По тексту платят «за клуб», получатель — ФИО целиком (PO 2026-09-07).
    expect(screen.getByRole('heading', { name: 'Оплата за клуб' })).toBeInTheDocument();
    expect(screen.getByText('самозанятый Варламов Иван Иванович')).toBeInTheDocument();
    // Ползунок включён по умолчанию, и подпись честно говорит, что спишем.
    expect(screen.getByRole('switch', { name: 'Продлевать автоматически' })).toHaveAttribute('aria-checked', 'true');

    // Оферта — текстом внутри шита, по кнопке.
    await userEvent.click(screen.getByRole('button', { name: /Условия \(публичная оферта\)/ }));
    expect(screen.getByText(/Исполнитель \(самозанятый Варламов Иван Иванович\)/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('switch', { name: 'Продлевать автоматически' }));
    await userEvent.click(screen.getByRole('button', { name: 'Оплатить 199 ₽' }));

    await waitFor(() => expect(openLinkMock).toHaveBeenCalledWith('https://rk.example/pay?inv=100001', { tryInstantView: false }));
    // Положение ползунка уезжает на бэкенд вместе с чекаутом.
    expect(checkoutBodies).toEqual([{ autopay: false }]);
    expect(await screen.findByText('Проверяем оплату…')).toBeInTheDocument();
    // Кнопки «подожду в личке» нет — проверку не бросают, закрыть можно только шапкой.
    expect(screen.getAllByRole('button')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Закрыть' })).toBeInTheDocument();
  });

  it('опрос видит сдвиг периода и показывает «оплачено до»', async () => {
    const before = status({ state: 'ACTIVE', currentPeriodEnd: '2026-09-20T10:00:00Z', autopayPossible: true, pendingCheckout: true });
    const after = status({ state: 'ACTIVE', currentPeriodEnd: '2026-10-20T10:00:00Z', autopayPossible: true, pendingCheckout: false });
    mockBilling(before, before, after);
    server.use(
      http.post(`*/api/clubs/${CLUB_ID}/billing/checkout`, () => HttpResponse.json({ paymentUrl: 'https://rk.example/pay', invId: 1 })),
    );
    const onPaid = vi.fn();
    renderWithProviders(<BillingSheet clubId={CLUB_ID} reason={null} onClose={() => {}} onPaid={onPaid} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Продлить на месяц — 199 ₽' }));
    expect(await screen.findByText('Проверяем оплату…')).toBeInTheDocument();

    expect(await screen.findByText('Оплачено до 20 октября', {}, { timeout: 8000 })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Вернуться к встрече' }));
    expect(onPaid).toHaveBeenCalled();
  }, 15000);

  it('после оплаты по СБП ползунок заблокирован с объяснением', async () => {
    mockBilling(status({ state: 'GRACE', currentPeriodEnd: '2026-09-03T10:00:00Z', graceUntil: '2026-09-10T10:00:00Z', autopayPossible: false }));
    renderWithProviders(<BillingSheet clubId={CLUB_ID} reason="SUBSCRIPTION_EXPIRED" onClose={() => {}} />);

    expect(await screen.findByText(/закончилась 3 сентября/)).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Продлевать автоматически' })).toBeDisabled();
    expect(screen.getByText(/автопродление работает только для карт/)).toBeInTheDocument();
  });

  it('возврат из браузера с неоплаченным счётом не выдаёт «оплачено» за старый период', async () => {
    // Раннее продление: подписка ACTIVE со СТАРОЙ датой, счёт ещё висит → ждём, а не поздравляем.
    mockBilling(status({ state: 'ACTIVE', currentPeriodEnd: '2026-09-20T10:00:00Z', autopayPossible: true, pendingCheckout: true }));
    renderWithProviders(<BillingSheet clubId={CLUB_ID} reason={null} initialMode="waiting" onClose={() => {}} />);

    expect(await screen.findByText('Проверяем оплату…')).toBeInTheDocument();
    expect(screen.queryByText(/Оплачено до/)).toBeNull();
  });

  it('возврат из браузера: уже погашенный счёт сразу показывает «оплачено»', async () => {
    mockBilling(status({ state: 'ACTIVE', currentPeriodEnd: '2026-10-07T10:00:00Z', autopayPossible: true, pendingCheckout: false }));
    renderWithProviders(<BillingSheet clubId={CLUB_ID} reason={null} initialMode="waiting" onClose={() => {}} />);

    expect(await screen.findByText('Оплачено до 7 октября')).toBeInTheDocument();
    expect(screen.getByText(/Автопродление включено/)).toBeInTheDocument();
  });
});

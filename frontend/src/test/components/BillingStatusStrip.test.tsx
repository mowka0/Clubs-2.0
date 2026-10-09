import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { BillingStatusDto } from '../../api/billing';

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));
vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

import { BillingStatusStrip } from '../../components/billing/BillingStatusStrip';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => { server.resetHandlers(); vi.clearAllMocks(); });
afterAll(() => server.close());

const CLUB_ID = 'club-1';

function status(over: Partial<BillingStatusDto> = {}): BillingStatusDto {
  return {
    state: 'TRIAL',
    priceKopecks: 19900,
    trialUntil: '2026-09-30T10:00:00Z',
    trialDays: 15,
    currentPeriodEnd: null,
    graceUntil: null,
    autopay: true,
    autopayPossible: false,
    autopayAvailable: true,
    pendingCheckout: false,
    recipientName: 'Варламов Иван Иванович',
    canEnableAutopay: true,
    paymentDue: false,
    lastPayer: null,
    ...over,
  };
}

function mockStatus(dto: BillingStatusDto) {
  server.use(http.get(`*/api/clubs/${CLUB_ID}/billing`, () => HttpResponse.json(dto)));
}

describe('BillingStatusStrip', () => {
  it('клуб без чата — полоски нет', async () => {
    mockStatus(status({ state: 'NO_CHAT' }));
    const { container } = renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} onPay={() => {}} />);
    await waitFor(() => expect(container.querySelector('.rd-billing-strip')).toBeNull());
    await new Promise((r) => setTimeout(r, 50));
    expect(container.querySelector('.rd-billing-strip')).toBeNull();
  });

  it('бота выгнали из чата — пауза без кнопки, оплаченная дата на виду', async () => {
    mockStatus(status({ state: 'BOT_REMOVED', currentPeriodEnd: '2026-10-07T10:00:00Z', trialUntil: null }));
    renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} onPay={() => {}} />);
    expect(await screen.findByText('Бот удалён из чата — подписка на паузе')).toBeInTheDocument();
    expect(screen.getByText(/оплачено до 7 октября/)).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('чат подключён, встреч ещё не было — обещание без даты и без кнопки', async () => {
    mockStatus(status({ state: 'TRIAL_NOT_STARTED', trialUntil: null }));
    renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} onPay={() => {}} />);
    expect(await screen.findByText('15 дней бесплатно')).toBeInTheDocument();
    expect(screen.getByText(/Отсчёт пойдёт с первой встречи/)).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('бесплатный период идёт — видна дата конца и кнопка оплаты заранее', async () => {
    mockStatus(status());
    const onPay = vi.fn();
    renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} onPay={onPay} />);
    expect(await screen.findByText('Бесплатно до 30 сентября')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Оплатить' }));
    expect(onPay).toHaveBeenCalled();
  });

  it('бесплатный период кончился — кнопка «Оплатить» открывает шит', async () => {
    mockStatus(status({ state: 'TRIAL_ENDED', trialUntil: null }));
    const onPay = vi.fn();
    renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} onPay={onPay} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Оплатить' }));
    expect(onPay).toHaveBeenCalled();
  });

  it('на странице клуба оплаченный клуб не виден, пока до конца больше недели', async () => {
    mockStatus(status({ state: 'ACTIVE', currentPeriodEnd: '2026-10-07T10:00:00Z', autopayPossible: true, paymentDue: false }));
    const { container } = renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} placement="club" onPay={() => {}} />);
    await new Promise((r) => setTimeout(r, 50));
    expect(container.querySelector('.rd-billing-strip')).toBeNull();
  });

  it('на странице клуба за неделю до конца — плашка с кнопкой и крайним оплатившим, без ползунка', async () => {
    mockStatus(status({
      state: 'ACTIVE', currentPeriodEnd: '2026-10-07T10:00:00Z', autopay: false, autopayPossible: true,
      paymentDue: true, canEnableAutopay: false, lastPayer: { userId: 'u-2', name: 'Маша Петрова' },
    }));
    const onPay = vi.fn();
    renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} placement="club" onPay={onPay} />);
    expect(await screen.findByText('Клуб оплачен до 7 октября')).toBeInTheDocument();
    expect(screen.getByText(/Оплатить может любой участник клуба/)).toBeInTheDocument();
    expect(screen.getByText('Последний платёж — Маша Петрова 💛')).toBeInTheDocument();
    expect(screen.queryByRole('switch')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Оплатить' }));
    expect(onPay).toHaveBeenCalled();
  });

  it('на странице клуба при автопродлении — дата списания с карты владельца и предложение оплатить вместо него', async () => {
    mockStatus(status({
      state: 'ACTIVE', currentPeriodEnd: '2026-10-07T10:00:00Z', autopay: true, autopayPossible: true,
      paymentDue: true, canEnableAutopay: false,
    }));
    renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} placement="club" onPay={() => {}} />);
    expect(await screen.findByText('7 октября продлится автоматически с карты владельца — можно оплатить месяц вместо него.')).toBeInTheDocument();
  });

  it('на странице клуба «бот удалён» видят только организаторы', async () => {
    mockStatus(status({ state: 'BOT_REMOVED', trialUntil: null }));
    const member = renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} placement="club" onPay={() => {}} />);
    await new Promise((r) => setTimeout(r, 50));
    expect(member.container.querySelector('.rd-billing-strip')).toBeNull();
    member.unmount();

    renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} placement="club" showBotRemoved onPay={() => {}} />);
    expect(await screen.findByText('Бот удалён из чата — подписка на паузе')).toBeInTheDocument();
  });

  it('состояния, требующие оплаты, на странице клуба видны всем — участнику с подсказкой, что платить может любой', async () => {
    mockStatus(status({ state: 'TRIAL_ENDED', trialUntil: null, paymentDue: true, canEnableAutopay: false }));
    renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} placement="club" onPay={() => {}} />);
    expect(await screen.findByText('Бесплатный период закончился')).toBeInTheDocument();
    expect(screen.getByText(/Оплатить может любой участник клуба/)).toBeInTheDocument();
  });

  it('в «Управлении» со-организатор видит «оплачено до», но без ползунка', async () => {
    mockStatus(status({ state: 'ACTIVE', currentPeriodEnd: '2026-10-07T10:00:00Z', autopayPossible: true, canEnableAutopay: false }));
    renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} onPay={() => {}} />);
    expect(await screen.findByText('Оплачено до 7 октября')).toBeInTheDocument();
    expect(screen.queryByRole('switch')).toBeNull();
  });

  it('оплачено — дата, ползунок шлёт PATCH и принимает ответ', async () => {
    mockStatus(status({ state: 'ACTIVE', currentPeriodEnd: '2026-10-07T10:00:00Z', autopay: true, autopayPossible: true }));
    const bodies: unknown[] = [];
    server.use(
      http.patch(`*/api/clubs/${CLUB_ID}/billing/autopay`, async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(status({ state: 'ACTIVE', currentPeriodEnd: '2026-10-07T10:00:00Z', autopay: false, autopayPossible: true }));
      }),
    );
    renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} onPay={() => {}} />);

    expect(await screen.findByText('Оплачено до 7 октября')).toBeInTheDocument();
    // Списание — в день окончания периода, по тексту «за клуб» (PO 2026-09-07).
    expect(screen.getByText('7 октября спишем 199 ₽ с сохранённой карты.')).toBeInTheDocument();
    expect(screen.getByText('199 ₽ в месяц за клуб · Robokassa')).toBeInTheDocument();
    const toggle = screen.getByRole('switch', { name: 'Продлевать автоматически' });
    expect(toggle).toHaveAttribute('aria-checked', 'true');
    await userEvent.click(toggle);

    await waitFor(() => expect(bodies).toEqual([{ autopay: false }]));
    await waitFor(() => expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'false'));
    expect(screen.getByText(/Выключено — напомним/)).toBeInTheDocument();
  });

  it('оплата по СБП — ползунок недоступен с объяснением', async () => {
    mockStatus(status({ state: 'ACTIVE', currentPeriodEnd: '2026-10-07T10:00:00Z', autopay: true, autopayPossible: false }));
    renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} onPay={() => {}} />);
    expect(await screen.findByRole('switch')).toBeDisabled();
    expect(screen.getByText(/Карта для автосписания не сохранена/)).toBeInTheDocument();
  });

  it('рекуррент магазину не разрешён — ползунок недоступен даже с сохранённой картой', async () => {
    mockStatus(status({ state: 'ACTIVE', currentPeriodEnd: '2026-10-07T10:00:00Z', autopay: true, autopayPossible: true, autopayAvailable: false }));
    renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} onPay={() => {}} />);
    const toggle = await screen.findByRole('switch');
    expect(toggle).toBeDisabled();
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByText(/Автопродление пока недоступно/)).toBeInTheDocument();
  });

  it('грейс и стена — сроки и кнопка продления', async () => {
    mockStatus(status({ state: 'GRACE', currentPeriodEnd: '2026-09-03T10:00:00Z', graceUntil: '2026-09-10T10:00:00Z' }));
    const first = renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} onPay={() => {}} />);
    expect(await screen.findByText('Подписка закончилась 3 сентября')).toBeInTheDocument();
    expect(screen.getByText('10 сентября')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Продлить' })).toBeInTheDocument();
    first.unmount();

    mockStatus(status({ state: 'ENDED', currentPeriodEnd: '2026-09-03T10:00:00Z', graceUntil: '2026-09-10T10:00:00Z' }));
    renderWithProviders(<BillingStatusStrip clubId={CLUB_ID} onPay={() => {}} />);
    expect(await screen.findByText('Новые встречи недоступны до оплаты')).toBeInTheDocument();
    // Дату конца льготы в «закончилась» не показываем (E23, PO 2026-10-07).
    expect(screen.queryByText(/10 сентября/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Оплатить' })).toBeInTheDocument();
  });
});

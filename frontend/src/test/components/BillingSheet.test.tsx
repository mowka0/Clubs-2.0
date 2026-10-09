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
    state: 'TRIAL_ENDED',
    priceKopecks: 19900,
    trialUntil: null,
    trialDays: 15,
    currentPeriodEnd: null,
    graceUntil: null,
    autopay: true,
    autopayPossible: false,
    autopayAvailable: true,
    pendingCheckout: false,
    recipientName: 'Варламов Иван Иванович',
    canEnableAutopay: true,
    paymentDue: true,
    lastPayer: null,
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
    renderWithProviders(<BillingSheet clubId={CLUB_ID} reason="TRIAL_ENDED" onClose={() => {}} />);

    expect(await screen.findByText('199 ₽')).toBeInTheDocument();
    expect(screen.getByText(/первые 15 дней были бесплатными/)).toBeInTheDocument();
    // По тексту платят «за клуб», получатель — ФИО целиком (PO 2026-09-07).
    expect(screen.getByRole('heading', { name: 'Оплата за клуб' })).toBeInTheDocument();
    expect(screen.getByText('самозанятый Варламов Иван Иванович')).toBeInTheDocument();
    // Отметка согласия на автосписание — дословно по Robokassa и по умолчанию снята.
    const consent = screen.getByRole('checkbox', { name: 'Я согласен на автоматические списания согласно условиям оферты' });
    expect(consent).not.toBeChecked();
    // Периодичность и способ отмены видны рядом с отметкой и без неё (требование Robokassa).
    expect(screen.getByText(/199 ₽ каждые 30 дней с этой же карты\. Отключить можно на странице клуба/)).toBeInTheDocument();

    // Ссылка из отметки раскрывает оферту текстом внутри шита (вторая ссылка — под кнопкой оплаты).
    await userEvent.click(screen.getByRole('button', { name: 'условиям оферты' }));
    expect(screen.getByText(/Исполнитель — самозанятый Варламов Иван Иванович, ИНН/)).toBeInTheDocument();
    // Оферта цитирует формулировку отметки дословно — текст на экране и текст в договоре не разъезжаются.
    expect(screen.getByText(/«Я согласен на автоматические списания согласно условиям оферты»/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '3. Услуга, стоимость и порядок оказания' })).toBeInTheDocument();

    await userEvent.click(consent);
    expect(consent).toBeChecked();
    await userEvent.click(screen.getByRole('button', { name: 'Оплатить 199 ₽' }));

    await waitFor(() => expect(openLinkMock).toHaveBeenCalledWith('https://rk.example/pay?inv=100001', { tryInstantView: false }));
    // Отметка уезжает на бэкенд вместе с чекаутом.
    expect(checkoutBodies).toEqual([{ autopay: true }]);
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

  it('на продлении отметка согласия тоже снята по умолчанию, даже если автопродление на подписке включено', async () => {
    mockBilling(status({ state: 'GRACE', currentPeriodEnd: '2026-09-03T10:00:00Z', graceUntil: '2026-09-10T10:00:00Z', autopay: true, autopayPossible: false }));
    const checkoutBodies: unknown[] = [];
    server.use(
      http.post(`*/api/clubs/${CLUB_ID}/billing/checkout`, async ({ request }) => {
        checkoutBodies.push(await request.json());
        return HttpResponse.json({ paymentUrl: 'https://rk.example/pay?inv=100003', invId: 100003 });
      }),
    );
    renderWithProviders(<BillingSheet clubId={CLUB_ID} reason="SUBSCRIPTION_EXPIRED" onClose={() => {}} />);

    expect(await screen.findByText(/закончилась 3 сентября/)).toBeInTheDocument();
    // Прошлая оплата по СБП отметке не мешает: эта оплата картой карту сохранит.
    expect(screen.getByRole('checkbox', { name: /Я согласен на автоматические списания/ })).not.toBeChecked();

    await userEvent.click(screen.getByRole('button', { name: 'Продлить на месяц — 199 ₽' }));
    await waitFor(() => expect(checkoutBodies).toEqual([{ autopay: false }]));
  });

  it('рекуррент магазину не разрешён — отметки согласия нет, списание не обещаем, чекаут уходит без автопродления', async () => {
    mockBilling(status({ autopayAvailable: false }));
    const checkoutBodies: unknown[] = [];
    server.use(
      http.post(`*/api/clubs/${CLUB_ID}/billing/checkout`, async ({ request }) => {
        checkoutBodies.push(await request.json());
        return HttpResponse.json({ paymentUrl: 'https://rk.example/pay?inv=100002', invId: 100002 });
      }),
    );
    renderWithProviders(<BillingSheet clubId={CLUB_ID} reason="TRIAL_ENDED" onClose={() => {}} />);

    // Ждём данные: до них шит ещё не знает про флаг и рисует отметку как обычно.
    expect(await screen.findByText('199 ₽')).toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(screen.getByText(/Автопродление пока недоступно/)).toBeInTheDocument();
    expect(screen.queryByText(/с этой же карты/)).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Оплатить 199 ₽' }));
    await waitFor(() => expect(checkoutBodies).toEqual([{ autopay: false }]));
  });

  it('возврат из браузера с неоплаченным счётом не выдаёт «оплачено» за старый период', async () => {
    // Раннее продление: подписка ACTIVE со СТАРОЙ датой, счёт ещё висит → ждём, а не поздравляем.
    mockBilling(status({ state: 'ACTIVE', currentPeriodEnd: '2026-09-20T10:00:00Z', autopayPossible: true, pendingCheckout: true }));
    renderWithProviders(<BillingSheet clubId={CLUB_ID} reason={null} initialMode="waiting" onClose={() => {}} />);

    expect(await screen.findByText('Проверяем оплату…')).toBeInTheDocument();
    expect(screen.queryByText(/Оплачено до/)).toBeNull();
  });

  it('участник платит разово: без отметки согласия, чекаут без автосписания, «спасибо» после оплаты', async () => {
    // Платит любой участник, но карту участника не сохраняем (billing-member-pays.md M2).
    const before = status({ state: 'ACTIVE', currentPeriodEnd: '2026-10-07T10:00:00Z', canEnableAutopay: false, autopay: true, autopayPossible: true });
    const after = status({ state: 'ACTIVE', currentPeriodEnd: '2026-11-06T10:00:00Z', canEnableAutopay: false, autopay: true, autopayPossible: true });
    mockBilling(before, before, after);
    const checkoutBodies: unknown[] = [];
    server.use(
      http.post(`*/api/clubs/${CLUB_ID}/billing/checkout`, async ({ request }) => {
        checkoutBodies.push(await request.json());
        return HttpResponse.json({ paymentUrl: 'https://rk.example/pay', invId: 100010 });
      }),
    );
    renderWithProviders(<BillingSheet clubId={CLUB_ID} reason={null} onClose={() => {}} />);

    expect(await screen.findByText('Разовая оплата за месяц')).toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Продлить на месяц — 199 ₽' }));
    await waitFor(() => expect(checkoutBodies).toEqual([{ autopay: false }]));

    expect(await screen.findByText('Оплачено до 6 ноября', {}, { timeout: 8000 })).toBeInTheDocument();
    expect(screen.getByText('Спасибо! Подписка клуба продлена на месяц 💛')).toBeInTheDocument();
  }, 15000);

  it('возврат из браузера: уже погашенный счёт сразу показывает «оплачено»', async () => {
    mockBilling(status({ state: 'ACTIVE', currentPeriodEnd: '2026-10-07T10:00:00Z', autopayPossible: true, pendingCheckout: false }));
    renderWithProviders(<BillingSheet clubId={CLUB_ID} reason={null} initialMode="waiting" onClose={() => {}} />);

    expect(await screen.findByText('Оплачено до 7 октября')).toBeInTheDocument();
    expect(screen.getByText(/Автопродление включено/)).toBeInTheDocument();
  });
});

import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import { useAuthStore } from '../../store/useAuthStore';
import type { DebtDto, SkladchinaDetailDto, UserDto } from '../../types/api';

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
  mountBackButton: Object.assign(vi.fn(), { isAvailable: () => false }),
  unmountBackButton: vi.fn(),
  showBackButton: Object.assign(vi.fn(), { isAvailable: () => false }),
  hideBackButton: Object.assign(vi.fn(), { isAvailable: () => false }),
  onBackButtonClick: Object.assign(vi.fn(() => vi.fn()), { isAvailable: () => false }),
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));
vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

import { SkladchinaPage } from '../../pages/SkladchinaPage';

const SKLADCHINA_ID = 's-1';
const ME = 'me-1';
const CREATOR = 'org-1';
const FUTURE = new Date(Date.now() + 3 * 86_400_000).toISOString();

const creator = { id: CREATOR, firstName: 'Иван', lastName: null, username: 'ivan', avatarUrl: null };
const me = { id: ME, firstName: 'Саша', lastName: null, username: null, avatarUrl: null };

function buildDebt(overrides: Partial<DebtDto> = {}): DebtDto {
  return {
    id: 'd-1',
    skladchinaId: SKLADCHINA_ID,
    skladchinaTitle: 'Ужин после игры',
    skladchinaKind: 'shared',
    clubId: 'club-1',
    clubName: 'Партия',
    debtor: me,
    creditor: creator,
    amountKopecks: 100000,
    dueAt: FUTURE,
    status: 'waiting',
    promisedAt: null,
    claimedAt: null,
    confirmedAt: null,
    rejectedAt: null,
    rejectNote: null,
    note: null,
    receiptUrl: null,
    settlementId: null,
    paymentLink: 'https://pay.example/x',
    paymentMethodNote: null,
    isOverdue: false,
    createdAt: FUTURE,
    ...overrides,
  };
}

function buildDetail(overrides: Partial<SkladchinaDetailDto> = {}): SkladchinaDetailDto {
  return {
    id: SKLADCHINA_ID,
    clubId: 'club-1',
    clubName: 'Партия',
    clubAvatarUrl: null,
    creatorId: CREATOR,
    creatorName: 'Иван',
    title: 'Ужин после игры',
    description: null,
    rules: null,
    photoUrl: null,
    kind: 'shared',
    amountKopecks: 600000,
    targetKopecks: 600000,
    receivedKopecks: 100000,
    claimedKopecks: 0,
    paymentLink: 'https://pay.example/x',
    paymentMethodNote: null,
    deadline: FUTURE,
    enrollmentUntil: null,
    minParticipants: null,
    lockedAt: null,
    orderedAt: null,
    eventId: null,
    eventTitle: null,
    eventDatetime: null,
    status: 'active',
    closedAt: null,
    isCreator: false,
    canCancel: false,
    isEnrolling: false,
    enrolledCount: 0,
    myEnrolled: false,
    debtCount: 6,
    receivedCount: 1,
    openCount: 5,
    claimedCount: 0,
    myDebt: buildDebt(),
    debts: null,
    ...overrides,
  };
}

function mockDetail(detail: SkladchinaDetailDto) {
  server.use(http.get(`*/api/skladchinas/${SKLADCHINA_ID}`, () => HttpResponse.json(detail)));
}

function renderPage() {
  const user = userEvent.setup();
  const result = renderWithProviders(
    <Routes>
      <Route path="/skladchina/:id" element={<SkladchinaPage />} />
    </Routes>,
    { routerEntries: [`/skladchina/${SKLADCHINA_ID}`] },
  );
  return { ...result, user };
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  // Строка долга решает, чьи кнопки показать, по id смотрящего из auth-стора.
  useAuthStore.setState({ user: { id: ME, telegramId: 1, firstName: 'Саша' } as UserDto, isAuthenticated: true });
});

describe('SkladchinaPage — сборы и долги v3', () => {
  it('должник видит «Получено X из Y», свой долг и кнопки «Отдал» / «Оплачу позже»', async () => {
    mockDetail(buildDetail());
    renderPage();

    expect(await screen.findByText('Ужин после игры')).toBeInTheDocument();
    expect(screen.getByText(/Получено 1\s?000 ₽ из 6\s?000 ₽/)).toBeInTheDocument();
    expect(screen.getByText(/оплатили 1 из 6/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Отдал' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Оплачу позже' })).toBeInTheDocument();
    // Список долгов сбора виден только создателю.
    expect(screen.queryByText('Кто должен')).not.toBeInTheDocument();
  });

  it('«Отдал» уходит на бэкенд, после ответа долг ждёт подтверждения', async () => {
    let claimed = false;
    mockDetail(buildDetail());
    server.use(
      http.post(`*/api/debts/d-1/claim`, () => {
        claimed = true;
        return HttpResponse.json(buildDebt({ status: 'claimed', claimedAt: FUTURE }));
      }),
    );
    const { user } = renderPage();

    await user.click(await screen.findByRole('button', { name: 'Отдал' }));

    expect(claimed).toBe(true);
    // После инвалидации деталка перечитывается тем же моком — проверяем только сам вызов.
  });

  it('claimed-долг: «ждём подтверждения» и кнопка «Отменить», без «Отдал»', async () => {
    mockDetail(buildDetail({ myDebt: buildDebt({ status: 'claimed', claimedAt: FUTURE }) }));
    renderPage();

    expect(await screen.findByText('ждём подтверждения')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Отменить' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Отдал' })).not.toBeInTheDocument();
  });

  it('создатель видит список «Кто должен» с «Получил» / «Не получил» у claimed-долга и «Отменить сбор»', async () => {
    useAuthStore.setState({ user: { id: CREATOR, telegramId: 2, firstName: 'Иван' } as UserDto, isAuthenticated: true });
    mockDetail(buildDetail({
      isCreator: true,
      canCancel: true,
      myDebt: null,
      debts: [
        buildDebt({ id: 'd-1', status: 'claimed', claimedAt: FUTURE }),
        buildDebt({ id: 'd-2', debtor: { ...me, id: 'u-2', firstName: 'Оля' }, status: 'received', confirmedAt: FUTURE }),
      ],
    }));
    renderPage();

    expect(await screen.findByText('Кто должен')).toBeInTheDocument();
    expect(screen.getByText('говорит, что отдал · подтвердите')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Получил' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Не получил' })).toBeInTheDocument();
    expect(screen.getByText('получено ✅')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Отменить сбор' })).toBeInTheDocument();
    // Создатель себе не платит: реквизитов и своего долга у него нет.
    expect(screen.queryByText('Реквизиты')).not.toBeInTheDocument();
  });

  it('этап «Кто в деле?»: участник видит «В деле», после отметки — «Передумал»', async () => {
    mockDetail(buildDetail({
      isEnrolling: true,
      enrollmentUntil: FUTURE,
      minParticipants: 6,
      enrolledCount: 3,
      debtCount: 0,
      receivedCount: 0,
      openCount: 0,
      receivedKopecks: 0,
      myDebt: null,
    }));
    renderPage();

    expect(await screen.findByRole('button', { name: 'В деле' })).toBeInTheDocument();
    expect(screen.getByText(/В деле 3 · нужно 6/)).toBeInTheDocument();

    mockDetail(buildDetail({ isEnrolling: true, enrollmentUntil: FUTURE, enrolledCount: 4, myEnrolled: true, myDebt: null }));
    renderPage();
    expect(await screen.findByRole('button', { name: 'Передумал' })).toBeInTheDocument();
  });

  it('«Кто берёт?»: без долга — заметка и «Беру»; после заказа приём закрыт', async () => {
    mockDetail(buildDetail({ kind: 'per_head', amountKopecks: 150000, targetKopecks: null, myDebt: null, debtCount: 0, receivedCount: 0, openCount: 0, receivedKopecks: 0 }));
    renderPage();
    expect(await screen.findByRole('button', { name: 'Беру' })).toBeInTheDocument();

    mockDetail(buildDetail({ kind: 'per_head', orderedAt: FUTURE, myDebt: null }));
    renderPage();
    expect(await screen.findByText('Приём закрыт: заказ уже сделан.')).toBeInTheDocument();
  });

  it('«Кто берёт?»: создатель тоже может «Беру», пока сам не взял и заказ не сделан', async () => {
    useAuthStore.setState({ user: { id: CREATOR, telegramId: 2, firstName: 'Иван' } as UserDto, isAuthenticated: true });
    mockDetail(buildDetail({ kind: 'per_head', isCreator: true, canCancel: true, myDebt: null, debts: [], debtCount: 0, receivedCount: 0, openCount: 0, receivedKopecks: 0 }));
    const first = renderPage();
    expect(await screen.findByRole('button', { name: 'Беру' })).toBeInTheDocument();
    first.unmount();

    mockDetail(buildDetail({
      kind: 'per_head', isCreator: true, canCancel: true, myDebt: null,
      debts: [buildDebt({ debtor: creator, creditor: creator, status: 'received', confirmedAt: FUTURE })],
    }));
    renderPage();
    expect(await screen.findByText('Берут')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Беру' })).not.toBeInTheDocument();
  });

  it('«По желанию»: поле суммы и «Перевёл»; создателю «Закрыть сбор» недоступна, пока есть переводы', async () => {
    mockDetail(buildDetail({ kind: 'voluntary', amountKopecks: 50000, targetKopecks: null, deadline: null, myDebt: null, debtCount: 0, receivedCount: 0, openCount: 0, receivedKopecks: 0 }));
    renderPage();
    expect(await screen.findByRole('button', { name: 'Перевёл' })).toBeInTheDocument();
    expect(screen.getByLabelText('Сумма перевода')).toBeInTheDocument();

    useAuthStore.setState({ user: { id: CREATOR, telegramId: 2, firstName: 'Иван' } as UserDto, isAuthenticated: true });
    mockDetail(buildDetail({ kind: 'voluntary', isCreator: true, canCancel: true, myDebt: null, debts: [buildDebt({ status: 'claimed' })], claimedCount: 1, openCount: 1 }));
    renderPage();
    const close = await screen.findByRole('button', { name: 'Закрыть сбор' });
    expect(close).toBeDisabled();
    expect(screen.getByText('Разберите переводы: 1')).toBeInTheDocument();
  });
});

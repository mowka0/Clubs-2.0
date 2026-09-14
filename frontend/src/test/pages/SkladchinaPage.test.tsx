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
    quantity: 1,
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
    creator,
    freeAmountRequired: false,
    title: 'Ужин после игры',
    description: null,
    rules: null,
    photoUrl: null,
    kind: 'shared',
    amountKopecks: 600000,
    targetKopecks: 600000,
    receivedKopecks: 100000,
    claimedKopecks: 0,
    promisedKopecks: 0,
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
    enrolled: [],
    paid: [],
    debtCount: 6,
    receivedCount: 1,
    openCount: 5,
    claimedCount: 0,
    promisedCount: 0,
    receivedItems: 1,
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

    // «Отдал» необратим — перед отправкой своя шторка «Подтвердить / Отмена».
    await user.click(await screen.findByRole('button', { name: 'Отдал' }));
    expect(screen.getByRole('dialog', { name: /^Отдали 1.000 ₽\? Иван получит/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Отмена' }));
    expect(claimed).toBe(false);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Отдал' }));
    await user.click(screen.getByRole('button', { name: 'Подтвердить' }));
    expect(claimed).toBe(true);
    // После инвалидации деталка перечитывается тем же моком — проверяем только сам вызов.
  });

  it('claimed-долг: «ждём подтверждения», без «Отдал» и без отмены — перевод не отзывается', async () => {
    mockDetail(buildDetail({ myDebt: buildDebt({ status: 'claimed', claimedAt: FUTURE }) }));
    renderPage();

    expect(await screen.findByText('ждём подтверждения')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Отменить' })).not.toBeInTheDocument();
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
    // Оплатившие — отдельной панелью (PO 2026-09-14).
    expect(screen.getByText('Оплатили')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Отменить сбор' })).toBeInTheDocument();
    // Создатель себе не платит: реквизитов и своего долга у него нет.
    expect(screen.queryByText('Кому переводить')).not.toBeInTheDocument();
  });

  it('этап «Кто в деле?»: участник видит «В деле», после отметки — «Передумал»', async () => {
    mockDetail(buildDetail({
      isEnrolling: true,
      enrollmentUntil: FUTURE,
      minParticipants: 6,
      enrolledCount: 3,
      enrolled: [creator, { ...me, id: 'u-2', firstName: 'Оля' }, me],
      debtCount: 0,
      receivedCount: 0,
      openCount: 0,
      receivedKopecks: 0,
      myDebt: null,
    }));
    renderPage();

    expect(await screen.findByRole('button', { name: 'В деле' })).toBeInTheDocument();
    expect(screen.getByText(/В деле 3 · нужно 6/)).toBeInTheDocument();
    // На этапе записи платить нечего — реквизитов и «Открыть в банке» нет.
    expect(screen.queryByText('Кому переводить')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Открыть в банке' })).not.toBeInTheDocument();
    // Кто записался — видно всем вместо «Кто должен».
    expect(screen.getByText('Оля')).toBeInTheDocument();
    expect(screen.getByText('Саша (вы)')).toBeInTheDocument();
    expect(screen.queryByText('Кто должен')).not.toBeInTheDocument();

    mockDetail(buildDetail({ isEnrolling: true, enrollmentUntil: FUTURE, enrolledCount: 4, myEnrolled: true, myDebt: null }));
    renderPage();
    expect(await screen.findByRole('button', { name: 'Передумал' })).toBeInTheDocument();
  });

  it('«Кто берёт?»: без долга — заметка и «Беру», полосы и «из» нет, пока никто не взял; после заказа приём закрыт', async () => {
    mockDetail(buildDetail({ kind: 'per_head', amountKopecks: 150000, targetKopecks: null, myDebt: null, debtCount: 0, receivedCount: 0, openCount: 0, receivedKopecks: 0 }));
    const { container } = renderPage();
    expect(await screen.findByRole('button', { name: 'Беру' })).toBeInTheDocument();
    expect(screen.getByText('Получено 0 ₽')).toBeInTheDocument();
    expect(container.querySelector('.rd-progress')).toBeNull();

    mockDetail(buildDetail({ kind: 'per_head', orderedAt: FUTURE, myDebt: null }));
    renderPage();
    expect(await screen.findByText('Приём закрыт: заказ уже сделан.')).toBeInTheDocument();
  });

  it('«Заказываю»: шторка перечисляет обещавших, галочка решает, брать ли их в долг', async () => {
    useAuthStore.setState({ user: { id: CREATOR, telegramId: 2, firstName: 'Иван' } as UserDto, isAuthenticated: true });
    let body: unknown = null;
    mockDetail(buildDetail({
      kind: 'per_head', isCreator: true, canCancel: true, myDebt: null, receivedCount: 1, claimedCount: 0, openCount: 2,
      debts: [
        buildDebt({ id: 'd-own', debtor: creator, creditor: creator, status: 'received', confirmedAt: FUTURE }),
        buildDebt({ id: 'd-p', debtor: { ...me, firstName: 'Оля' }, status: 'promised', promisedAt: '2026-09-16' }),
        buildDebt({ id: 'd-w', debtor: { ...me, id: 'u-3', firstName: 'Петя' }, status: 'waiting' }),
      ],
    }));
    server.use(http.post(`*/api/skladchinas/${SKLADCHINA_ID}/order`, async ({ request }) => {
      body = await request.json();
      return HttpResponse.json(buildDetail({ kind: 'per_head', isCreator: true, orderedAt: FUTURE, myDebt: null, debts: [] }));
    }));
    const { user } = renderPage();
    await user.click(await screen.findByRole('button', { name: 'Заказываю' }));
    const dialog = screen.getByRole('dialog', { name: /Заказываю: оплатили 1, говорят, что отдали 0, не оплатили 1 — они выбывают\./ });
    expect(dialog).toHaveTextContent(/Оля — 1.000 ₽ к 16 сентября/);
    const keep = screen.getByRole('checkbox', { name: /Купить и на них в долг/ });
    expect(keep).toBeChecked();
    await user.click(keep);
    const orderButtons = screen.getAllByRole('button', { name: 'Заказываю' });
    await user.click(orderButtons[orderButtons.length - 1]!);
    expect(body).toEqual({ includePromised: false });
  });

  it('«Кто берёт?»: у создателя нет «Беру» — он берёт себе чекбоксом при создании; «Заказываю» на месте', async () => {
    useAuthStore.setState({ user: { id: CREATOR, telegramId: 2, firstName: 'Иван' } as UserDto, isAuthenticated: true });
    mockDetail(buildDetail({
      kind: 'per_head', isCreator: true, canCancel: true, myDebt: null,
      debts: [buildDebt({ debtor: creator, creditor: creator, status: 'received', confirmedAt: FUTURE })],
    }));
    renderPage();
    expect(await screen.findByText('Берут')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Беру' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Заказываю' })).toBeInTheDocument();
  });

  it('«Кто берёт?»: количество рядом с «Беру», подтверждение считает сумму, после «Передумал» можно взять снова', async () => {
    let body: unknown = null;
    mockDetail(buildDetail({ kind: 'per_head', amountKopecks: 150000, targetKopecks: null, myDebt: null, debtCount: 0, receivedCount: 0, openCount: 0, receivedKopecks: 0 }));
    server.use(http.post(`*/api/skladchinas/${SKLADCHINA_ID}/join`, async ({ request }) => {
      body = await request.json();
      return HttpResponse.json(buildDetail({ kind: 'per_head', myDebt: buildDebt({ skladchinaKind: 'per_head', amountKopecks: 450000, quantity: 3 }) }));
    }));
    const first = renderPage();
    const { user } = first;
    const qty = await screen.findByLabelText('Сколько штук');
    expect(qty).toHaveValue(1);
    await user.clear(qty);
    await user.type(qty, '3');
    await user.click(screen.getByRole('button', { name: 'Беру' }));
    expect(screen.getByRole('dialog', { name: /Беру 3 × 1.500 ₽ = 4.500 ₽\?/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Подтвердить' }));
    expect(body).toEqual({ note: null, quantity: 3 });
    first.unmount();

    mockDetail(buildDetail({ kind: 'per_head', myDebt: buildDebt({ skladchinaKind: 'per_head', status: 'dropped' }), debtCount: 0, receivedCount: 0, openCount: 0, receivedKopecks: 0 }));
    renderPage();
    expect(await screen.findByText('Вы выбывали из этого сбора — можно взять снова.')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Беру' }).length).toBeGreaterThan(0);
    expect(screen.queryByText('Мой долг')).not.toBeInTheDocument();
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
    expect(screen.getByText('Подтвердите')).toBeInTheDocument();
    expect(screen.queryByText('Кто должен')).not.toBeInTheDocument();
  });

  it('плательщик видит «Кому переводить» с создателем, у «По желанию» заголовок «Ваш перевод», сбор из встречи — строку встречи', async () => {
    mockDetail(buildDetail({ kind: 'voluntary', myDebt: null, eventId: 'e-1', eventTitle: 'Покатушки', eventDatetime: FUTURE, paid: [{ ...me, id: 'u-2', firstName: 'Оля' }] }));
    renderPage();
    expect(await screen.findByText('Кому переводить')).toBeInTheDocument();
    expect(screen.getByText('Иван')).toBeInTheDocument();
    expect(screen.getByText(/@ivan · собирает/)).toBeInTheDocument();
    expect(screen.getByText('Ваш перевод')).toBeInTheDocument();
    expect(screen.getByText('Сбор в клубе · собирает Иван')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Открыть встречу Покатушки/ })).toBeInTheDocument();
    expect(screen.getByText('Покатушки')).toBeInTheDocument();
    // Оплативших видят все участники — людьми, без сумм (PO 2026-09-14).
    expect(screen.getByText('Перевели')).toBeInTheDocument();
    expect(screen.getByText('Оля')).toBeInTheDocument();
  });

  it('участник «Скинуться» видит панель «Оплатили» с людьми, без сумм и без «Кто должен»', async () => {
    mockDetail(buildDetail({ paid: [creator, { ...me, id: 'u-2', firstName: 'Оля' }] }));
    renderPage();
    expect(await screen.findByText('Оплатили')).toBeInTheDocument();
    expect(screen.getByText('Оля')).toBeInTheDocument();
    expect(screen.getAllByText('Иван').length).toBe(3); // «Мой долг» (кредитор), «Кому переводить», «Оплатили»
    expect(screen.queryByText('Кто должен')).not.toBeInTheDocument();
  });

  it('создатель «По желанию»: свой взнос в «Перевели», говорящие «перевёл» — в «Подтвердите», счётчик без знаменателя', async () => {
    useAuthStore.setState({ user: { id: CREATOR, telegramId: 2, firstName: 'Иван' } as UserDto, isAuthenticated: true });
    mockDetail(buildDetail({
      kind: 'voluntary', isCreator: true, canCancel: true, myDebt: null, receivedCount: 1, claimedCount: 1, openCount: 1, debtCount: 2,
      debts: [
        buildDebt({ id: 'd-own', debtor: creator, creditor: creator, status: 'received', confirmedAt: FUTURE }),
        buildDebt({ id: 'd-2', debtor: { ...me, id: 'u-2', firstName: 'Оля' }, status: 'claimed', claimedAt: FUTURE }),
      ],
    }));
    renderPage();
    expect(await screen.findByText('Подтвердите')).toBeInTheDocument();
    expect(screen.getByText('Перевели')).toBeInTheDocument();
    expect(screen.getByText('ваш взнос ✅')).toBeInTheDocument();
    expect(screen.getByText('Сбор в клубе · собираете вы')).toBeInTheDocument();
    expect(screen.queryByText('Кто должен')).not.toBeInTheDocument();
    expect(screen.getByText(/перевели 1 · 1 ждут подтверждения/)).toBeInTheDocument();
  });

  it('«Сумму выбираете сами»: подпись режима, «перевели N из M · обещали K», подсказка о молчунах, «Оплачу позже» шлёт сумму и дату', async () => {
    let sent: { amountKopecks: number; date: string } | null = null;
    const olya = { ...me, id: 'u-2', firstName: 'Оля' };
    const petya = { ...me, id: 'u-3', firstName: 'Петя' };
    mockDetail(buildDetail({
      kind: 'voluntary', freeAmountRequired: true, eventId: 'e-1', eventTitle: 'Покатушки', eventDatetime: FUTURE,
      amountKopecks: 900000, targetKopecks: 900000, receivedKopecks: 500000, promisedKopecks: 150000, promisedCount: 1,
      receivedCount: 2, debtCount: 3, openCount: 1, myDebt: null, enrolled: [me, olya, petya], paid: [creator, petya],
    }));
    server.use(
      http.post('*/api/skladchinas/s-1/promise', async ({ request }) => {
        sent = (await request.json()) as { amountKopecks: number; date: string };
        return HttpResponse.json(buildDetail({ kind: 'voluntary', freeAmountRequired: true }));
      }),
    );
    const { user } = renderPage();
    expect(await screen.findByText(/Сумму выбираете сами/)).toBeInTheDocument();
    // Круг = позвали трое + создатель, который скинулся.
    expect(screen.getByText(/перевели 2 из 4 · обещали 1/)).toBeInTheDocument();
    expect(screen.getByText('после срока остаток разделится между теми, кто промолчал')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Оплачу позже' }));
    await user.type(screen.getByLabelText('Сумма обещания'), '1500');
    await user.click(screen.getByRole('button', { name: 'Обещаю' }));
    await user.click(screen.getByRole('button', { name: 'Подтвердить' }));
    expect(sent).toEqual({ amountKopecks: 150000, date: expect.any(String) });
  });
});

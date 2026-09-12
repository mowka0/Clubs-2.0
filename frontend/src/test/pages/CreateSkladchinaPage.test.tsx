import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { CreateSkladchinaRequest, MemberListItemDto } from '../../types/api';

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

import { CreateSkladchinaPage } from '../../pages/CreateSkladchinaPage';

const CLUB_ID = 'club-1';

const MEMBER: MemberListItemDto = {
  userId: 'u-1',
  firstName: 'Анна',
  lastName: null,
  avatarUrl: null,
  role: 'member',
  joinedAt: null,
  trust: null,
  promiseFulfillmentPct: null,
  totalConfirmations: null,
  awards: [],
  accessStatus: 'active',
  subscriptionExpiresAt: null,
};

function renderPage(query: string) {
  const user = userEvent.setup();
  const result = renderWithProviders(
    <Routes>
      <Route path="/clubs/:id/skladchina/new" element={<CreateSkladchinaPage />} />
      <Route path="/skladchina/:id" element={<div data-testid="detail">detail</div>} />
    </Routes>,
    { routerEntries: [`/clubs/${CLUB_ID}/skladchina/new${query}`] },
  );
  return { ...result, user };
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('CreateSkladchinaPage — одна форма на три вида', () => {
  it('«Скинуться» по списку: выбор человека, сумма поровну, тело запроса с kind и debtors', async () => {
    let sent: CreateSkladchinaRequest | null = null;
    server.use(
      http.get(`*/api/clubs/${CLUB_ID}/members`, () => HttpResponse.json([MEMBER])),
      http.post(`*/api/clubs/${CLUB_ID}/skladchinas`, async ({ request }) => {
        sent = (await request.json()) as CreateSkladchinaRequest;
        return HttpResponse.json({ id: 's-new', clubId: CLUB_ID }, { status: 201 });
      }),
    );
    const { user } = renderPage('?kind=shared');

    expect(await screen.findByText(/Скинуться/)).toBeInTheDocument();
    await user.type(screen.getByLabelText(/Название/), 'Ужин');
    await user.type(screen.getByPlaceholderText('Например, 6000'), '6000');
    await user.type(screen.getByPlaceholderText('Ссылка СБП или номер телефона'), 'https://pay.example/x');
    await user.click(await screen.findByText('Анна'));
    await user.click(screen.getByRole('button', { name: 'Создать сбор' }));

    expect(await screen.findByTestId('detail')).toBeInTheDocument();
    expect(sent).not.toBeNull();
    expect(sent!.kind).toBe('shared');
    expect(sent!.amountKopecks).toBe(600000);
    expect(sent!.debtors).toEqual([{ userId: 'u-1', amountKopecks: null }]);
    expect(sent!.deadline).toBeTruthy();
  });

  it('«Кто берёт?» просит цену за человека; «По желанию» позволяет обойтись без срока', async () => {
    server.use(http.get(`*/api/clubs/${CLUB_ID}/members`, () => HttpResponse.json([MEMBER])));
    renderPage('?kind=per_head');
    expect(await screen.findByText(/Цена за человека/)).toBeInTheDocument();
    expect(screen.queryByText('Кто платит')).not.toBeInTheDocument();
  });

  it('«По желанию» показывает «Скрыть от» и чекбокс «Без срока»', async () => {
    server.use(http.get(`*/api/clubs/${CLUB_ID}/members`, () => HttpResponse.json([MEMBER])));
    renderPage('?kind=voluntary');
    expect(await screen.findByText('Скрыть от')).toBeInTheDocument();
    expect(screen.getByLabelText('Без срока')).toBeChecked();
  });
});

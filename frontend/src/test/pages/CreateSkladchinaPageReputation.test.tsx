import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { MemberListItemDto } from '../../types/api';

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

// ID клуба-фикстуры — общий для роута страницы и всех msw-моков
const CLUB_ID = 'club-1';

// Единственный участник клуба в моках — его выбирают при создании сбора
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

function mockMembers() {
  server.use(
    http.get(`*/api/clubs/${CLUB_ID}/members`, () => HttpResponse.json([MEMBER])),
  );
}

function renderPage() {
  const user = userEvent.setup();
  const result = renderWithProviders(
    <Routes>
      <Route path="/clubs/:id/skladchina/new" element={<CreateSkladchinaPage />} />
    </Routes>,
    { routerEntries: [`/clubs/${CLUB_ID}/skladchina/new`] },
  );
  return { ...result, user };
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('CreateSkladchinaPage — «Важный сбор» checkbox', () => {
  it('показывает лейбл «Важный сбор» с актуальным прайсом (+10 / без штрафа / −40)', async () => {
    mockMembers();
    renderPage();

    expect(await screen.findByText('Важный сбор')).toBeInTheDocument();
    expect(
      screen.getByText('Влияет на репутацию участников: оплата +10, отказ — без штрафа, молчание до дедлайна −40'),
    ).toBeInTheDocument();
    // Устаревший прайс из старого дизайна должен исчезнуть.
    expect(screen.queryByText(/за неответ −25/)).not.toBeInTheDocument();
  });

  it('в режиме voluntary чекбокс задизейблен, значение сброшено, показана подпись', async () => {
    mockMembers();
    const { user } = renderPage();

    const checkbox = await screen.findByRole('checkbox');
    await user.click(checkbox);
    expect(checkbox).toBeChecked();

    await user.click(screen.getByText('По желанию (без фикс. суммы)'));

    expect(checkbox).toBeDisabled();
    expect(checkbox).not.toBeChecked();
    expect(screen.getByText('Добровольный сбор не влияет на репутацию')).toBeInTheDocument();

    // Переключение обратно снова включает чекбокс (по-прежнему снятый).
    await user.click(screen.getByText('Поровну между всеми'));
    expect(checkbox).toBeEnabled();
    expect(checkbox).not.toBeChecked();
  });

  it('для важного сбора с дедлайном ближе 24 часов показывает ошибку до запроса', async () => {
    mockMembers();
    const { user } = renderPage();

    await user.type(await screen.findByLabelText(/Название/), 'Баня');
    await user.type(screen.getByPlaceholderText('Например, 5000'), '5000');
    await user.type(screen.getByPlaceholderText(/tinkoff/), 'https://pay.example/x');
    await user.click(screen.getByText('Анна'));
    await user.click(screen.getByRole('checkbox'));

    // дедлайн через ~2 часа (меньше 24 часов, требуемых для важного сбора)
    const soon = new Date(Date.now() + 2 * 60 * 60 * 1000);
    soon.setMinutes(soon.getMinutes() - soon.getTimezoneOffset());
    const deadlineInput = screen.getByLabelText(/Срок до/) as HTMLInputElement;
    // happy-dom не полностью эмулирует ввод в datetime-local — ставим значение через change-событие.
    fireEvent.change(deadlineInput, { target: { value: soon.toISOString().slice(0, 16) } });

    await user.click(screen.getByRole('button', { name: 'Создать сбор' }));

    expect(
      await screen.findByText('Для важного сбора дедлайн должен быть не раньше чем через 24 часа'),
    ).toBeInTheDocument();
  });

  it('показывает сообщение сервера при 400 (rate-limit важных сборов) и спец-текст при 429', async () => {
    mockMembers();
    // Бизнес-гейты бэкенда (дедлайн 24 ч, ≤3 важных за 7 дней) возвращаются как
    // 400 ValidationException с русским сообщением для пользователя.
    const rateLimitMessage =
      'Лимит важных сборов: не больше 3 за 7 дней в одном клубе. Создайте сбор без влияния на репутацию или попробуйте позже';
    server.use(
      http.post(`*/api/clubs/${CLUB_ID}/skladchinas`, () =>
        HttpResponse.json(
          { error: 'VALIDATION_ERROR', message: rateLimitMessage },
          { status: 400 },
        ),
      ),
    );
    const { user } = renderPage();

    await user.type(await screen.findByLabelText(/Название/), 'Баня');
    await user.type(screen.getByPlaceholderText('Например, 5000'), '5000');
    await user.type(screen.getByPlaceholderText(/tinkoff/), 'https://pay.example/x');
    await user.click(screen.getByText('Анна'));
    await user.click(screen.getByRole('button', { name: 'Создать сбор' }));

    expect(await screen.findByText(rateLimitMessage)).toBeInTheDocument();

    // Инфраструктурный 429 (bucket4j) → общая человекочитаемая подсказка «попробуйте позже».
    server.use(
      http.post(`*/api/clubs/${CLUB_ID}/skladchinas`, () =>
        HttpResponse.json(
          { error: 'RATE_LIMIT_EXCEEDED', message: 'Too many requests' },
          { status: 429 },
        ),
      ),
    );
    await user.click(screen.getByRole('button', { name: 'Создать сбор' }));
    expect(
      await screen.findByText('Слишком много запросов. Подождите немного и попробуйте снова.'),
    ).toBeInTheDocument();
  });
});

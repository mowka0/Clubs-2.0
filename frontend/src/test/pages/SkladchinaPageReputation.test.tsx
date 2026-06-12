import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { SkladchinaDetailDto, SkladchinaParticipantDto } from '../../types/api';

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
const FUTURE = new Date(Date.now() + 3 * 86_400_000).toISOString();

function buildDetail(overrides: Partial<SkladchinaDetailDto> = {}): SkladchinaDetailDto {
  return {
    id: SKLADCHINA_ID,
    clubId: 'club-1',
    clubName: 'Клуб',
    clubAvatarUrl: null,
    creatorId: 'org-1',
    title: 'Сбор на баню',
    description: null,
    rules: null,
    photoUrl: null,
    paymentMode: 'fixed_equal',
    totalGoalKopecks: 500000,
    collectedKopecks: 100000,
    paymentLink: 'https://pay.example/x',
    paymentMethodNote: null,
    deadline: FUTURE,
    affectsReputation: false,
    status: 'active',
    closedAt: null,
    isOrganizerView: false,
    myStatus: 'pending',
    myExpectedAmountKopecks: 100000,
    myDeclaredAmountKopecks: null,
    participants: null,
    participantCount: 5,
    paidCount: 1,
    ...overrides,
  };
}

function mockDetail(detail: SkladchinaDetailDto) {
  server.use(
    http.get(`*/api/skladchinas/${SKLADCHINA_ID}`, () => HttpResponse.json(detail)),
  );
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/skladchina/:id" element={<SkladchinaPage />} />
    </Routes>,
    { routerEntries: [`/skladchina/${SKLADCHINA_ID}`] },
  );
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('SkladchinaPage — reputation redesign UI', () => {
  it('показывает warning-блок и бейдж «Важный сбор» pending-участнику важного сбора', async () => {
    mockDetail(buildDetail({ affectsReputation: true, myStatus: 'pending' }));
    renderPage();

    expect(await screen.findByText('Сбор на баню')).toBeInTheDocument();
    expect(screen.getByText('⚠️ Важный сбор')).toBeInTheDocument();
    expect(
      screen.getByText(/Это важный сбор\. Оплатите или откажитесь до .+: молчание снизит репутацию на 40/),
    ).toBeInTheDocument();
    // Old badge text must be gone.
    expect(screen.queryByText('⚠️ С репутацией')).not.toBeInTheDocument();
  });

  it('не показывает warning-блок для обычного (не важного) сбора', async () => {
    mockDetail(buildDetail({ affectsReputation: false, myStatus: 'pending' }));
    renderPage();

    expect(await screen.findByText('Сбор на баню')).toBeInTheDocument();
    expect(screen.queryByText(/молчание снизит репутацию/)).not.toBeInTheDocument();
    expect(screen.queryByText('⚠️ Важный сбор')).not.toBeInTheDocument();
  });

  it('released-участник видит плашку о досрочном закрытии, а не «Срок истёк»', async () => {
    mockDetail(buildDetail({
      status: 'closed_failed',
      affectsReputation: true,
      myStatus: 'released',
      closedAt: new Date().toISOString(),
    }));
    renderPage();

    expect(
      await screen.findByText('Сбор закрыли досрочно — ваш ответ не потребовался'),
    ).toBeInTheDocument();
    expect(screen.getByText('Репутация не изменилась')).toBeInTheDocument();
    expect(
      screen.queryByText('Срок истёк, оплата не зарегистрирована'),
    ).not.toBeInTheDocument();
  });

  it('expired_no_response-участник по-прежнему видит «Срок истёк»', async () => {
    mockDetail(buildDetail({
      status: 'closed_failed',
      affectsReputation: true,
      myStatus: 'expired_no_response',
      closedAt: new Date().toISOString(),
    }));
    renderPage();

    expect(
      await screen.findByText('Срок истёк, оплата не зарегистрирована'),
    ).toBeInTheDocument();
    expect(
      screen.queryByText(/Сбор закрыли досрочно/),
    ).not.toBeInTheDocument();
  });

  it('организатор видит «Не потребовался» для released-участника и «Не ответил» для expired', async () => {
    const participants: SkladchinaParticipantDto[] = [
      {
        userId: 'u-released', firstName: 'Анна', lastName: null, avatarUrl: null,
        expectedAmountKopecks: 100000, declaredAmountKopecks: null,
        status: 'released', paidAt: null,
      },
      {
        userId: 'u-expired', firstName: 'Глеб', lastName: null, avatarUrl: null,
        expectedAmountKopecks: 100000, declaredAmountKopecks: null,
        status: 'expired_no_response', paidAt: null,
      },
    ];
    mockDetail(buildDetail({
      status: 'closed_failed',
      isOrganizerView: true,
      myStatus: null,
      participants,
      closedAt: new Date().toISOString(),
    }));
    renderPage();

    expect(await screen.findByText('Не потребовался')).toBeInTheDocument();
    expect(screen.getByText('Не ответил')).toBeInTheDocument();
  });
});

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
    template: 'custom',
    eventId: null,
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
    declineRequiresApproval: false,
    myDeclineRequested: false,
    myDeclineRejected: false,
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
        declineRequested: false, declineNote: null, declineRejected: false,
      },
      {
        userId: 'u-expired', firstName: 'Глеб', lastName: null, avatarUrl: null,
        expectedAmountKopecks: 100000, declaredAmountKopecks: null,
        status: 'expired_no_response', paidAt: null,
        declineRequested: false, declineNote: null, declineRejected: false,
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

describe('SkladchinaPage — Phase A', () => {
  it('A-1: fixed-режим — одна кнопка «Я оплатил {доля} ₽» без поля суммы', async () => {
    mockDetail(buildDetail({ paymentMode: 'fixed_equal', myStatus: 'pending', myExpectedAmountKopecks: 100000 }));
    renderPage();

    expect(await screen.findByText('Я оплатил 1 000 ₽')).toBeInTheDocument();
    // No amount input for fixed modes.
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument();
  });

  it('A-1: voluntary — поле суммы остаётся, кнопка «Я оплатил» без суммы', async () => {
    mockDetail(buildDetail({ paymentMode: 'voluntary', myStatus: 'pending', myExpectedAmountKopecks: null, totalGoalKopecks: null }));
    renderPage();

    expect(await screen.findByRole('spinbutton')).toBeInTheDocument();
    expect(screen.getByText('Я оплатил')).toBeInTheDocument();
  });

  it('A-5: прогресс показан в людях «Скинулись N из M»', async () => {
    mockDetail(buildDetail({ paidCount: 1, participantCount: 5 }));
    renderPage();

    expect(await screen.findByText('Скинулись 1 из 5')).toBeInTheDocument();
  });

  it('A-2/A-3: организатор fixed active — кнопки отметки и панель «Не хватает X ₽»', async () => {
    const participants: SkladchinaParticipantDto[] = [
      {
        userId: 'u-pending', firstName: 'Иван', lastName: null, avatarUrl: null,
        expectedAmountKopecks: 100000, declaredAmountKopecks: null, status: 'pending', paidAt: null,
        declineRequested: false, declineNote: null, declineRejected: false,
      },
      {
        userId: 'u-paid', firstName: 'Пётр', lastName: null, avatarUrl: null,
        expectedAmountKopecks: 100000, declaredAmountKopecks: 100000, status: 'paid',
        paidAt: new Date().toISOString(),
        declineRequested: false, declineNote: null, declineRejected: false,
      },
    ];
    mockDetail(buildDetail({
      paymentMode: 'fixed_equal',
      isOrganizerView: true,
      myStatus: null,
      participants,
      collectedKopecks: 100000,
      totalGoalKopecks: 500000,
    }));
    renderPage();

    expect(await screen.findByText('Отметить оплату')).toBeInTheDocument(); // pending row
    expect(screen.getByText('Отменить')).toBeInTheDocument();               // paid row
    expect(screen.getByText('Не хватает 4 000 ₽')).toBeInTheDocument();     // 500000 − 100000
    expect(screen.getByText('Перераспределить на неоплативших')).toBeInTheDocument();
  });

  it('A-2: для voluntary у организатора нет кнопок отметки оплаты', async () => {
    const participants: SkladchinaParticipantDto[] = [
      {
        userId: 'u-pending', firstName: 'Иван', lastName: null, avatarUrl: null,
        expectedAmountKopecks: null, declaredAmountKopecks: null, status: 'pending', paidAt: null,
        declineRequested: false, declineNote: null, declineRejected: false,
      },
    ];
    mockDetail(buildDetail({
      paymentMode: 'voluntary',
      isOrganizerView: true,
      myStatus: null,
      participants,
      totalGoalKopecks: null,
    }));
    renderPage();

    expect(await screen.findByText('Иван')).toBeInTheDocument();
    expect(screen.queryByText('Отметить оплату')).not.toBeInTheDocument();
    expect(screen.queryByText('Не хватает')).not.toBeInTheDocument();
  });
});

describe('SkladchinaPage — decline-with-approval (V28)', () => {
  it('approval-сбор: pending → кнопка «Запросить отказ», не «Отказаться»', async () => {
    mockDetail(buildDetail({ declineRequiresApproval: true, myStatus: 'pending' }));
    renderPage();
    expect(await screen.findByText('Запросить отказ')).toBeInTheDocument();
    expect(screen.queryByText('Отказаться')).not.toBeInTheDocument();
  });

  it('FREE-сбор: pending → обычная «Отказаться»', async () => {
    mockDetail(buildDetail({ declineRequiresApproval: false, myStatus: 'pending' }));
    renderPage();
    expect(await screen.findByText('Отказаться')).toBeInTheDocument();
    expect(screen.queryByText('Запросить отказ')).not.toBeInTheDocument();
  });

  it('запрос на отказ отправлен → плашка ожидания, без кнопки отказа', async () => {
    mockDetail(buildDetail({ declineRequiresApproval: true, myStatus: 'pending', myDeclineRequested: true }));
    renderPage();
    expect(await screen.findByText(/Запрос на отказ отправлен/)).toBeInTheDocument();
    expect(screen.queryByText('Запросить отказ')).not.toBeInTheDocument();
  });

  it('отказ отклонён → плашка «нужно оплатить»', async () => {
    mockDetail(buildDetail({ declineRequiresApproval: true, myStatus: 'pending', myDeclineRejected: true }));
    renderPage();
    expect(await screen.findByText(/Запрос на отказ отклонён/)).toBeInTheDocument();
  });

  it('организатор видит заявку на отказ с запиской и кнопками', async () => {
    const participants: SkladchinaParticipantDto[] = [
      {
        userId: 'u-1', firstName: 'Иван', lastName: null, avatarUrl: null,
        expectedAmountKopecks: 100000, declaredAmountKopecks: null, status: 'pending', paidAt: null,
        declineRequested: true, declineNote: 'не ел, только смотрел', declineRejected: false,
      },
    ];
    mockDetail(buildDetail({
      declineRequiresApproval: true,
      paymentMode: 'fixed_equal',
      isOrganizerView: true,
      myStatus: null,
      participants,
    }));
    renderPage();

    expect(await screen.findByText('Просит отказаться')).toBeInTheDocument();
    expect(screen.getByText('«не ел, только смотрел»')).toBeInTheDocument();
    expect(screen.getByText('Одобрить отказ')).toBeInTheDocument();
    expect(screen.getByText('Отклонить')).toBeInTheDocument();
  });
});

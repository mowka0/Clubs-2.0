import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, fireEvent } from '@testing-library/react';
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

// Id складчины, используемый во всех моках теста.
const SKLADCHINA_ID = 's-1';
// Дедлайн в будущем (+3 дня) — сбор ещё активен.
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
    eventTitle: null,
    eventDatetime: null,
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
    myDeclineRejectNote: null,
    awaitingConfirmation: false,
    myPaymentRejectNote: null,
    myReceiptUrl: null,
    myDisputeDeadline: null,
    myDisputeTerminal: false,
    participants: null,
    participantCount: 5,
    paidCount: 1,
    pendingCount: 4,
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
    // Старый текст бейджа должен исчезнуть.
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
        declineRequested: false, declineNote: null, declineRejected: false, declineRejectNote: null,
        paymentRejectNote: null, receiptUrl: null, receiptNote: null,
        disputedAt: null, disputeTerminal: false,
      },
      {
        userId: 'u-expired', firstName: 'Глеб', lastName: null, avatarUrl: null,
        expectedAmountKopecks: 100000, declaredAmountKopecks: null,
        status: 'expired_no_response', paidAt: null,
        declineRequested: false, declineNote: null, declineRejected: false, declineRejectNote: null,
        paymentRejectNote: null, receiptUrl: null, receiptNote: null,
        disputedAt: null, disputeTerminal: false,
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
    // Для fixed-режимов поля суммы нет.
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

  it('A-2: организатор fixed active — кнопки «Отметить оплату» / «Отменить» (без перераспределения)', async () => {
    const participants: SkladchinaParticipantDto[] = [
      {
        userId: 'u-pending', firstName: 'Иван', lastName: null, avatarUrl: null,
        expectedAmountKopecks: 100000, declaredAmountKopecks: null, status: 'pending', paidAt: null,
        declineRequested: false, declineNote: null, declineRejected: false, declineRejectNote: null,
        paymentRejectNote: null, receiptUrl: null, receiptNote: null,
        disputedAt: null, disputeTerminal: false,
      },
      {
        userId: 'u-paid', firstName: 'Пётр', lastName: null, avatarUrl: null,
        expectedAmountKopecks: 100000, declaredAmountKopecks: 100000, status: 'paid',
        paidAt: new Date().toISOString(),
        declineRequested: false, declineNote: null, declineRejected: false, declineRejectNote: null,
        paymentRejectNote: null, receiptUrl: null, receiptNote: null,
        disputedAt: null, disputeTerminal: false,
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

    expect(await screen.findByText('Отметить оплату')).toBeInTheDocument(); // строка pending
    expect(screen.getByText('Отменить')).toBeInTheDocument();               // строка paid
    // Перераспределение убрано — панели дефицита больше нет.
    expect(screen.queryByText(/Не хватает/)).not.toBeInTheDocument();
    expect(screen.queryByText('Перераспределить на неоплативших')).not.toBeInTheDocument();
  });

  it('A-2: для voluntary у организатора нет кнопок отметки оплаты', async () => {
    const participants: SkladchinaParticipantDto[] = [
      {
        userId: 'u-pending', firstName: 'Иван', lastName: null, avatarUrl: null,
        expectedAmountKopecks: null, declaredAmountKopecks: null, status: 'pending', paidAt: null,
        declineRequested: false, declineNote: null, declineRejected: false, declineRejectNote: null,
        paymentRejectNote: null, receiptUrl: null, receiptNote: null,
        disputedAt: null, disputeTerminal: false,
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
        declineRequested: true, declineNote: 'не ел, только смотрел', declineRejected: false, declineRejectNote: null,
        paymentRejectNote: null, receiptUrl: null, receiptNote: null,
        disputedAt: null, disputeTerminal: false,
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

  it('V29: «Отклонить» открывает поле причины, кнопка отказа активна только с текстом', async () => {
    const participants: SkladchinaParticipantDto[] = [
      {
        userId: 'u-1', firstName: 'Иван', lastName: null, avatarUrl: null,
        expectedAmountKopecks: 100000, declaredAmountKopecks: null, status: 'pending', paidAt: null,
        declineRequested: true, declineNote: 'не хочу', declineRejected: false, declineRejectNote: null,
        paymentRejectNote: null, receiptUrl: null, receiptNote: null,
        disputedAt: null, disputeTerminal: false,
      },
    ];
    mockDetail(buildDetail({
      declineRequiresApproval: true, paymentMode: 'fixed_equal', isOrganizerView: true,
      myStatus: null, participants,
    }));
    renderPage();

    fireEvent.click(await screen.findByText('Отклонить'));
    const reason = screen.getByPlaceholderText('Почему участник должен оплатить (обязательно)');
    expect(reason).toBeInTheDocument();
    // Кнопка подтверждения неактивна, пока не введена причина.
    const confirm = screen.getByText('Отклонить заявку');
    expect(confirm).toBeDisabled();
    fireEvent.change(reason, { target: { value: 'ты был на событии' } });
    expect(confirm).not.toBeDisabled();
  });
});

describe('SkladchinaPage — шапка сбора по встрече', () => {
  const splitDetail = (overrides: Partial<SkladchinaDetailDto> = {}) =>
    buildDetail({
      template: 'split_bill',
      eventId: 'ev-1',
      eventTitle: 'Ужин в «Веранде»',
      eventDatetime: '2026-09-03T16:00:00Z',
      title: 'Счёт: Ужин в «Веранде»',
      affectsReputation: true,
      description: 'Еда и напитки на компанию',
      ...overrides,
    });

  it('показывает встречу отдельным блоком, без заголовка-пересказа и ряда бейджей', async () => {
    mockDetail(splitDetail());
    renderPage();

    expect(await screen.findByText('Счёт за встречу')).toBeInTheDocument();
    expect(screen.getByText('Ужин в «Веранде»')).toBeInTheDocument();
    // Заголовок сбора повторял название встречи — его больше нет.
    expect(screen.queryByText('Счёт: Ужин в «Веранде»')).not.toBeInTheDocument();
    // Бейджи ушли: «Активен» очевиден по кнопке оплаты, «Важный сбор» — по предупреждению ниже.
    expect(screen.queryByText('Активен')).not.toBeInTheDocument();
    expect(screen.queryByText('⚠️ Важный сбор')).not.toBeInTheDocument();
    // Клуб остаётся крошкой над блоком встречи.
    expect(screen.getByLabelText('Открыть клуб Клуб')).toBeInTheDocument();
    // Описание переехало внутрь блока сбора.
    expect(screen.getByText('Еда и напитки на компанию')).toBeInTheDocument();
  });

  it('показывает своё название сбора, если организатор написал не «Счёт: <встреча>»', async () => {
    mockDetail(splitDetail({ title: 'Скидываемся на Веранду' }));
    renderPage();

    expect(await screen.findByText('Скидываемся на Веранду')).toBeInTheDocument();
  });

  it('у обычного сбора шапка прежняя: плитка клуба, заголовок и бейджи', async () => {
    mockDetail(buildDetail({ affectsReputation: true }));
    renderPage();

    expect(await screen.findByText('Сбор на баню')).toBeInTheDocument();
    expect(screen.getByText('Сбор в клубе')).toBeInTheDocument();
    expect(screen.getByText('⚠️ Важный сбор')).toBeInTheDocument();
    expect(screen.queryByText('Счёт за встречу')).not.toBeInTheDocument();
  });
});

describe('SkladchinaPage — сверка оплат организатором (V89)', () => {
  it('заявивший оплату видит «ждём сверки» и может снять свою отметку', async () => {
    mockDetail(buildDetail({ myStatus: 'paid', myDeclaredAmountKopecks: 100000 }));
    renderPage();

    expect(await screen.findByText(/Вы отметили оплату/)).toBeInTheDocument();
    expect(
      screen.getByText('Организатор сверит с выпиской и подтвердит, когда сбор закроется.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Отменить отметку' })).toBeInTheDocument();
  });

  it('после начала сверки отметку снять уже нельзя', async () => {
    mockDetail(buildDetail({ myStatus: 'paid', awaitingConfirmation: true }));
    renderPage();

    expect(await screen.findByText(/Вы отметили оплату/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Отменить отметку' })).not.toBeInTheDocument();
  });

  it('подтверждённая оплата показывает плюс к надёжности в важном сборе', async () => {
    mockDetail(buildDetail({
      status: 'closed_success',
      affectsReputation: true,
      myStatus: 'payment_confirmed',
      myDeclaredAmountKopecks: 100000,
      closedAt: new Date().toISOString(),
    }));
    renderPage();

    expect(await screen.findByText(/Оплата подтверждена/)).toBeInTheDocument();
    expect(screen.getByText(/\+10 к надёжности/)).toBeInTheDocument();
  });

  it('отклонённая оплата показывает причину, срок и форму чека', async () => {
    const deadline = new Date(Date.now() + 40 * 3_600_000).toISOString();
    mockDetail(buildDetail({
      status: 'closed_failed',
      affectsReputation: true,
      myStatus: 'payment_rejected',
      myPaymentRejectNote: 'В выписке 833 ₽ от вас нет',
      myDisputeDeadline: deadline,
      closedAt: new Date().toISOString(),
    }));
    renderPage();

    expect(await screen.findByText('Организатор не нашёл ваш платёж')).toBeInTheDocument();
    expect(screen.getByText('«В выписке 833 ₽ от вас нет»')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Приложить чек и оспорить' })).toBeInTheDocument();
    expect(
      screen.getByText('Без чека оспорить нельзя — организатор сверяет с выпиской.'),
    ).toBeInTheDocument();
  });

  it('после окончательного отказа организатора форма чека не показывается', async () => {
    mockDetail(buildDetail({
      status: 'closed_failed',
      myStatus: 'payment_rejected',
      myDisputeTerminal: true,
      closedAt: new Date().toISOString(),
    }));
    renderPage();

    expect(await screen.findByText('Организатор не нашёл ваш платёж')).toBeInTheDocument();
    expect(
      screen.getByText('Чек рассмотрен, платёж не подтверждён — решение окончательное.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Приложить чек и оспорить' })).not.toBeInTheDocument();
  });

  it('во время спора списание заморожено', async () => {
    mockDetail(buildDetail({
      status: 'closed_failed',
      affectsReputation: true,
      myStatus: 'payment_disputed',
      myReceiptUrl: '/uploads/receipt.jpg',
      closedAt: new Date().toISOString(),
    }));
    renderPage();

    expect(await screen.findByText(/Чек отправлен организатору/)).toBeInTheDocument();
    expect(screen.getByText(/40 очков не списываются/)).toBeInTheDocument();
  });

  it('организатор завершённого сбора сразу попадает на список сверки', async () => {
    mockDetail(buildDetail({
      isOrganizerView: true,
      awaitingConfirmation: true,
      myStatus: null,
      participants: [
        {
          userId: 'u-1', firstName: 'Анна', lastName: null, avatarUrl: null,
          expectedAmountKopecks: 100000, declaredAmountKopecks: 100000,
          status: 'paid', paidAt: new Date().toISOString(),
          declineRequested: false, declineNote: null, declineRejected: false, declineRejectNote: null,
          paymentRejectNote: null, receiptUrl: null, receiptNote: null,
          disputedAt: null, disputeTerminal: false,
        },
      ],
    }));
    renderPage();

    expect(await screen.findByRole('button', { name: 'Подтвердить и закрыть сбор' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Снять отметку оплаты: Анна' })).toBeInTheDocument();
    expect(
      screen.getByText('Снятая галка = платёж не дошёл: у человека будет 48 часов прислать чек.'),
    ).toBeInTheDocument();
  });
});

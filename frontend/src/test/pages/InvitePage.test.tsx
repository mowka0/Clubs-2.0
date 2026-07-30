import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { mockClubDetail } from '../mocks/handlers';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { ClubDetailDto, ClubEventsTeaserDto, ClubFactsDto } from '../../types/api';

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
  backButton: { show: vi.fn(), hide: vi.fn(), onClick: vi.fn(() => vi.fn()) },
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

import { InvitePage } from '../../pages/InvitePage';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const livingClubFacts: ClubFactsDto = {
  meetingsPerMonth: 2.4,
  avgAttendance: 9,
  coreSize: 6,
  ageMonths: 8,
  totalMeetings: 19,
  successfulSkladchinas: 0,
};

const youngClubFacts: ClubFactsDto = {
  meetingsPerMonth: 0,
  avgAttendance: 0,
  coreSize: 0,
  ageMonths: 0,
  totalMeetings: 0,
  successfulSkladchinas: 0,
};

const teaser: ClubEventsTeaserDto = {
  upcoming: [
    {
      id: 'e1', title: 'Сходка на Патриках', eventDatetime: '2026-08-01T17:00:00Z',
      status: 'upcoming', isUrgent: false, isOpenEvent: false, goingCount: 4, confirmedCount: 0,
    },
  ],
  past: [],
  totalPastCount: 19,
};

/**
 * Приглашение с подставленным клубом и (опционально) фактами/афишей. Отсутствующие
 * эндпоинты отдают пусто — так проверяется fail-soft молодого клуба.
 */
function mockInvite(club: Partial<ClubDetailDto>, facts: ClubFactsDto = livingClubFacts, withTeaser = true) {
  const detail: ClubDetailDto = { ...mockClubDetail, ownerFirstName: 'Иван', ...club };
  server.use(
    http.get('*/api/invite/:code', () => HttpResponse.json(detail)),
    http.get('*/api/users/me/clubs', () => HttpResponse.json([])),
    http.get(`*/api/clubs/${detail.id}/quality`, () => HttpResponse.json(facts)),
    http.get(`*/api/clubs/${detail.id}/events/teaser`, () => HttpResponse.json(
      withTeaser ? teaser : { upcoming: [], past: [], totalPastCount: 0 },
    )),
  );
}

/** Членство вызывающего в этом клубе — для веток «уже участник» и «должник». */
function mockMyMembership(status: string, clubId = mockClubDetail.id) {
  server.use(
    http.get('*/api/users/me/clubs', () => HttpResponse.json([{
      id: 'mem-1', userId: 'user-1', clubId, status,
      role: 'member', joinedAt: '2026-01-01T00:00:00Z', subscriptionExpiresAt: null,
    }])),
  );
}

/** Успешное вступление + детали клуба с реквизитами (их шит взноса берёт отдельным запросом). */
function mockJoinAndRequisites(club: Partial<ClubDetailDto> = {}) {
  const detail: ClubDetailDto = { ...mockClubDetail, ...club };
  server.use(
    http.post('*/api/invite/:code/join', () => HttpResponse.json({
      id: 'mem-new', userId: 'user-1', clubId: detail.id,
      status: detail.subscriptionPrice > 0 ? 'frozen' : 'active',
      role: 'member', joinedAt: '2026-07-30T00:00:00Z', subscriptionExpiresAt: null,
    }, { status: 201 })),
    http.get(`*/api/clubs/${detail.id}`, () => HttpResponse.json({
      ...detail, paymentLink: 'https://sbp.example/pay', paymentMethodNote: 'Сбербанк',
    })),
  );
}

function renderInvite() {
  return renderWithProviders(
    <Routes>
      <Route path="/invite/:code" element={<InvitePage />} />
      <Route path="/clubs/:id" element={<div>Страница клуба</div>} />
    </Routes>,
    { routerEntries: ['/invite/abc123'] },
  );
}

describe('InvitePage — посадочная в языке страницы клуба', () => {
  it('показывает шапку клуба, организатора, кольца и афишу', async () => {
    mockInvite({ name: 'Партия', rules: 'Отменять участие не позже чем за сутки.' });
    renderInvite();

    // Шапка — та же, что на странице клуба: название, чипы параметров.
    expect(await screen.findByText('Партия')).toBeInTheDocument();
    expect(screen.getByText('Открытый')).toBeInTheDocument();
    expect(screen.getByText('10 / 50')).toBeInTheDocument();

    // Кто зовёт — наверху, а не сноской под кнопкой.
    expect(screen.getByText(/Организатор — Иван/)).toBeInTheDocument();

    // Правила раньше не выводились вовсе, хотя приходили в ответе.
    expect(screen.getByText('Правила')).toBeInTheDocument();

    // Публичные блоки страницы клуба.
    await waitFor(() => expect(screen.getByText('Жизнь клуба')).toBeInTheDocument());
    expect(await screen.findByText('Афиша клуба')).toBeInTheDocument();
    expect(screen.getByText('Сходка на Патриках')).toBeInTheDocument();

    expect(screen.getByRole('button', { name: 'Вступить в клуб' })).toBeInTheDocument();
  });

  it('в платном клубе объясняет, что кнопка ничего не списывает', async () => {
    mockInvite({ subscriptionPrice: 1500 });
    renderInvite();

    expect(await screen.findByText(/платформа денег не касается и ничего не списывает/)).toBeInTheDocument();
    expect(screen.getByText(/взнос вы передаёте напрямую, минуя платформу/)).toBeInTheDocument();
  });

  it('у молодого клуба вместо пустоты — обещание «одним из первых»', async () => {
    mockInvite({ memberCount: 2 }, youngClubFacts, false);
    renderInvite();

    expect(await screen.findByText(/вы будете одним из первых/)).toBeInTheDocument();
    // Афиша fail-soft: встреч нет — блока нет.
    expect(screen.queryByText('Афиша клуба')).not.toBeInTheDocument();
  });

  it('живому клубу обещание «одним из первых» не показывает', async () => {
    mockInvite({ memberCount: 2 });
    renderInvite();

    await screen.findByText('Афиша клуба');
    expect(screen.queryByText(/вы будете одним из первых/)).not.toBeInTheDocument();
  });

  it('в клуб «по заявке» ведёт заявкой, а не вступлением', async () => {
    mockInvite({ inviteRequiresApplication: true, accessType: 'closed' });
    renderInvite();

    expect(await screen.findByRole('button', { name: 'Отправить заявку' })).toBeInTheDocument();
    expect(screen.getByText(/Организатор посмотрит заявку и откроет доступ/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Вступить в клуб' })).not.toBeInTheDocument();
  });

  it('чат клуба — пилюлей в «О клубе», по тапу подсказка с кнопкой вступления', async () => {
    const user = userEvent.setup();
    mockInvite({ chatLinked: true, chatDoorEnabled: true });
    renderInvite();

    const pill = await screen.findByRole('button', { name: /В чат/ });
    // Подсказка закрыта, пока по пилюле не нажали.
    expect(screen.queryByText(/бот впустит вас туда/)).not.toBeInTheDocument();

    await user.click(pill);
    expect(screen.getByText(/Чат клуба открыт участникам/)).toBeInTheDocument();
    // В подсказке — то же действие, что и в главном CTA.
    expect(screen.getAllByRole('button', { name: 'Вступить в клуб' })).toHaveLength(2);

    // Повторный тап закрывает.
    await user.click(pill);
    expect(screen.queryByText(/Чат клуба открыт участникам/)).not.toBeInTheDocument();
  });

  it('чат без включённой двери — пилюля есть, авто-впуск не обещается', async () => {
    const user = userEvent.setup();
    mockInvite({ chatLinked: true, chatDoorEnabled: false });
    renderInvite();

    await user.click(await screen.findByRole('button', { name: /В чат/ }));
    expect(screen.getByText(/Организатор позовёт вас туда после вступления/)).toBeInTheDocument();
    expect(screen.queryByText(/бот впустит вас туда/)).not.toBeInTheDocument();
  });

  it('платный клуб: вступление и оплата — один экран, без пересадок', async () => {
    const user = userEvent.setup();
    mockInvite({ subscriptionPrice: 1500 });
    mockJoinAndRequisites({ subscriptionPrice: 1500 });
    renderInvite();

    await user.click(await screen.findByRole('button', { name: 'Вступить и оплатить взнос' }));

    // Сразу шит оплаты — ни «Добро пожаловать», ни страницы клуба между ними.
    expect(await screen.findByRole('button', { name: /Подтвердить оплату/ })).toBeInTheDocument();
    expect(screen.queryByText('Добро пожаловать!')).not.toBeInTheDocument();
    expect(screen.queryByText('Страница клуба')).not.toBeInTheDocument();
  });

  it('бесплатный клуб знакомому пользователю: сразу в клуб, без экрана-пересадки', async () => {
    const user = userEvent.setup();
    mockInvite({ subscriptionPrice: 0 });
    mockJoinAndRequisites({ subscriptionPrice: 0 });
    renderInvite();

    await user.click(await screen.findByRole('button', { name: 'Вступить в клуб' }));

    expect(await screen.findByText('Страница клуба')).toBeInTheDocument();
    expect(screen.queryByText('Добро пожаловать!')).not.toBeInTheDocument();
  });

  it('должнику предлагают оплату, а не только дверь в клуб', async () => {
    mockInvite({ subscriptionPrice: 1500 });
    mockMyMembership('frozen');
    renderInvite();

    expect(await screen.findByRole('button', { name: 'Оплатить взнос' })).toBeInTheDocument();
    expect(screen.getByText(/остался взнос/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Перейти в клуб' })).toBeInTheDocument();
  });

  it('участнику с доступом — только дверь в клуб', async () => {
    mockInvite({});
    mockMyMembership('active');
    renderInvite();

    expect(await screen.findByRole('button', { name: 'Перейти в клуб' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Оплатить взнос' })).not.toBeInTheDocument();
  });

  it('снятые дубли на экран не возвращаются', async () => {
    mockInvite({ chatLinked: true, chatDoorEnabled: true, inviteRequiresApplication: true, accessType: 'closed' });
    renderInvite();

    await screen.findByText('Афиша клуба');
    // Строка-замок под афишей — то же самое говорит плашка ниже.
    expect(screen.queryByText(/Место встреч, голосование и участие/)).not.toBeInTheDocument();
    // Отдельный чип про чат внизу экрана заменён пилюлей в «О клубе».
    expect(screen.queryByText(/У клуба есть чат/)).not.toBeInTheDocument();
    // Чип «принимает по заявке» дублировал и подпись под кнопкой, и чип параметров.
    expect(screen.queryByText(/Клуб принимает по заявке/)).not.toBeInTheDocument();
  });

  it('в полном клубе предлагает попроситься', async () => {
    mockInvite({ memberCount: 50, memberLimit: 50 });
    renderInvite();

    expect(await screen.findByRole('button', { name: 'Попроситься в клуб' })).toBeInTheDocument();
    expect(screen.getByText(/В клубе кончились места/)).toBeInTheDocument();
  });
});

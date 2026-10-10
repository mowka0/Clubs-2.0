import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { FC } from 'react';
import { Route, Routes, useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { mockClubDetail } from '../mocks/handlers';
import { renderWithProviders } from '../utils/renderWithProviders';
import { useAuthStore } from '../../store/useAuthStore';
import type { ChatLinkStatusDto, ClubDetailDto, MemberListItemDto } from '../../types/api';

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

import { useChatPostWarning } from '../../components/club/ChatPostWarningSheet';
import { CreateSkladchinaPage } from '../../pages/CreateSkladchinaPage';

const CLUB_ID = 'club-1';
const OWNER_ID = 'owner-1';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function setViewer(id: string) {
  useAuthStore.setState({
    user: {
      id, telegramId: 1, telegramUsername: 'viewer', firstName: 'Viewer', lastName: null,
      avatarUrl: null, city: null, country: null, cityId: null, bio: null, onboardingTours: ['INTRO'],
    },
    isAuthenticated: true,
    isLoading: false,
    error: null,
  });
}

/** Сколько раз спросили статус чата: негативные кейсы ждут ответа, а не таймер. */
let chatLinkRequests = 0;

/** Клуб из чата, ещё не показанный в чате: бот админ, обе публикующие функции включены. */
function mockChat(over: Partial<ChatLinkStatusDto> = {}) {
  chatLinkRequests = 0;
  server.use(
    http.get('*/api/clubs/:id', ({ params }) => HttpResponse.json({
      ...mockClubDetail, id: params.id as string, ownerId: OWNER_ID, chatLinked: true,
    } as ClubDetailDto)),
    http.get('*/api/clubs/:id/chat-link', () => {
      chatLinkRequests += 1;
      return HttpResponse.json({
        linked: true, chatTitle: 'Тест Clubs', botStatus: 'administrator',
        livePinEnabled: true, skladchinaStatusEnabled: true, clubLinkPinned: false, ...over,
      });
    }),
  );
}

/** Статус чата приехал и отрисован: после ответа сервера даём React один такт на рендер. */
async function waitForChatStatus() {
  await waitFor(() => expect(chatLinkRequests).toBeGreaterThan(0));
  await new Promise((r) => setTimeout(r, 0));
}

const SettingsProbe: FC = () => {
  const location = useLocation();
  return <div>{`at:${location.pathname}${location.search}`}</div>;
};

/** Минимальная форма: рендерит шторку так же, как EventForm и форма сбора. */
const Harness: FC<{ kind: 'event' | 'skladchina' }> = ({ kind }) => {
  const chatPostWarning = useChatPostWarning(CLUB_ID, kind);
  return (
    <>
      <div>форма</div>
      {chatPostWarning}
    </>
  );
};

function renderHarness(kind: 'event' | 'skladchina') {
  const user = userEvent.setup();
  renderWithProviders(
    <Routes>
      <Route path="/form" element={<Harness kind={kind} />} />
      <Route path="/clubs/:id/manage" element={<SettingsProbe />} />
    </Routes>,
    { routerEntries: ['/form'] },
  );
  return { user };
}

describe('useChatPostWarning — шторка при открытии формы, пока клуб не показан в чате (PO 2026-10-10)', () => {
  beforeEach(() => setViewer(OWNER_ID));

  it('встреча при включённом живом закрепе: шторка сразу, «Создать встречу» закрывает её и оставляет в форме', async () => {
    mockChat();
    const { user } = renderHarness('event');

    expect(await screen.findByText(/Встреча появится в чате «Тест Clubs»/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Создать встречу' }));

    expect(screen.queryByText(/Встреча появится в чате/)).not.toBeInTheDocument();
    expect(screen.getByText('форма')).toBeInTheDocument();
  });

  it('«Настройки чата» ведёт в таб «Чат» управления', async () => {
    mockChat();
    const { user } = renderHarness('event');

    await user.click(await screen.findByRole('button', { name: 'Настройки чата' }));
    expect(await screen.findByText(`at:/clubs/${CLUB_ID}/manage?tab=chat`)).toBeInTheDocument();
  });

  it('живой закреп выключен — встреча в чат не уйдёт, шторки нет', async () => {
    mockChat({ livePinEnabled: false });
    renderHarness('event');
    await waitForChatStatus();
    expect(screen.queryByText(/появится в чате/)).not.toBeInTheDocument();
  });

  it('клуб уже показан в чате — без шторки', async () => {
    mockChat({ clubLinkPinned: true });
    renderHarness('skladchina');
    await waitForChatStatus();
    expect(screen.queryByText(/появится в чате/)).not.toBeInTheDocument();
  });

  it('сбор смотрит на свой тумблер — «Статус сборов в чате»', async () => {
    mockChat({ livePinEnabled: false, skladchinaStatusEnabled: true });
    renderHarness('skladchina');
    expect(await screen.findByText(/Сбор появится в чате «Тест Clubs»/)).toBeInTheDocument();
    expect(screen.getByText(/«Статус сборов в чате»/)).toBeInTheDocument();
  });

  it('не владельцу не показывается: статус чата ему даже не запрашивается', async () => {
    setViewer('someone-else');
    let clubRequested = false;
    mockChat();
    server.use(http.get('*/api/clubs/:id', ({ params }) => {
      clubRequested = true;
      return HttpResponse.json({ ...mockClubDetail, id: params.id as string, ownerId: OWNER_ID, chatLinked: true } as ClubDetailDto);
    }));
    renderHarness('event');
    await waitFor(() => expect(clubRequested).toBe(true));
    await new Promise((r) => setTimeout(r, 0));
    expect(chatLinkRequests).toBe(0);
    expect(screen.queryByText(/появится в чате/)).not.toBeInTheDocument();
  });
});

describe('CreateSkladchinaPage — шторка при открытии формы сбора', () => {
  const MEMBER: MemberListItemDto = {
    userId: 'u-1', firstName: 'Анна', lastName: null, avatarUrl: null, role: 'member', joinedAt: null,
    trust: null, promiseFulfillmentPct: null, totalConfirmations: null, awards: [],
    accessStatus: 'active', subscriptionExpiresAt: null,
  };

  beforeEach(() => setViewer(OWNER_ID));

  it('шторка встречает на открытии; закрыл — заполнил — «Создать сбор» создаёт с первого нажатия', async () => {
    let posts = 0;
    mockChat();
    server.use(
      http.get(`*/api/clubs/${CLUB_ID}/members`, () => HttpResponse.json([MEMBER])),
      http.post(`*/api/clubs/${CLUB_ID}/skladchinas`, () => {
        posts += 1;
        return HttpResponse.json({ id: 's-new', clubId: CLUB_ID }, { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route path="/clubs/:id/skladchina/new" element={<CreateSkladchinaPage />} />
        <Route path="/skladchina/:id" element={<div data-testid="detail">detail</div>} />
      </Routes>,
      { routerEntries: [`/clubs/${CLUB_ID}/skladchina/new?flow=voluntary`] },
    );

    expect(await screen.findByText(/Сбор появится в чате «Тест Clubs»/)).toBeInTheDocument();
    // В шторке своя «Создать сбор» — последняя из одноимённых (первая — кнопка формы).
    const sheetButtons = screen.getAllByRole('button', { name: 'Создать сбор' });
    await user.click(sheetButtons[sheetButtons.length - 1]!);
    expect(screen.queryByText(/Сбор появится в чате/)).not.toBeInTheDocument();
    expect(posts).toBe(0);

    await user.type(screen.getByLabelText(/Название/), 'Подарок');
    await user.type(screen.getByPlaceholderText('Ссылка СБП или номер телефона'), 'https://pay.example/x');
    await user.click(screen.getByRole('button', { name: 'Создать сбор' }));
    expect(await screen.findByTestId('detail')).toBeInTheDocument();
    expect(posts).toBe(1);
  });
});

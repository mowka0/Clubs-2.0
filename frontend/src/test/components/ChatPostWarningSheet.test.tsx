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

/** Минимальная форма: так же, как EventForm и форма сбора, зовёт warn(create) или create сразу. */
const Harness: FC<{ kind: 'event' | 'skladchina'; onCreate: () => void }> = ({ kind, onCreate }) => {
  const { shouldWarn, warn, warningSheet } = useChatPostWarning(CLUB_ID, kind);
  return (
    <>
      <div>{shouldWarn ? 'предупредит' : 'не предупредит'}</div>
      <button type="button" onClick={() => (shouldWarn ? warn(onCreate) : onCreate())}>создать</button>
      {warningSheet}
    </>
  );
};

function renderHarness(kind: 'event' | 'skladchina', onCreate = vi.fn()) {
  const user = userEvent.setup();
  renderWithProviders(
    <Routes>
      <Route path="/form" element={<Harness kind={kind} onCreate={onCreate} />} />
      <Route path="/clubs/:id/manage" element={<SettingsProbe />} />
    </Routes>,
    { routerEntries: ['/form'] },
  );
  return { user, onCreate };
}

describe('useChatPostWarning — встреча и сбор до показа клуба в чате (PO 2026-10-10)', () => {
  beforeEach(() => setViewer(OWNER_ID));

  it('встреча при включённом живом закрепе: шторка, «Создать встречу» создаёт', async () => {
    mockChat();
    const { user, onCreate } = renderHarness('event');
    expect(await screen.findByText('предупредит')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'создать' }));
    expect(screen.getByText(/Встреча появится в чате «Тест Clubs»/)).toBeInTheDocument();
    expect(onCreate).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: 'Создать встречу' }));
    expect(onCreate).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(/Встреча появится в чате/)).not.toBeInTheDocument();

    // Создание сорвалось (оплата, ошибка) — повторное «Создать» не спрашивает второй раз.
    await user.click(screen.getByRole('button', { name: 'создать' }));
    expect(onCreate).toHaveBeenCalledTimes(2);
    expect(screen.queryByText(/Встреча появится в чате/)).not.toBeInTheDocument();
  });

  it('«Настройки чата» ведёт в таб «Чат» управления и ничего не создаёт', async () => {
    mockChat();
    const { user, onCreate } = renderHarness('event');
    expect(await screen.findByText('предупредит')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'создать' }));
    await user.click(screen.getByRole('button', { name: 'Настройки чата' }));

    expect(await screen.findByText(`at:/clubs/${CLUB_ID}/manage?tab=chat`)).toBeInTheDocument();
    expect(onCreate).not.toHaveBeenCalled();
  });

  it('живой закреп выключен — встреча в чат не уйдёт, предупреждать не о чем', async () => {
    mockChat({ livePinEnabled: false });
    renderHarness('event');
    // «Не предупредит» видно и до ответа — поэтому сначала ждём сам ответ.
    await waitForChatStatus();
    expect(screen.getByText('не предупредит')).toBeInTheDocument();
  });

  it('клуб уже показан в чате — без шторки', async () => {
    mockChat({ clubLinkPinned: true });
    renderHarness('skladchina');
    await waitForChatStatus();
    expect(screen.getByText('не предупредит')).toBeInTheDocument();
  });

  it('сбор смотрит на свой тумблер — «Статус сборов в чате»', async () => {
    mockChat({ livePinEnabled: false, skladchinaStatusEnabled: true });
    renderHarness('skladchina');
    expect(await screen.findByText('предупредит')).toBeInTheDocument();
  });

  it('не владельцу не показывается: статус чата ему недоступен', async () => {
    setViewer('someone-else');
    let clubRequested = false;
    mockChat();
    server.use(http.get('*/api/clubs/:id', ({ params }) => {
      clubRequested = true;
      return HttpResponse.json({ ...mockClubDetail, id: params.id as string, ownerId: OWNER_ID, chatLinked: true } as ClubDetailDto);
    }));
    renderHarness('event');
    // Клуб приехал, а статус чата так и не спрашивали: он владельческий.
    await waitFor(() => expect(clubRequested).toBe(true));
    await new Promise((r) => setTimeout(r, 0));
    expect(chatLinkRequests).toBe(0);
    expect(screen.getByText('не предупредит')).toBeInTheDocument();
  });
});

describe('CreateSkladchinaPage — предупреждение перед первым сообщением бота', () => {
  const MEMBER: MemberListItemDto = {
    userId: 'u-1', firstName: 'Анна', lastName: null, avatarUrl: null, role: 'member', joinedAt: null,
    trust: null, promiseFulfillmentPct: null, totalConfirmations: null, awards: [],
    accessStatus: 'active', subscriptionExpiresAt: null,
  };

  beforeEach(() => setViewer(OWNER_ID));

  it('«Создать сбор» сначала показывает шторку, создание — только после второй кнопки', async () => {
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
    await user.type(await screen.findByLabelText(/Название/), 'Подарок');
    await user.type(screen.getByPlaceholderText('Ссылка СБП или номер телефона'), 'https://pay.example/x');
    // Статус чата должен успеть приехать — иначе форма создала бы сбор без вопроса.
    await waitForChatStatus();

    await user.click(screen.getByRole('button', { name: 'Создать сбор' }));
    expect(await screen.findByText(/Сбор появится в чате «Тест Clubs»/)).toBeInTheDocument();
    expect(posts).toBe(0);

    // В шторке своя кнопка «Создать сбор» — последняя из одноимённых.
    const buttons = screen.getAllByRole('button', { name: 'Создать сбор' });
    await user.click(buttons[buttons.length - 1]!);
    expect(await screen.findByTestId('detail')).toBeInTheDocument();
    expect(posts).toBe(1);
  });
});

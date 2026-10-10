import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { FC } from 'react';
import { Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { mockClubDetail } from '../mocks/handlers';
import { QueryClient } from '@tanstack/react-query';
import { renderWithProviders } from '../utils/renderWithProviders';
import { queryKeys } from '../../queries/queryKeys';
import type { ClubDetailDto, MembershipDto } from '../../types/api';

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

import { ClubPage } from '../../pages/ClubPage';
import { useAuthStore } from '../../store/useAuthStore';
import { resetPostponedClubCreatedSheetsForTests } from '../../components/club/ClubCreatedSheet';

const OWNER_ID = 'owner-1';
const CLUB_ID = 'club-123';
const OTHER_CLUB_ID = 'club-999';
const BANNER_TITLE = 'Подключи чат клуба';
/** Текст свёрнутой строки — в неё панель складывается по «Позже в настройках». */
const COLLAPSED_ROW = 'Чат клуба не подключён';
/**
 * Ключ сворачивания — контракт со спекой club-chat-link (§ «Скрытие "Позже в настройках"»).
 * Строка ключа осталась от прежнего смысла «скрыл насовсем» намеренно: у тех, кто уже
 * нажимал «Позже», панель должна показаться свёрнутой строкой, а не вернуться целиком.
 */
const collapseKey = (clubId: string) => `clubs:chat-banner-dismissed:${clubId}`;

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

/** Заглушки данных табов — сама панель их не использует, но страница их запрашивает. */
function mockTabData() {
  server.use(
    http.get('*/api/clubs/:id/activities', () => HttpResponse.json({ upcoming: [], past: [] })),
    http.get('*/api/clubs/:id/events', () => HttpResponse.json({
      content: [], totalElements: 0, totalPages: 0, page: 0, size: 100,
    })),
    http.get('*/api/clubs/:id/members', () => HttpResponse.json([])),
    http.get('*/api/users/me/applications', () => HttpResponse.json([])),
  );
}

function mockClub(over: Partial<ClubDetailDto> = {}, memberships: MembershipDto[] = []) {
  server.use(
    http.get('*/api/clubs/:id', ({ params }) => HttpResponse.json({
      ...mockClubDetail,
      id: params.id as string,
      ownerId: OWNER_ID,
      chatLinked: false,
      ...over,
    } as ClubDetailDto)),
    http.get('*/api/users/me/clubs', () => HttpResponse.json(memberships)),
  );
  mockTabData();
}

function membership(over: Partial<MembershipDto> = {}): MembershipDto {
  return {
    id: 'mem-1',
    userId: 'user-1',
    clubId: CLUB_ID,
    status: 'active',
    role: 'member',
    joinedAt: '2025-01-01T00:00:00Z',
    subscriptionExpiresAt: null,
    ...over,
  } as MembershipDto;
}

/** Проба назначения навигации: показывает путь вместе с query, чтобы проверить `?tab=chat`. */
const ManageProbe = () => {
  const location = useLocation();
  return <div>{`manage:${location.pathname}${location.search}`}</div>;
};

/**
 * Кнопка перехода на другой клуб внутри того же рендера: маршрут `/clubs/:id`
 * остаётся тем же, меняется только параметр — ClubPage НЕ перемонтируется.
 * Именно этот сценарий проверяет механизм key={club.id}, а не скоуп ключа хранилища.
 */
const NavToOtherClub: FC = () => {
  const navigate = useNavigate();
  return (
    <button type="button" onClick={() => navigate(`/clubs/${OTHER_CLUB_ID}`)}>
      к другому клубу
    </button>
  );
};

function renderClubPage(clubId: string = CLUB_ID, queryClient?: QueryClient, search = '') {
  const user = userEvent.setup();
  const result = renderWithProviders(
    <>
      <Routes>
        <Route path="/clubs/:id" element={<ClubPage />} />
        <Route path="/clubs/:id/manage" element={<ManageProbe />} />
        <Route path="/clubs/:id/setup" element={<div>мастер наполнения</div>} />
      </Routes>
      <NavToOtherClub />
    </>,
    { routerEntries: [`/clubs/${clubId}${search}`], queryClient },
  );
  return { ...result, user };
}

function setViewer(id: string) {
  useAuthStore.setState({
    user: {
      id,
      telegramId: 1,
      telegramUsername: 'viewer',
      firstName: 'Viewer',
      lastName: null,
      avatarUrl: null,
      city: null,
      country: null,
      cityId: null,
      bio: null,
      onboardingTours: ['INTRO', 'WELCOME'],
    },
    isAuthenticated: true,
    isLoading: false,
    error: null,
  });
}

describe('ClubPage · панель подключения чата', () => {
  beforeEach(() => {
    localStorage.clear();
    setViewer(OWNER_ID);
    server.resetHandlers();
  });

  it('владелец без привязанного чата видит панель с буллетами и обеими кнопками', async () => {
    mockClub();

    renderClubPage();

    expect(await screen.findByText(BANNER_TITLE)).toBeInTheDocument();
    expect(screen.getByText(/полная синхронизация с клубом/i)).toBeInTheDocument();
    expect(screen.getByText(/умное голосование/i)).toBeInTheDocument();
    expect(screen.getByText(/групповыми сборами/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^подключить$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /позже в настройках/i })).toBeInTheDocument();
  });

  it('«Подключить» ведёт в управление с открытым табом «Чат»', async () => {
    mockClub();

    const { user } = renderClubPage();

    await user.click(await screen.findByRole('button', { name: /^подключить$/i }));

    await waitFor(() => {
      expect(screen.getByText(`manage:/clubs/${CLUB_ID}/manage?tab=chat`)).toBeInTheDocument();
    });
  });

  it('со-организатор панель не видит (привязка чата — владельческая)', async () => {
    setViewer('co-org-1');
    mockClub({}, [membership({ userId: 'co-org-1', role: 'organizer' })]);

    renderClubPage();

    // Дожидаемся отрисовки страницы, только потом утверждаем отсутствие панели.
    expect(await screen.findByText(mockClubDetail.name)).toBeInTheDocument();
    expect(screen.queryByText(BANNER_TITLE)).not.toBeInTheDocument();
  });

  it('участник панель не видит', async () => {
    setViewer('user-1');
    mockClub({}, [membership()]);

    renderClubPage();

    expect(await screen.findByText(mockClubDetail.name)).toBeInTheDocument();
    expect(screen.queryByText(BANNER_TITLE)).not.toBeInTheDocument();
  });

  it('гость панель не видит', async () => {
    setViewer('stranger-1');
    mockClub();

    renderClubPage();

    expect(await screen.findByText(mockClubDetail.name)).toBeInTheDocument();
    expect(screen.queryByText(BANNER_TITLE)).not.toBeInTheDocument();
  });

  it('чат уже привязан — панели нет', async () => {
    mockClub({ chatLinked: true });

    renderClubPage();

    expect(await screen.findByText(mockClubDetail.name)).toBeInTheDocument();
    expect(screen.queryByText(BANNER_TITLE)).not.toBeInTheDocument();
  });

  it('«Позже в настройках» сворачивает панель в строку и пишет отметку в localStorage', async () => {
    mockClub();

    const { user } = renderClubPage();

    await user.click(await screen.findByRole('button', { name: /позже в настройках/i }));

    await waitFor(() => {
      expect(screen.queryByText(BANNER_TITLE)).not.toBeInTheDocument();
    });
    // Дверь к подключению чата остаётся на экране — раньше «Позже» закрывало её насовсем.
    expect(screen.getByText(COLLAPSED_ROW)).toBeInTheDocument();
    expect(localStorage.getItem(collapseKey(CLUB_ID))).not.toBeNull();
  });

  it('тап по свёрнутой строке разворачивает панель обратно', async () => {
    localStorage.setItem(collapseKey(CLUB_ID), '1');
    mockClub();

    const { user } = renderClubPage();

    await user.click(await screen.findByRole('button', { name: new RegExp(COLLAPSED_ROW, 'i') }));

    expect(await screen.findByText(BANNER_TITLE)).toBeInTheDocument();
    // Отметку разворот не стирает: человек сказал «потом», и следующий заход снова спокойный.
    expect(localStorage.getItem(collapseKey(CLUB_ID))).not.toBeNull();
  });

  it('при уже стоящей отметке показывается свёрнутая строка, а не полная панель', async () => {
    localStorage.setItem(collapseKey(CLUB_ID), '1');
    mockClub();

    renderClubPage();

    expect(await screen.findByText(mockClubDetail.name)).toBeInTheDocument();
    expect(screen.queryByText(BANNER_TITLE)).not.toBeInTheDocument();
    expect(screen.getByText(COLLAPSED_ROW)).toBeInTheDocument();
  });

  it('отметка одного клуба не сворачивает панель в другом', async () => {
    localStorage.setItem(collapseKey(CLUB_ID), '1');
    mockClub();

    renderClubPage(OTHER_CLUB_ID);

    expect(await screen.findByText(BANNER_TITLE)).toBeInTheDocument();
  });

  it('сворачивание не протекает на другой клуб в пределах одного монтирования страницы', async () => {
    mockClub();
    // Данные обоих клубов заранее в кэше: иначе переход на клуб B уводит страницу
    // в спиннер (ранний выход по isPending), панель размонтируется сама и тест
    // прошёл бы вхолостую, не проверив механизм key={club.id}.
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: Infinity, staleTime: Infinity } },
    });
    for (const clubId of [CLUB_ID, OTHER_CLUB_ID]) {
      queryClient.setQueryData(queryKeys.clubs.detail(clubId), {
        ...mockClubDetail, id: clubId, ownerId: OWNER_ID, chatLinked: false,
      } as ClubDetailDto);
    }

    const { user } = renderClubPage(CLUB_ID, queryClient);

    await user.click(await screen.findByRole('button', { name: /позже в настройках/i }));
    await waitFor(() => {
      expect(screen.queryByText(BANNER_TITLE)).not.toBeInTheDocument();
    });

    // ClubPage остаётся смонтированной — меняется только :id в маршруте.
    await user.click(screen.getByRole('button', { name: /к другому клубу/i }));

    expect(await screen.findByText(BANNER_TITLE)).toBeInTheDocument();
  });

  it('должник (frozen) панель не видит', async () => {
    setViewer('debtor-1');
    mockClub({ subscriptionPrice: 500 }, [membership({ userId: 'debtor-1', status: 'frozen' })]);

    renderClubPage();

    expect(await screen.findByText(mockClubDetail.name)).toBeInTheDocument();
    expect(screen.queryByText(BANNER_TITLE)).not.toBeInTheDocument();
  });

  it('localStorage недоступен на чтение — панель всё равно показана', async () => {
    // Часть клиентов Telegram (приватный режим, веб-вью без storage) бросает на доступе.
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage denied');
    });
    mockClub();

    renderClubPage();

    expect(await screen.findByText(BANNER_TITLE)).toBeInTheDocument();
    getItem.mockRestore();
  });

  it('localStorage недоступен на запись — «Позже» сворачивает панель без падения рендера', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage denied');
    });
    mockClub();

    const { user } = renderClubPage();

    await user.click(await screen.findByRole('button', { name: /позже в настройках/i }));

    // Запись провалилась молча, но скрытие живёт в состоянии компонента.
    await waitFor(() => {
      expect(screen.queryByText(BANNER_TITLE)).not.toBeInTheDocument();
    });
    expect(setItem).toHaveBeenCalled();
    setItem.mockRestore();
  });
});

describe('ClubPage · баннер «Клуб ещё не заполнен»', () => {
  it('держится, пока мастер не пройден, даже когда город уже выбран', async () => {
    // Раньше признаком служило отсутствие города, и баннер пропадал после второго шага —
    // описание с обложкой доделать было негде (баг PO 2026-08-19).
    mockClub({ setupCompleted: false, cityId: 'city-1', city: 'Москва' });
    renderClubPage();

    expect(await screen.findByText('Клуб ещё не заполнен')).toBeInTheDocument();
  });

  it('исчезает, когда мастер пройден', async () => {
    mockClub({ setupCompleted: true, cityId: null });
    renderClubPage();

    expect(await screen.findByText(mockClubDetail.name)).toBeInTheDocument();
    expect(screen.queryByText('Клуб ещё не заполнен')).not.toBeInTheDocument();
  });
});

describe('ClubPage · чек-лист «Клуб создан» владельца клуба из чата', () => {
  const SHEET_TITLE = `Клуб «${mockClubDetail.name}» создан`;
  /** Превью CLUB_OWNER «Твой клуб» — после чек-листа, не раньше (PO 2026-10-10). */
  const OWNER_PREVIEW_TITLE = 'Твой клуб';

  function mockChatLink(clubLinkPinned: boolean) {
    server.use(http.get('*/api/clubs/:id/chat-link', () => HttpResponse.json({ linked: true, clubLinkPinned })));
  }

  /** Шаг чек-листа по началу его текста: пройденный помечен классом is-done (зачёркнут). */
  function step(label: string): HTMLElement {
    return screen.getByText(label).closest('li') as HTMLElement;
  }

  beforeEach(() => {
    localStorage.clear();
    server.resetHandlers();
    resetPostponedClubCreatedSheetsForTests();
    setViewer(OWNER_ID);
    mockChatLink(false);
  });

  it('встречает владельца без всякого параметра в адресе; «Заполнить клуб» ведёт в мастер', async () => {
    mockClub({ chatLinked: true, setupCompleted: false });
    const { user } = renderClubPage();

    const title = await screen.findByText(SHEET_TITLE);
    expect(screen.getByText(/участникам пока ничего не написал/)).toBeInTheDocument();
    expect(step('Наполни клуб:')).not.toHaveClass('is-done');
    expect(step('Покажи клуб в чате:')).not.toHaveClass('is-done');
    // Кнопка с тем же текстом есть и в баннере «Клуб ещё не заполнен» — берём из поздравления.
    const scene = title.parentElement as HTMLElement;
    await user.click(within(scene).getByRole('button', { name: 'Заполнить клуб' }));
    expect(await screen.findByText('мастер наполнения')).toBeInTheDocument();
  });

  it('клуб наполнен — первый шаг зачёркнут, главная кнопка «Показать клуб в чате»', async () => {
    mockClub({ chatLinked: true, setupCompleted: true });
    renderClubPage();

    expect(await screen.findByRole('button', { name: 'Показать клуб в чате' })).toBeInTheDocument();
    expect(step('Наполни клуб:')).toHaveClass('is-done');
    expect(step('Покажи клуб в чате:')).not.toHaveClass('is-done');
  });

  it('клуб закреплён в чате раньше, чем наполнен, — второй шаг зачёркнут, ведёт в мастер', async () => {
    mockClub({ chatLinked: true, setupCompleted: false });
    mockChatLink(true);
    renderClubPage();

    await screen.findByText(SHEET_TITLE);
    expect(screen.getByText(/Клуб уже закреплён в чате/)).toBeInTheDocument();
    expect(step('Наполни клуб:')).not.toHaveClass('is-done');
    expect(step('Покажи клуб в чате:')).toHaveClass('is-done');
    expect(screen.queryByRole('button', { name: 'Показать клуб в чате' })).not.toBeInTheDocument();
  });

  it('оба шага пройдены — шторки нет, вместо неё превью «Твой клуб»', async () => {
    mockClub({ chatLinked: true, setupCompleted: true });
    mockChatLink(true);
    renderClubPage();

    expect(await screen.findByText(OWNER_PREVIEW_TITLE)).toBeInTheDocument();
    expect(screen.queryByText(SHEET_TITLE)).not.toBeInTheDocument();
  });

  it('пока шаги не пройдены, превью «Твой клуб» не всплывает — и после «Позже» тоже', async () => {
    mockClub({ chatLinked: true, setupCompleted: false });
    const { user } = renderClubPage();

    await user.click(await screen.findByRole('button', { name: 'Позже' }));
    await waitFor(() => expect(screen.queryByText(SHEET_TITLE)).not.toBeInTheDocument());
    // Превью поднимается с задержкой ~0,4 с — ждём дольше, чтобы его отсутствие что-то значило.
    await new Promise((r) => setTimeout(r, 600));
    expect(screen.queryByText(OWNER_PREVIEW_TITLE)).not.toBeInTheDocument();
  });

  it('«Позже» откладывает шторку до перезапуска приложения: новый заход в клуб её не показывает', async () => {
    mockClub({ chatLinked: true, setupCompleted: false });
    const first = renderClubPage();
    await first.user.click(await screen.findByRole('button', { name: 'Позже' }));
    first.unmount();

    renderClubPage();
    expect(await screen.findByText(mockClubDetail.name)).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByText(SHEET_TITLE)).not.toBeInTheDocument();
  });

  it('приход по оплате за клуб (?billing=1) — шторка не ложится поверх шита оплаты', async () => {
    mockClub({ chatLinked: true, setupCompleted: false });
    renderClubPage(CLUB_ID, undefined, '?billing=1');

    expect(await screen.findByText(mockClubDetail.name)).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByText(SHEET_TITLE)).not.toBeInTheDocument();
  });

  it('не владельцу шторка не показывается', async () => {
    setViewer('someone-else');
    mockClub({ chatLinked: true, setupCompleted: false }, [membership({ userId: 'someone-else' })]);
    renderClubPage();

    expect(await screen.findByText(mockClubDetail.name)).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByText(SHEET_TITLE)).not.toBeInTheDocument();
  });
});

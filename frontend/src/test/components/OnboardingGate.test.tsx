import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
  // Layout монтирует Telegram BackButton — вне Telegram он недоступен, но экспорты нужны.
  mountBackButton: Object.assign(vi.fn(), { isAvailable: () => false }),
  unmountBackButton: Object.assign(vi.fn(), { isAvailable: () => false }),
  showBackButton: Object.assign(vi.fn(), { isAvailable: () => false }),
  hideBackButton: Object.assign(vi.fn(), { isAvailable: () => false }),
  onBackButtonClick: Object.assign(vi.fn(() => vi.fn()), { isAvailable: () => false }),
}));

vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));

// Deep-link параметр запуска — управляем им напрямую: от него зависит, показывается ли интро.
const getStartParamMock = vi.fn<[], string | null>();
vi.mock('../../telegram/sdk', () => ({
  getStartParam: () => getStartParamMock(),
}));

// Док не участвует в гейте, но монтируется вместе с приложением — глушим его запросы.
vi.mock('../../queries/organizerClubs', () => ({
  useOrganizerClubs: () => ({ clubs: [], isLoading: false }),
}));
vi.mock('../../components/manage/CreateActivityFlow', () => ({
  CreateActivityFlow: () => null,
}));

import { Layout } from '../../components/Layout';
import { useAuthStore } from '../../store/useAuthStore';
import { server } from '../mocks/server';
import type { UserDto } from '../../types/api';

const makeUser = (onboardingTours: UserDto['onboardingTours']): UserDto => ({
  id: 'user-1',
  telegramId: 1,
  telegramUsername: null,
  firstName: 'Аня',
  lastName: null,
  avatarUrl: null,
  city: null,
  country: null,
  cityId: null,
  bio: null,
  onboardingTours,
});

/** Куда нас привела кнопка интро — читаем прямо из роутера. */
const LandingProbe = ({ name }: { name: string }) => <div>{`ПРИЗЕМЛИЛИСЬ: ${name}`}</div>;

function renderLayout() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<LandingProbe name="каталог" />} />
            <Route path="/profile" element={<LandingProbe name="профиль" />} />
            <Route path="/invite/:code" element={<div>СТРАНИЦА ПРИГЛАШЕНИЯ</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const intro = () => screen.queryByText(/Знакомства, встречи, активности/i);
const appContent = () => screen.queryByText(/ПРИЗЕМЛИЛИСЬ: каталог/);

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  getStartParamMock.mockReset();
  getStartParamMock.mockReturnValue(null);
  useAuthStore.setState({ user: null, isAuthenticated: false, isLoading: false, error: null });
});

// Гейт, а не роут: основание показать интро — пустой список туров. Производные признаки
// («нет клубов», «пустой профиль») запрещены — на них мы уже обжигались (F5-20):
// «данные не пришли» ≠ «данных нет», и онбординг вылезал поверх обжитого аккаунта.
describe('Онбординг — кому показываем интро', () => {
  it('показывает интро вместо приложения тому, кто не прошёл ни одного тура', () => {
    useAuthStore.setState({ user: makeUser([]), isAuthenticated: true });
    renderLayout();

    expect(intro()).toBeInTheDocument();
    expect(appContent()).toBeNull();
  });

  it('не показывает интро тому, кто его уже прошёл', () => {
    useAuthStore.setState({ user: makeUser(['INTRO']), isAuthenticated: true });
    renderLayout();

    expect(intro()).toBeNull();
    expect(appContent()).toBeInTheDocument();
  });

  it('не показывает интро тому, кто закрыл ЛЮБОЙ другой тур: пришедший по приглашению', () => {
    // Ключевое следствие модели (PO 2026-07-31): приглашённый начинает со страницы клуба,
    // получает тезисы через подсказки экранов — и три слайда ему не показываются уже никогда.
    useAuthStore.setState({ user: makeUser(['WELCOME']), isAuthenticated: true });
    renderLayout();

    expect(intro()).toBeNull();
    expect(appContent()).toBeInTheDocument();
  });

  it('не показывает интро, если человек пришёл по deep-link (инвайт): он шёл в клуб', async () => {
    getStartParamMock.mockReturnValue('invite_a1b2c3d4e5f60718');
    useAuthStore.setState({ user: makeUser([]), isAuthenticated: true });
    renderLayout();

    // Интро нет, и DeepLinkHandler свободно уводит на страницу приглашения.
    expect(intro()).toBeNull();
    await waitFor(() =>
      expect(screen.getByText('СТРАНИЦА ПРИГЛАШЕНИЯ')).toBeInTheDocument(),
    );
    expect(useAuthStore.getState().user?.onboardingTours).toEqual([]);
  });

  it('пока профиль не доехал — спиннер, а не интро (регресс F5-20)', () => {
    // isAuthenticated уже true, но user ещё null: «данные не пришли» ≠ «ничего не пройдено».
    useAuthStore.setState({ user: null, isAuthenticated: true });
    renderLayout();

    expect(intro()).toBeNull();
    expect(appContent()).toBeNull();
  });

  it('до авторизации не показывает ни интро, ни приложение', () => {
    useAuthStore.setState({ user: null, isAuthenticated: false });
    renderLayout();

    expect(intro()).toBeNull();
    expect(appContent()).toBeNull();
  });
});

// Регресс (найден PO на staging 2026-07-13): кнопка выхода из интро не уводила никуда.
// Причина — порядок шагов: `setUser` открывал гейт и размонтировал интро ДО того, как
// TanStack успевал вызвать колбэки `mutate(...)`, а у наблюдателя без слушателей он их
// не вызывает вовсе. Навигация терялась целиком.
//
// Ловится только через ГЕЙТ: интро, отрендеренное напрямую, не размонтируется и потому здорово.
describe('Онбординг — выход из интро ведёт в профиль', () => {
  beforeEach(() => {
    server.use(
      http.post('*/api/users/me/onboarding/INTRO', () => HttpResponse.json(makeUser(['INTRO']))),
    );
  });

  it('«Погнали!» на последнем слайде уводит в профиль и гасит интро', async () => {
    const user = userEvent.setup();
    useAuthStore.setState({ user: makeUser([]), isAuthenticated: true });
    renderLayout();

    await user.click(screen.getByRole('button', { name: 'Дальше' }));
    await user.click(screen.getByRole('button', { name: 'Дальше' }));
    await user.click(screen.getByRole('button', { name: 'Погнали!' }));

    await waitFor(() =>
      expect(screen.getByText('ПРИЗЕМЛИЛИСЬ: профиль')).toBeInTheDocument(),
    );
    expect(intro()).toBeNull();
  });
});

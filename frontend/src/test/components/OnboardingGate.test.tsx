import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

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

// Deep-link параметр запуска — управляем им напрямую: от него зависит, откладывается ли карусель.
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
import type { UserDto } from '../../types/api';

const makeUser = (onboardedAt: string | null): UserDto => ({
  id: 'user-1',
  telegramId: 1,
  telegramUsername: null,
  firstName: 'Аня',
  lastName: null,
  avatarUrl: null,
  city: null,
  country: null,
  bio: null,
  onboardedAt,
});

function renderLayout() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<div>СОДЕРЖИМОЕ ПРИЛОЖЕНИЯ</div>} />
            <Route path="/invite/:code" element={<div>СТРАНИЦА ПРИГЛАШЕНИЯ</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const carousel = () => screen.queryByText(/Найди своих для/i);
const appContent = () => screen.queryByText('СОДЕРЖИМОЕ ПРИЛОЖЕНИЯ');

beforeEach(() => {
  getStartParamMock.mockReset();
  getStartParamMock.mockReturnValue(null);
  useAuthStore.setState({ user: null, isAuthenticated: false, isLoading: false, error: null });
});

// Гейт, а не роут: единственное основание показать карусель — явный факт onboardedAt = null.
// Производные признаки («нет клубов», «пустой профиль») запрещены — на них мы уже обжигались
// (F5-20): «данные не пришли» ≠ «данных нет», и онбординг вылезал поверх обжитого аккаунта.
describe('Онбординг — кому показываем карусель', () => {
  it('показывает карусель вместо приложения новому пользователю (onboardedAt = null)', () => {
    useAuthStore.setState({ user: makeUser(null), isAuthenticated: true });
    renderLayout();

    expect(carousel()).toBeInTheDocument();
    expect(appContent()).toBeNull();
  });

  it('не показывает карусель тому, кто онбординг уже прошёл', () => {
    useAuthStore.setState({ user: makeUser('2026-07-13T10:00:00Z'), isAuthenticated: true });
    renderLayout();

    expect(carousel()).toBeNull();
    expect(appContent()).toBeInTheDocument();
  });

  it('откладывает карусель, если человек пришёл по deep-link (инвайт): он шёл в клуб', async () => {
    getStartParamMock.mockReturnValue('invite_a1b2c3d4e5f60718');
    useAuthStore.setState({ user: makeUser(null), isAuthenticated: true });
    renderLayout();

    // Карусели нет, и DeepLinkHandler свободно уводит на страницу приглашения.
    // onboardedAt остаётся null — карусель покажется при следующем ОБЫЧНОМ запуске.
    expect(carousel()).toBeNull();
    await waitFor(() =>
      expect(screen.getByText('СТРАНИЦА ПРИГЛАШЕНИЯ')).toBeInTheDocument(),
    );
    expect(useAuthStore.getState().user?.onboardedAt).toBeNull();
  });

  it('пока профиль не доехал — спиннер, а не карусель (регресс F5-20)', () => {
    // isAuthenticated уже true, но user ещё null: «данные не пришли» ≠ «онбординг не пройден».
    useAuthStore.setState({ user: null, isAuthenticated: true });
    renderLayout();

    expect(carousel()).toBeNull();
    expect(appContent()).toBeNull();
  });

  it('до авторизации не показывает ни карусель, ни приложение', () => {
    useAuthStore.setState({ user: null, isAuthenticated: false });
    renderLayout();

    expect(carousel()).toBeNull();
    expect(appContent()).toBeNull();
  });
});

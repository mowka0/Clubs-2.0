import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

import { OnboardingFlow } from '../../components/onboarding/OnboardingFlow';
import { useAuthStore } from '../../store/useAuthStore';
import { server } from '../mocks/server';

/** Ширина вьюпорта: в jsdom раскладки нет, а от неё зависит порог свайпа. */
const VIEWPORT_WIDTH = 320;

const LandingProbe = ({ name }: { name: string }) => <div>{`ПРИЗЕМЛИЛИСЬ: ${name}`}</div>;

function renderFlow() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/onboarding']}>
        <Routes>
          <Route path="/onboarding" element={<OnboardingFlow />} />
          <Route path="/profile" element={<LandingProbe name="профиль" />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const introDoneUser = {
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
  onboardingTours: ['INTRO'],
};

/** Слайды опознаём по микро-строкам — единственному тексту под заголовком. */
const slide1 = () => screen.queryByText(/Знакомства, встречи, активности/i);
const slide2 = () => screen.queryByText(/В чате болтаем/i);
const slide3 = () => screen.queryByText(/Подключи чат, разошли инвайты/i);

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'bypass' });
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
    configurable: true,
    value: VIEWPORT_WIDTH,
  });
});
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  useAuthStore.setState({ user: null, isAuthenticated: true, isLoading: false, error: null });
});

describe('Интро — три слайда без стены текста', () => {
  it('листается кнопкой «Дальше», на последнем слайде она становится «Погнали!»', async () => {
    const user = userEvent.setup();
    renderFlow();

    expect(slide1()).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Погнали!' })).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Дальше' }));
    expect(slide2()).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Дальше' }));
    expect(slide3()).toBeInTheDocument();
    // Выбора роли на последнем слайде нет — одна кнопка ведёт всех в профиль.
    expect(screen.getByRole('button', { name: 'Погнали!' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Дальше' })).toBeNull();
  });

  it('на слайде нет списка преимуществ — только заголовок и одна микро-строка', () => {
    const { container } = renderFlow();

    // Ровно та стена текста, из-за которой интро переделывали: четыре абзаца на первом экране.
    expect(container.querySelectorAll('.ob-perk')).toHaveLength(0);
    expect(container.querySelectorAll('.ob-micro')).toHaveLength(3);
  });

  it('листается свайпом: дрожание пальца — нет, уверенный сдвиг — да', () => {
    const { container } = renderFlow();
    const viewport = container.querySelector('.ob-viewport')!;
    const swipe = (fromX: number, toX: number) => {
      fireEvent.touchStart(viewport, { changedTouches: [{ clientX: fromX }] });
      fireEvent.touchEnd(viewport, { changedTouches: [{ clientX: toX }] });
    };

    // Меньше 22% ширины — это дрожание, лента возвращается на место.
    swipe(200, 190);
    expect(slide1()).toBeInTheDocument();

    // Смахнули влево — вперёд.
    swipe(280, 60);
    expect(slide2()).toBeInTheDocument();

    // Смахнули вправо — назад.
    swipe(60, 280);
    expect(slide1()).toBeInTheDocument();
  });

  it('за первым слайдом назад не листает — дальше ничего нет', () => {
    const { container } = renderFlow();
    const viewport = container.querySelector('.ob-viewport')!;

    fireEvent.touchStart(viewport, { changedTouches: [{ clientX: 60 }] });
    fireEvent.touchEnd(viewport, { changedTouches: [{ clientX: 300 }] });

    expect(slide1()).toBeInTheDocument();
  });

  it('«Погнали!» отмечает тур INTRO и уводит в профиль', async () => {
    const user = userEvent.setup();
    let calledPath: string | null = null;
    server.use(
      http.post('*/api/users/me/onboarding/:tour', ({ request }) => {
        calledPath = new URL(request.url).pathname;
        return HttpResponse.json(introDoneUser);
      }),
    );
    renderFlow();

    await user.click(screen.getByRole('button', { name: 'Дальше' }));
    await user.click(screen.getByRole('button', { name: 'Дальше' }));
    await user.click(screen.getByRole('button', { name: 'Погнали!' }));

    await waitFor(() => expect(screen.getByText('ПРИЗЕМЛИЛИСЬ: профиль')).toBeInTheDocument());
    expect(calledPath).toBe('/api/users/me/onboarding/INTRO');
    // Профиль в сторе обновлён — гейт в Layout больше интро не покажет.
    expect(useAuthStore.getState().user?.onboardingTours).toEqual(['INTRO']);
  });

  it('запрос упал — человек остаётся в интро и видит ошибку, а не пустое приложение', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('*/api/users/me/onboarding/:tour', () =>
        HttpResponse.json({ message: 'boom' }, { status: 500 })),
    );
    renderFlow();

    await user.click(screen.getByRole('button', { name: 'Дальше' }));
    await user.click(screen.getByRole('button', { name: 'Дальше' }));
    await user.click(screen.getByRole('button', { name: 'Погнали!' }));

    await waitFor(() => expect(screen.getByText(/Не удалось продолжить/i)).toBeInTheDocument());
    expect(screen.queryByText(/ПРИЗЕМЛИЛИСЬ/)).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
  });
});

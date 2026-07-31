import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

import { CoachTour } from '../../components/onboarding/CoachTour';
import { useAuthStore } from '../../store/useAuthStore';
import { server } from '../mocks/server';
import type { OnboardingTour, UserDto } from '../../types/api';

const makeUser = (onboardingTours: OnboardingTour[]): UserDto => ({
  id: 'user-1',
  telegramId: 1,
  telegramUsername: null,
  firstName: 'Аня',
  lastName: null,
  avatarUrl: null,
  city: null,
  country: null,
  bio: null,
  onboardingTours,
});

/**
 * Реальные цели тура ACTIVITIES: тур короткий (два шага) и оба его якоря — простые кнопки.
 * Рисуем их сами, чтобы тест проверял движок, а не вёрстку страницы «Активности».
 */
function renderTour(tour: OnboardingTour, targets: string[]) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      {targets.map((name) => (
        <div key={name} data-coach={name}>
          {name}
        </div>
      ))}
      <CoachTour tour={tour} />
    </QueryClientProvider>,
  );
}

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'bypass' });
  // jsdom не реализует scrollIntoView, а движок доводит цель до центра экрана.
  Element.prototype.scrollIntoView = vi.fn();
});
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  useAuthStore.setState({ user: null, isAuthenticated: true, isLoading: false, error: null });
});

describe('CoachTour — движок подсказок', () => {
  it('ведёт по шагам: «Далее» → «Понятно», и отмечает тур пройденным', async () => {
    const user = userEvent.setup();
    let calledPath: string | null = null;
    server.use(
      http.post('*/api/users/me/onboarding/:tour', ({ request }) => {
        calledPath = new URL(request.url).pathname;
        return HttpResponse.json(makeUser(['ACTIVITIES']));
      }),
    );
    useAuthStore.setState({ user: makeUser([]) });
    renderTour('ACTIVITIES', ['activities-tab-events', 'activities-tab-skladchina']);

    await waitFor(() => expect(screen.getByText(/встречи клуба вживую/)).toBeInTheDocument());
    // Крестика нет нигде — шаг закрывается только кнопкой подтверждения.
    expect(screen.queryByRole('button', { name: /закрыть/i })).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Далее' }));
    await waitFor(() => expect(screen.getByText(/скидываемся на общие траты/)).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Понятно' }));
    await waitFor(() => expect(calledPath).toBe('/api/users/me/onboarding/ACTIVITIES'));
  });

  it('пройденный тур не показывается вовсе', () => {
    useAuthStore.setState({ user: makeUser(['ACTIVITIES']) });
    renderTour('ACTIVITIES', ['activities-tab-events', 'activities-tab-skladchina']);

    expect(screen.queryByText(/встречи клуба вживую/)).toBeNull();
  });

  it('шаг без цели на экране пропускается, тур не виснет', async () => {
    useAuthStore.setState({ user: makeUser([]) });
    // Первой цели нет — движок обязан дойти до второй, а не замереть на отсутствующей.
    renderTour('ACTIVITIES', ['activities-tab-skladchina']);

    await waitFor(
      () => expect(screen.getByText(/скидываемся на общие траты/)).toBeInTheDocument(),
      { timeout: 4000 },
    );
  });

  it('тур, у которого не нашлось ни одной цели, НЕ засчитывается', async () => {
    let called = false;
    server.use(
      http.post('*/api/users/me/onboarding/:tour', () => {
        called = true;
        return HttpResponse.json(makeUser(['ACTIVITIES']));
      }),
    );
    useAuthStore.setState({ user: makeUser([]) });
    renderTour('ACTIVITIES', []);

    // Иначе экран, где цели просто не успели отрисоваться, молча сжигал бы тур.
    await new Promise((resolve) => setTimeout(resolve, 4200));
    expect(called).toBe(false);
  }, 10_000);

  it('профиль без списка туров подсказок не рисует и страницу не роняет', () => {
    // Fail-closed: компонент висит на каждом экране, белый экран из-за него недопустим.
    useAuthStore.setState({ user: { ...makeUser([]), onboardingTours: undefined as never } });

    expect(() =>
      renderTour('ACTIVITIES', ['activities-tab-events']),
    ).not.toThrow();
    expect(screen.queryByText(/встречи клуба вживую/)).toBeNull();
  });
});

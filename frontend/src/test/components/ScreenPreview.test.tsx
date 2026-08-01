import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

import { ScreenPreview } from '../../components/onboarding/ScreenPreview';
import { SCREEN_PREVIEWS } from '../../components/onboarding/previews';
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
  cityId: null,
  bio: null,
  onboardingTours,
});

/**
 * Сколько «прокручиваем» времени, прежде чем считать, что шторка НЕ появится. Чуть больше
 * задержки подъёма (`RISE_DELAY_MS` = 420 мс). Время фейковое: ждать по-настоящему незачем,
 * запросов в этих сценариях не бывает.
 */
const NO_SHOW_WAIT_MS = 500;

/** Проверка «шторка так и не поднялась» — без реального ожидания. */
async function expectNoSheet(title: string) {
  vi.useFakeTimers();
  try {
    await vi.advanceTimersByTimeAsync(NO_SHOW_WAIT_MS);
  } finally {
    vi.useRealTimers();
  }
  expect(screen.queryByText(title)).toBeNull();
}

function renderPreview(screenKey: OnboardingTour, ready = true) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ScreenPreview screen={screenKey} ready={ready} />
    </QueryClientProvider>,
  );
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  useAuthStore.setState({ user: null, isAuthenticated: true, isLoading: false, error: null });
});

describe('ScreenPreview — превью экрана', () => {
  it('первый заход: поднимается шторка с правилами, «Понятно» отмечает экран показанным', async () => {
    const user = userEvent.setup();
    let calledPath: string | null = null;
    server.use(
      http.post('*/api/users/me/onboarding/:tour', ({ request }) => {
        calledPath = new URL(request.url).pathname;
        return HttpResponse.json(makeUser(['ACTIVITIES']));
      }),
    );
    useAuthStore.setState({ user: makeUser([]) });
    renderPreview('ACTIVITIES');

    const preview = SCREEN_PREVIEWS.ACTIVITIES!;
    await waitFor(() => expect(screen.getByText(preview.title)).toBeInTheDocument());
    expect(screen.getByText(preview.lead)).toBeInTheDocument();
    // Правила игры видны целиком: цепочки шагов больше нет, всё на одном экране.
    preview.rules.forEach((rule) => expect(screen.getByText(rule)).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Понятно' }));
    await waitFor(() => expect(calledPath).toBe('/api/users/me/onboarding/ACTIVITIES'));
    expect(screen.queryByText(preview.title)).toBeNull();
  });

  it('тап мимо шторки тоже засчитывает показ — второй раз она не появится', async () => {
    const user = userEvent.setup();
    let calls = 0;
    server.use(
      http.post('*/api/users/me/onboarding/:tour', () => {
        calls += 1;
        return HttpResponse.json(makeUser(['MY_CLUBS']));
      }),
    );
    useAuthStore.setState({ user: makeUser([]) });
    const { container } = renderPreview('MY_CLUBS');

    await waitFor(() => expect(screen.getByText(SCREEN_PREVIEWS.MY_CLUBS!.title)).toBeInTheDocument());
    await user.click(container.ownerDocument.querySelector('.rd-sheet-overlay')!);

    await waitFor(() => expect(calls).toBe(1));
    expect(screen.queryByText(SCREEN_PREVIEWS.MY_CLUBS!.title)).toBeNull();
  });

  it('протяжка вниз за шапку закрывает шторку и засчитывает показ', async () => {
    let calls = 0;
    server.use(
      http.post('*/api/users/me/onboarding/:tour', () => {
        calls += 1;
        return HttpResponse.json(makeUser(['CLUB_MANAGE']));
      }),
    );
    useAuthStore.setState({ user: makeUser([]) });
    renderPreview('CLUB_MANAGE');
    await waitFor(() => expect(screen.getByText(SCREEN_PREVIEWS.CLUB_MANAGE!.title)).toBeInTheDocument());

    const grip = document.querySelector('.sp-grip')!;
    fireEvent.touchStart(grip, { changedTouches: [{ clientY: 100 }] });
    fireEvent.touchMove(grip, { changedTouches: [{ clientY: 200 }] });
    fireEvent.touchEnd(grip, { changedTouches: [{ clientY: 260 }] });

    await waitFor(() => expect(calls).toBe(1));
    expect(screen.queryByText(SCREEN_PREVIEWS.CLUB_MANAGE!.title)).toBeNull();
  });

  it('короткая протяжка шторку не закрывает — она возвращается на место', async () => {
    let calls = 0;
    server.use(
      http.post('*/api/users/me/onboarding/:tour', () => {
        calls += 1;
        return HttpResponse.json(makeUser(['CLUB_OWNER']));
      }),
    );
    useAuthStore.setState({ user: makeUser([]) });
    renderPreview('CLUB_OWNER');
    await waitFor(() => expect(screen.getByText(SCREEN_PREVIEWS.CLUB_OWNER!.title)).toBeInTheDocument());

    const grip = document.querySelector('.sp-grip')!;
    // 40px — меньше порога в 90px, и медленно: ни дистанции, ни скорости.
    fireEvent.touchStart(grip, { changedTouches: [{ clientY: 100 }] });
    fireEvent.touchMove(grip, { changedTouches: [{ clientY: 120 }] });
    fireEvent.touchEnd(grip, { changedTouches: [{ clientY: 140 }] });

    vi.useFakeTimers();
    try {
      await vi.advanceTimersByTimeAsync(NO_SHOW_WAIT_MS);
    } finally {
      vi.useRealTimers();
    }
    expect(calls).toBe(0);
    expect(screen.getByText(SCREEN_PREVIEWS.CLUB_OWNER!.title)).toBeInTheDocument();
  });

  it('уже показанное превью не поднимается вовсе', async () => {
    useAuthStore.setState({ user: makeUser(['DISCOVERY']) });
    renderPreview('DISCOVERY');

    await expectNoSheet(SCREEN_PREVIEWS.DISCOVERY!.title);
  });

  it('ready=false: ждём страницу, шторка не лезет на пустой экран', async () => {
    useAuthStore.setState({ user: makeUser([]) });
    renderPreview('CLUB', false);

    await expectNoSheet(SCREEN_PREVIEWS.CLUB!.title);
  });

  it('профиль ещё не приехал: молчим, а не показываем превью вслепую', async () => {
    useAuthStore.setState({ user: null });
    renderPreview('PROFILE');

    await expectNoSheet(SCREEN_PREVIEWS.PROFILE!.title);
  });
});

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
function renderTour(tour: OnboardingTour, targets: string[], gateSatisfied = false) {
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
      <CoachTour tour={tour} gateSatisfied={gateSatisfied} />
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

  it('шаг-задание не даёт «Далее», пока условие не выполнено', async () => {
    useAuthStore.setState({ user: makeUser([]) });
    const { unmount } = renderTour('PROFILE', ['profile-quest'], false);

    await waitFor(() => expect(screen.getByText(/визитная карточка/)).toBeInTheDocument());
    // Вместо кнопки — что нужно сделать; иначе тур можно было бы промотать мимо задания.
    expect(screen.getByText('Заполни профиль — и продолжим')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Далее' })).toBeNull();
    // Тапы проходят только в дырку: слой сквозной, но вокруг цели стоят четыре заглушки.
    expect(document.querySelector('.ct-root-open')).not.toBeNull();
    expect(document.querySelectorAll('.ct-block')).toHaveLength(4);
    unmount();
  });

  it('выполненное условие возвращает кнопку на место', async () => {
    useAuthStore.setState({ user: makeUser([]) });
    renderTour('PROFILE', ['profile-quest'], true);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Далее' })).toBeInTheDocument());
    expect(screen.queryByText('Заполни профиль — и продолжим')).toBeNull();
    // Обычный шаг перекрывает экран целиком — отдельные заглушки ему не нужны.
    expect(document.querySelector('.ct-root-open')).toBeNull();
    expect(document.querySelectorAll('.ct-block')).toHaveLength(0);
  });

  it('подсветка едет за целью, когда та меняет размер', async () => {
    useAuthStore.setState({ user: makeUser([]) });
    renderTour('PROFILE', ['profile-quest'], false);
    await waitFor(() => expect(screen.getByText(/визитная карточка/)).toBeInTheDocument());

    // jsdom не считает раскладку, поэтому подменяем бокс цели и дёргаем наблюдателя вручную:
    // проверяем, что перезамер вообще подключён, а не что jsdom умеет верстать.
    const target = document.querySelector('[data-coach="profile-quest"]') as HTMLElement;
    target.getBoundingClientRect = () => ({
      top: 400, left: 20, width: 300, height: 120, right: 320, bottom: 520, x: 20, y: 400, toJSON: () => ({}),
    }) as DOMRect;
    window.dispatchEvent(new Event('resize'));

    await waitFor(() => {
      const bands = [...document.querySelectorAll('.ct-block')] as HTMLElement[];
      // Верхняя полоса кончается там, где начинается дырка: 400 − 6px воздуха.
      expect(bands[0]?.style.height).toBe('394px');
    });
  });

  it('цель исчезла со страницы — шаг переезжает дальше, а не улетает в угол', async () => {
    useAuthStore.setState({ user: makeUser([]) });
    // Ровно сценарий закрытого профиль-квеста: карточка сменяется поздравлением и уходит
    // из DOM. Мерить отсоединённый узел нельзя — он отдаёт нули, дырка схлопывается
    // в (0,0), а пузырь улетает под шапку (баг PO 2026-07-31).
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const harness = (targets: string[]) => (
      <QueryClientProvider client={queryClient}>
        {targets.map((name) => (
          <div key={name} data-coach={name}>
            {name}
          </div>
        ))}
        <CoachTour tour="PROFILE" gateSatisfied />
      </QueryClientProvider>
    );

    const { rerender } = render(harness(['profile-quest', 'profile-level']));
    await waitFor(() => expect(screen.getByText(/визитная карточка/)).toBeInTheDocument());

    // Убираем цель ререндером, а не руками: вручную вырванный узел ломает размонтирование React.
    rerender(harness(['profile-level']));
    window.dispatchEvent(new Event('resize'));

    // Матчим кусок ОДНОГО текстового узла: акцент («XP») обёрнут в <em> и рвёт фразу.
    await waitFor(() => expect(screen.getByText(/Уровень растёт от/)).toBeInTheDocument(), {
      timeout: 4000,
    });
  }, 10_000);

  it('награда закрывается кнопкой внутри неё, а не «Далее»', async () => {
    useAuthStore.setState({ user: makeUser([]) });
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const harness = (targets: string[]) => (
      <QueryClientProvider client={queryClient}>
        {targets.map((name) => (
          <div key={name} data-coach={name}>
            {name}
          </div>
        ))}
        <CoachTour tour="PROFILE" gateSatisfied />
      </QueryClientProvider>
    );

    // Карточки квеста нет — шаг-задание пропускается, тур встаёт на поздравление.
    const { rerender } = render(harness(['profile-congrats', 'profile-level']));
    await waitFor(() => expect(screen.getByText(/первая награда/)).toBeInTheDocument(), {
      timeout: 4000,
    });
    // «Далее» здесь не должно быть НИКОГДА: иначе награду можно проскочить, не забрав.
    expect(screen.queryByRole('button', { name: 'Далее' })).toBeNull();
    expect(screen.getByText('Забери награду')).toBeInTheDocument();

    // Тап по «Отлично!» убирает поздравление со страницы — это и есть ход тура.
    rerender(harness(['profile-level']));
    window.dispatchEvent(new Event('resize'));
    await waitFor(() => expect(screen.getByText(/профиль, встречи, сборы/)).toBeInTheDocument(), {
      timeout: 4000,
    });
  }, 15_000);

  it('нет точной цели — шаг падает на запасную, а не пропадает', async () => {
    useAuthStore.setState({ user: makeUser([]) });
    // Строки надёжности нет (новичок без репутации) — рассказ обязан состояться на панели.
    renderTour('PROFILE', ['profile-quest', 'profile-level', 'profile-stats-panel'], true);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Далее' })).toBeInTheDocument());
    await userEvent.setup().click(screen.getByRole('button', { name: 'Далее' }));
    // Поздравления в этой раскладке нет — шаг награды пропускается сам, тур едет к уровню.
    // Совпадение — по куску ОДНОГО текстового узла: акцент внутри фразы обёрнут в <em>.
    await waitFor(() => expect(screen.getByText(/профиль, встречи, сборы/)).toBeInTheDocument(), {
      timeout: 4000,
    });
    await userEvent.setup().click(screen.getByRole('button', { name: 'Далее' }));
    await waitFor(() => expect(screen.getByText(/главное, что видят организаторы/)).toBeInTheDocument());
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

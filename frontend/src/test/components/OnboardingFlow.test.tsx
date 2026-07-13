import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

import { OnboardingFlow } from '../../components/onboarding/OnboardingFlow';
import { useAuthStore } from '../../store/useAuthStore';
import { server } from '../mocks/server';

/** Куда нас привела дверь и что попросили подсветить — читаем прямо из роутера. */
const LandingProbe = ({ name }: { name: string }) => {
  const location = useLocation();
  const highlight = (location.state as { highlight?: string } | null)?.highlight ?? 'нет';
  return <div>{`ПРИЗЕМЛИЛИСЬ: ${name}, подсветка: ${highlight}`}</div>;
};

function renderFlow() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/onboarding']}>
        <Routes>
          <Route path="/onboarding" element={<OnboardingFlow />} />
          <Route path="/" element={<LandingProbe name="каталог" />} />
          <Route path="/my-clubs" element={<LandingProbe name="мои клубы" />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const onboardedResponse = {
  id: 'user-1',
  telegramId: 1,
  telegramUsername: null,
  firstName: 'Аня',
  lastName: null,
  avatarUrl: null,
  city: null,
  country: null,
  bio: null,
  onboardedAt: '2026-07-13T12:00:00Z',
};

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  useAuthStore.setState({ user: null, isAuthenticated: true, isLoading: false, error: null });
});

describe('OnboardingFlow — карусель из трёх слайдов', () => {
  it('листается кнопками: «Дальше» ведёт к участнику, оттуда — к организатору и обратно', async () => {
    const user = userEvent.setup();
    renderFlow();

    // Слайд 1 — общий: дверей нет, только «Дальше».
    expect(screen.getByText(/Наполни свою жизнь активностями/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Найти клубы в своём городе/i })).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Дальше' }));
    expect(screen.getByRole('button', { name: /Найти клубы в своём городе/i })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Хочу вести свой клуб/i }));
    expect(screen.getByRole('button', { name: /Создать клуб и пригласить друзей/i })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Сначала посмотрю клубы/i }));
    expect(screen.getByRole('button', { name: /Найти клубы в своём городе/i })).toBeInTheDocument();
  });

  it('первый экран называет продукт: «Clubs» вшито в слоган и набрано каллиграфией', () => {
    const { container } = renderFlow();

    const brand = container.querySelector('.ob-brand');
    expect(brand).toHaveTextContent('Clubs');
    // Слоган — цельное предложение, имя стоит внутри него, а не отдельной надписью сверху.
    expect(container.querySelector('.ob-title')).toHaveTextContent(
      'Объединяйтесь в Clubs по интересам, чтобы встречаться вживую!',
    );
  });

  it('листается свайпом: сдвиг больше порога листает, дрожание пальца — нет (AC-2)', () => {
    const { container } = renderFlow();
    const root = container.querySelector('.ob-root')!;
    const swipe = (fromX: number, toX: number) => {
      fireEvent.touchStart(root, { changedTouches: [{ clientX: fromX }] });
      fireEvent.touchEnd(root, { changedTouches: [{ clientX: toX }] });
    };

    // Короткий сдвиг (меньше SWIPE_THRESHOLD_PX = 50) — это не свайп, слайд остаётся.
    swipe(200, 180);
    expect(screen.getByText(/Наполни свою жизнь активностями/i)).toBeInTheDocument();

    // Смахнули влево — вперёд, к слайду участника.
    swipe(200, 100);
    expect(screen.getByRole('button', { name: /Найти клубы в своём городе/i })).toBeInTheDocument();

    // Смахнули вправо — назад, на первый слайд.
    swipe(100, 200);
    expect(screen.getByText(/Наполни свою жизнь активностями/i)).toBeInTheDocument();
  });

  it('листается стрелками по краям (AC-2)', async () => {
    const user = userEvent.setup();
    renderFlow();

    // На первом слайде назад листать некуда — левой стрелки нет.
    expect(screen.queryByRole('button', { name: 'Предыдущий слайд' })).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Следующий слайд' }));
    expect(screen.getByRole('button', { name: /Найти клубы в своём городе/i })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Следующий слайд' }));
    expect(screen.getByRole('button', { name: /Создать клуб и пригласить друзей/i })).toBeInTheDocument();

    // Последний слайд — вперёд листать некуда.
    expect(screen.queryByRole('button', { name: 'Следующий слайд' })).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Предыдущий слайд' }));
    expect(screen.getByRole('button', { name: /Найти клубы в своём городе/i })).toBeInTheDocument();
  });

  it('дверь участника: помечает онбординг пройденным (door=MEMBER) и ведёт в каталог с подсветкой города', async () => {
    const user = userEvent.setup();
    let sentBody: unknown = null;
    server.use(
      http.post('*/api/users/me/onboarding', async ({ request }) => {
        sentBody = await request.json();
        return HttpResponse.json(onboardedResponse);
      }),
    );
    renderFlow();

    await user.click(screen.getByRole('button', { name: 'Дальше' }));
    await user.click(screen.getByRole('button', { name: /Найти клубы в своём городе/i }));

    await waitFor(() =>
      expect(screen.getByText('ПРИЗЕМЛИЛИСЬ: каталог, подсветка: city')).toBeInTheDocument(),
    );
    expect(sentBody).toEqual({ door: 'MEMBER' });
    // Профиль в сторе обновлён — гейт в Layout больше карусель не покажет.
    expect(useAuthStore.getState().user?.onboardedAt).toBe('2026-07-13T12:00:00Z');
  });

  it('дверь организатора: door=ORGANIZER, ведёт в «Мои клубы» с подсветкой создания клуба', async () => {
    const user = userEvent.setup();
    let sentBody: unknown = null;
    server.use(
      http.post('*/api/users/me/onboarding', async ({ request }) => {
        sentBody = await request.json();
        return HttpResponse.json(onboardedResponse);
      }),
    );
    renderFlow();

    await user.click(screen.getByRole('button', { name: 'Дальше' }));
    await user.click(screen.getByRole('button', { name: /Хочу вести свой клуб/i }));
    await user.click(screen.getByRole('button', { name: /Создать клуб и пригласить друзей/i }));

    await waitFor(() =>
      expect(screen.getByText('ПРИЗЕМЛИЛИСЬ: мои клубы, подсветка: create-club')).toBeInTheDocument(),
    );
    expect(sentBody).toEqual({ door: 'ORGANIZER' });
  });

  it('409 «уже пройден» — не отказ: перечитывает профиль и пропускает дальше, а не запирает в карусели', async () => {
    const user = userEvent.setup();
    server.use(
      // Онбординг успели пройти на другом устройстве — профиль в этой сессии устарел.
      http.post('*/api/users/me/onboarding', () =>
        HttpResponse.json({ message: 'Onboarding already completed' }, { status: 409 })),
      http.get('*/api/users/me', () => HttpResponse.json(onboardedResponse)),
    );
    renderFlow();

    await user.click(screen.getByRole('button', { name: 'Дальше' }));
    await user.click(screen.getByRole('button', { name: /Найти клубы в своём городе/i }));

    await waitFor(() =>
      expect(screen.getByText('ПРИЗЕМЛИЛИСЬ: каталог, подсветка: city')).toBeInTheDocument(),
    );
    expect(useAuthStore.getState().user?.onboardedAt).toBe('2026-07-13T12:00:00Z');
    expect(screen.queryByText(/Не удалось продолжить/i)).toBeNull();
  });

  it('запрос упал — человек остаётся в карусели и видит ошибку, а не пустое приложение', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('*/api/users/me/onboarding', () =>
        HttpResponse.json({ message: 'boom' }, { status: 500 })),
    );
    renderFlow();

    await user.click(screen.getByRole('button', { name: 'Дальше' }));
    await user.click(screen.getByRole('button', { name: /Найти клубы в своём городе/i }));

    await waitFor(() => expect(screen.getByText(/Не удалось продолжить/i)).toBeInTheDocument());
    expect(screen.queryByText(/ПРИЗЕМЛИЛИСЬ/)).toBeNull();
    expect(useAuthStore.getState().user?.onboardedAt).toBeUndefined();
  });
});

import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { GamificationDto, ProfileQuestDto } from '../../types/api';

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

import { ProfilePage } from '../../pages/ProfilePage';
import { useAuthStore } from '../../store/useAuthStore';

const EMPTY_REPUTATION = {
  global: { reliableClubs: 0, trackRecordClubs: 0, score: null },
  activeClubs: [],
  historyClubs: [],
};

function gamificationWith(quest: ProfileQuestDto, xp = 0): GamificationDto {
  return {
    xp,
    level: xp >= 50 ? 2 : 1,
    levelName: xp >= 50 ? 'Свой' : 'Гость',
    nextLevelName: xp >= 50 ? 'Участник' : 'Свой',
    xpIntoLevel: xp >= 50 ? xp - 50 : xp,
    xpSpanToNext: xp >= 50 ? 150 : 50,
    badges: quest.completed ? [{ id: 'profile_card', name: 'Визитка', family: 'PROFILE' }] : [],
    quest,
  };
}

function mockEndpoints(gam: GamificationDto, interests: string[] = []) {
  server.use(
    http.get('*/api/users/me/reputation', () => HttpResponse.json(EMPTY_REPUTATION)),
    http.get('*/api/users/me/interests', () => HttpResponse.json(interests)),
    http.get('*/api/users/me/gamification', () => HttpResponse.json(gam)),
  );
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/profile" element={<ProfilePage />} />
    </Routes>,
    { routerEntries: ['/profile'] },
  );
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  localStorage.clear();
  useAuthStore.setState({
    user: {
      id: 'viewer-1', telegramId: 1, telegramUsername: 'v', firstName: 'V', lastName: null,
      avatarUrl: null, city: null, country: null, bio: null,
    },
    isAuthenticated: true,
    isLoading: false,
  } as never);
});

describe('ProfilePage — профиль-квест', () => {
  it('квест не завершён: карточка с шагами видна, лис-экрана интересов нет', async () => {
    mockEndpoints(gamificationWith({ cityDone: true, interestsDone: false, bioDone: false, completed: false }, 10));
    renderPage();

    expect(await screen.findByText('Прокачай профиль')).toBeInTheDocument();
    // «10 / 50 XP» есть и в панели «Уровень» — квестовый сабтайтл уточняем по «до уровня 2»
    expect(screen.getByText(/до уровня 2/)).toBeInTheDocument();
    // Лис-экран интересов снят: при пустых интересах нет ни заголовка секции, ни призыва лиса
    expect(screen.queryByText('Расскажи, что тебе интересно')).not.toBeInTheDocument();
    expect(screen.queryByText('Лис ждёт твои интересы')).not.toBeInTheDocument();
  });

  it('AC-3: переход «не завершён → завершён» в сессии — поздравление; «Отлично» убирает', async () => {
    // Хендлер с замыканием: сначала квест не завершён, после «сохранения профиля» — завершён.
    let completed = false;
    server.use(
      http.get('*/api/users/me/reputation', () => HttpResponse.json(EMPTY_REPUTATION)),
      http.get('*/api/users/me/interests', () => HttpResponse.json([])),
      http.get('*/api/users/me/gamification', () =>
        HttpResponse.json(
          completed
            ? gamificationWith({ cityDone: true, interestsDone: true, bioDone: true, completed: true }, 50)
            : gamificationWith({ cityDone: true, interestsDone: false, bioDone: false, completed: false }, 10),
        ),
      ),
    );
    const { queryClient } = renderPage();

    expect(await screen.findByText('Прокачай профиль')).toBeInTheDocument();

    // Эмуляция successful-мутации профиля: сервер теперь отвечает completed, кэш инвалидируется
    completed = true;
    await queryClient.invalidateQueries();

    expect(await screen.findByText('Уровень 2 — «Свой»!')).toBeInTheDocument();
    expect(screen.getByText('Бейдж «Визитка»')).toBeInTheDocument();
    expect(screen.queryByText('Прокачай профиль')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Отлично' }));
    await waitFor(() =>
      expect(screen.queryByText('Уровень 2 — «Свой»!')).not.toBeInTheDocument(),
    );
  });

  it('квест завершён при загрузке (backfill старичка): ни карточки, ни поздравления', async () => {
    mockEndpoints(
      gamificationWith({ cityDone: true, interestsDone: true, bioDone: true, completed: true }, 50),
      ['Настолки'],
    );
    renderPage();

    // Страница загрузилась (панель уровня на месте)
    expect(await screen.findByText('Уровень')).toBeInTheDocument();
    expect(screen.queryByText('Прокачай профиль')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Отлично' })).not.toBeInTheDocument();
    // Бейдж «Визитка» — чипом в панели уровня
    expect(screen.getByText('Визитка')).toBeInTheDocument();
  });
});

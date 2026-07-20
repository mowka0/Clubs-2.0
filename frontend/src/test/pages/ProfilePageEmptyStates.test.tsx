import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { GamificationDto } from '../../types/api';

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

const VIEWER_ID = 'viewer-1';

// Свежий аккаунт без репутации: hasReputation=false, страница проходит верхний спиннер.
const EMPTY_REPUTATION = {
  global: { reliableClubs: 0, trackRecordClubs: 0, score: null },
  activeClubs: [],
  historyClubs: [],
};

// Нулевой стейт геймификации — ровно то, что бэкенд отдаёт при xp=0 (XpPolicy: «Гость» → «Свой»).
const ZERO_GAMIFICATION: GamificationDto = {
  xp: 0,
  level: 1,
  levelName: 'Гость',
  nextLevelName: 'Свой',
  xpIntoLevel: 0,
  xpSpanToNext: 50,
  badges: [],
};

interface MockOptions {
  gamification?: GamificationDto | 'error';
  interests?: string[];
}

/** Возвращает счётчик запросов геймификации — нужен, чтобы проверить refetch по «Повторить». */
function mockEndpoints(opts: MockOptions = {}): { gamHits: () => number } {
  let gamCount = 0;
  server.use(
    http.get('*/api/users/me/reputation', () => HttpResponse.json(EMPTY_REPUTATION)),
    http.get('*/api/users/me/interests', () => HttpResponse.json(opts.interests ?? [])),
    http.get('*/api/users/me/gamification', () => {
      gamCount += 1;
      if (opts.gamification === 'error') {
        return HttpResponse.json({ message: 'boom' }, { status: 500 });
      }
      return HttpResponse.json(opts.gamification ?? ZERO_GAMIFICATION);
    }),
  );
  return { gamHits: () => gamCount };
}

function renderPage() {
  const user = userEvent.setup();
  const result = renderWithProviders(
    <Routes>
      <Route path="/profile" element={<ProfilePage />} />
    </Routes>,
    { routerEntries: ['/profile'] },
  );
  return { ...result, user };
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function setUser(bio: string | null) {
  useAuthStore.setState({
    user: {
      id: VIEWER_ID, telegramId: 1, telegramUsername: 'v', firstName: 'V', lastName: null,
      avatarUrl: null, city: null, country: null, bio,
    },
    isAuthenticated: true,
    isLoading: false,
  } as never);
}

beforeEach(() => setUser(null));

describe('ProfilePage — W3-04: секция «Уровень»', () => {
  it('xp=0: панель показывается всегда (Гость, 0 XP, 0/50) + строка-пояснение про XP', async () => {
    mockEndpoints({ gamification: ZERO_GAMIFICATION });
    renderPage();

    // Секция и нулевая панель видны — новичок понимает, что уровни существуют.
    expect(await screen.findByText('Уровень')).toBeInTheDocument();
    expect(screen.getByText('Гость')).toBeInTheDocument();
    expect(screen.getByText(/0 XP · ур\. 1/)).toBeInTheDocument();
    expect(screen.getByText(/0 \/ 50 XP до «Свой»/)).toBeInTheDocument();
    // Пояснение-тизер про начисление XP.
    expect(screen.getByText(/XP начисляется за посещённые встречи/)).toBeInTheDocument();
  });

  it('xp>0: панель как раньше, строки-пояснения нет', async () => {
    mockEndpoints({
      gamification: {
        xp: 120, level: 2, levelName: 'Свой', nextLevelName: 'Завсегдатай',
        xpIntoLevel: 20, xpSpanToNext: 150, badges: [],
      },
    });
    renderPage();

    expect(await screen.findByText('Свой')).toBeInTheDocument();
    expect(screen.getByText(/120 XP · ур\. 2/)).toBeInTheDocument();
    expect(screen.queryByText(/XP начисляется за посещённые встречи/)).not.toBeInTheDocument();
  });

  it('isError: плашка «Не удалось загрузить уровень» + «Повторить» (role=alert, не «уровня нет»)', async () => {
    const { gamHits } = mockEndpoints({ gamification: 'error' });
    const { user } = renderPage();

    const title = await screen.findByText('Не удалось загрузить уровень');
    // Плашка — контейнер с role="alert" (скринридер озвучит сбой сразу).
    expect(title.closest('[role="alert"]')).not.toBeNull();
    // Секция «Уровень» есть, но панели с прогрессом нет — сбой не выдаётся за «уровня нет».
    expect(screen.getByText('Уровень')).toBeInTheDocument();
    expect(screen.queryByText(/XP · ур\./)).not.toBeInTheDocument();

    // «Повторить» дёргает refetch.
    expect(gamHits()).toBe(1);
    await user.click(screen.getByRole('button', { name: 'Повторить' }));
    await waitFor(() => expect(gamHits()).toBeGreaterThan(1));
  });
});

describe('ProfilePage — W3-05: нудж «О себе»', () => {
  it('bio пуст: виден нудж «Добавь пару слов о себе →», тап открывает модалку', async () => {
    setUser(null);
    mockEndpoints();
    const { user } = renderPage();

    const nudge = await screen.findByRole('button', { name: /Добавь пару слов о себе/ });
    expect(nudge).toBeInTheDocument();

    await user.click(nudge);
    expect(await screen.findByRole('dialog', { name: 'Редактировать профиль' })).toBeInTheDocument();
  });

  it('bio заполнен: показывается текст, нуджа нет', async () => {
    setUser('Люблю настолки и походы');
    mockEndpoints();
    renderPage();

    expect(await screen.findByText('Люблю настолки и походы')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Добавь пару слов о себе/ })).not.toBeInTheDocument();
  });
});

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('../../api/cities', () => ({
  getCities: vi.fn(async () => CITIES),
}));

import { useCityChoice } from '../../components/CityPicker';
import { useAuthStore } from '../../store/useAuthStore';
import type { CityDto, UserDto } from '../../types/api';

const STORAGE_KEY = 'clubs.cityId';

const city = (id: string, name: string, isFeatured = false): CityDto => ({
  id, name, region: null, needsRegion: false, countryCode: 'RU', isFeatured, hasClubs: false,
});

// Москва первой и featured — она же дефолт, когда о человеке ничего не известно.
const CITIES: CityDto[] = [
  city('id-msk', 'Москва', true),
  city('id-kzn', 'Казань'),
  city('id-sochi', 'Сочи'),
];

const makeUser = (cityId: string | null): UserDto => ({
  id: 'user-1',
  telegramId: 1,
  telegramUsername: null,
  firstName: 'Аня',
  lastName: null,
  avatarUrl: null,
  city: null,
  country: null,
  cityId,
  bio: null,
  onboardingTours: ['INTRO'],
});

/** Печатает текущий выбор и даёт сменить его — как это делает пикер. */
const Probe = () => {
  const [choice, setChoice] = useCityChoice();
  return (
    <>
      <span data-testid="choice">{choice?.name ?? '—'}</span>
      <button type="button" onClick={() => setChoice(CITIES[1]!)}>
        сменить
      </button>
    </>
  );
};

function renderProbe() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return render(<Probe />, { wrapper });
}

const choice = () => screen.getByTestId('choice').textContent;

beforeEach(() => {
  localStorage.clear();
  useAuthStore.setState({ user: null, isAuthenticated: true, isLoading: false, error: null });
});

describe('useCityChoice — город каталога', () => {
  it('без профиля и без выбора — первый featured-город справочника', async () => {
    renderProbe();
    await waitFor(() => expect(choice()).toBe('Москва'));
  });

  it('город берётся из профиля, пока человек не выбрал свой', async () => {
    useAuthStore.setState({ user: makeUser('id-sochi') });
    renderProbe();
    // Человек уже сказал, где живёт, — спрашивать второй раз незачем.
    await waitFor(() => expect(choice()).toBe('Сочи'));
  });

  it('пустой город в профиле игнорируется', async () => {
    useAuthStore.setState({ user: makeUser(null) });
    renderProbe();
    await waitFor(() => expect(choice()).toBe('Москва'));
  });

  it('город из профиля, которого нет в справочнике, не ломает выбор', async () => {
    // Легаси-профиль: FK указывает на запись, которой в текущем сиде нет.
    useAuthStore.setState({ user: makeUser('id-удалён') });
    renderProbe();
    await waitFor(() => expect(choice()).toBe('Москва'));
  });

  it('явный выбор сильнее профиля и переживает перезапуск', async () => {
    useAuthStore.setState({ user: makeUser('id-sochi') });
    const { unmount } = renderProbe();
    await waitFor(() => expect(choice()).toBe('Сочи'));

    act(() => screen.getByRole('button', { name: 'сменить' }).click());
    await waitFor(() => expect(choice()).toBe('Казань'));
    expect(localStorage.getItem(STORAGE_KEY)).toBe('id-kzn');
    unmount();

    // Перезапуск: выбор поднимается из localStorage, профиль его не перебивает.
    renderProbe();
    await waitFor(() => expect(choice()).toBe('Казань'));
  });
});

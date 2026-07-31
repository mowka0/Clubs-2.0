import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

import { useCityChoice } from '../../components/CityPicker';
import { useAuthStore } from '../../store/useAuthStore';
import type { UserDto } from '../../types/api';

const STORAGE_KEY = 'clubs.cityChoice';

const makeUser = (city: string | null, country: string | null): UserDto => ({
  id: 'user-1',
  telegramId: 1,
  telegramUsername: null,
  firstName: 'Аня',
  lastName: null,
  avatarUrl: null,
  city,
  country,
  bio: null,
  onboardingTours: ['INTRO'],
});

/** Печатает текущий выбор и даёт сменить его — как это делает пикер. */
const Probe = () => {
  const [choice, setChoice] = useCityChoice();
  return (
    <>
      <span data-testid="choice">{`${choice.country}/${choice.city}`}</span>
      <button type="button" onClick={() => setChoice({ country: 'RU', city: 'Казань' })}>
        сменить
      </button>
    </>
  );
};

const choice = () => screen.getByTestId('choice').textContent;

beforeEach(() => {
  localStorage.clear();
  useAuthStore.setState({ user: null, isAuthenticated: true, isLoading: false, error: null });
});

describe('useCityChoice — город каталога', () => {
  it('без профиля и без выбора — Москва', () => {
    render(<Probe />);
    expect(choice()).toBe('RU/Москва');
  });

  it('город берётся из профиля, пока человек не выбрал свой', () => {
    useAuthStore.setState({ user: makeUser('Казань', 'RU') });
    render(<Probe />);
    // Человек уже сказал, где живёт, — спрашивать второй раз незачем.
    expect(choice()).toBe('RU/Казань');
  });

  it('страна восстанавливается по городу, если в профиле её нет', () => {
    useAuthStore.setState({ user: makeUser('Алматы', null) });
    render(<Probe />);
    // Иначе пикер открылся бы на вкладке чужой страны.
    expect(choice()).toBe('KZ/Алматы');
  });

  it('пустой город в профиле игнорируется', () => {
    useAuthStore.setState({ user: makeUser('   ', 'RU') });
    render(<Probe />);
    expect(choice()).toBe('RU/Москва');
  });

  it('явный выбор сильнее профиля и переживает перезапуск', () => {
    useAuthStore.setState({ user: makeUser('Сочи', 'RU') });
    const { unmount } = render(<Probe />);
    expect(choice()).toBe('RU/Сочи');

    act(() => screen.getByRole('button', { name: 'сменить' }).click());
    expect(choice()).toBe('RU/Казань');
    unmount();

    // Перезапуск: выбор поднимается из localStorage, профиль его не перебивает.
    render(<Probe />);
    expect(choice()).toBe('RU/Казань');
    expect(localStorage.getItem(STORAGE_KEY)).toContain('Казань');
  });
});

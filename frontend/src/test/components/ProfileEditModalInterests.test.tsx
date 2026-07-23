import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));
vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));
vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

import { ProfileEditModal } from '../../components/profile/ProfileEditModal';
import { useAuthStore } from '../../store/useAuthStore';

const USER_RESPONSE = {
  id: 'u1', telegramId: 1, telegramUsername: 'v', firstName: 'V', lastName: null,
  avatarUrl: null, city: null, country: null, bio: null, onboardedAt: null,
};

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  useAuthStore.setState({
    user: USER_RESPONSE,
    isAuthenticated: true,
    isLoading: false,
  } as never);
});

describe('ProfileEditModal — недобранный интерес (баг PO 2026-07-22)', () => {
  it('текст в поле без Enter/запятой уезжает в PATCH при «Сохранить» — в lowercase', async () => {
    let body: { interests?: string[] } | null = null;
    server.use(
      http.get('*/api/interests/suggest', () => HttpResponse.json([])),
      http.patch('*/api/users/me', async ({ request }) => {
        body = (await request.json()) as { interests?: string[] };
        return HttpResponse.json(USER_RESPONSE);
      }),
    );
    renderWithProviders(<ProfileEditModal initialInterests={[]} onClose={vi.fn()} />);

    // Клавиатура телефона капитализирует первый ввод — «Настолки»; Enter юзер не жмёт
    fireEvent.change(screen.getByLabelText('Добавить интерес'), { target: { value: 'Настолки' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(body).not.toBeNull());
    // Интерес не потерян и нормализован к канонично-строчной форме
    expect(body!.interests).toEqual(['настолки']);
  });

  it('уход с поля (blur) фиксирует недобранный текст как чип в каноничной форме', async () => {
    server.use(http.get('*/api/interests/suggest', () => HttpResponse.json([])));
    renderWithProviders(<ProfileEditModal initialInterests={[]} onClose={vi.fn()} />);

    const input = screen.getByLabelText('Добавить интерес');
    fireEvent.change(input, { target: { value: 'Походы В Горы' } });
    fireEvent.blur(input);

    expect(await screen.findByText('походы в горы')).toBeInTheDocument();
    expect((input as HTMLInputElement).value).toBe('');
  });
});

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

// BackButton дёргает Telegram SDK — в unit-тесте не нужен.
vi.mock('../../hooks/useBackButton', () => ({ useBackButton: vi.fn() }));

const submitFeedbackMock = vi.fn();
vi.mock('../../api/feedback', () => ({
  submitFeedback: (body: unknown) => submitFeedbackMock(body),
}));

import { FeedbackPage } from '../../pages/FeedbackPage';
import { ApiError } from '../../api/apiClient';

// Свой рендер вместо renderWithProviders: нужен initialEntry-объект со state.from
// (util принимает только строки), state кладёт CreateActivityFlow при навигации.
function renderPage(state?: { from?: string }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[{ pathname: '/feedback', state }]}>
        <FeedbackPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  submitFeedbackMock.mockReset();
});

describe('FeedbackPage', () => {
  it('кнопка «Отправить» неактивна, пока сообщение пустое', () => {
    renderPage();

    expect(screen.getByRole('button', { name: 'Отправить' })).toBeDisabled();
  });

  it('отправляет обрезанный текст и route открытия, показывает подтверждение', async () => {
    const user = userEvent.setup();
    submitFeedbackMock.mockResolvedValue(undefined);
    renderPage({ from: '/my-clubs' });

    await user.type(screen.getByPlaceholderText(/Опишите проблему/), '  Кнопка не работает  ');
    await user.click(screen.getByRole('button', { name: 'Отправить' }));

    await waitFor(() =>
      expect(submitFeedbackMock).toHaveBeenCalledWith({
        message: 'Кнопка не работает',
        page: '/my-clubs',
      }),
    );
    expect(await screen.findByText(/Сообщение отправлено/)).toBeInTheDocument();
  });

  it('503 показывает «поддержка временно недоступна» и не показывает успех', async () => {
    const user = userEvent.setup();
    submitFeedbackMock.mockRejectedValue(new ApiError(503, 'Support account is not available'));
    renderPage();

    await user.type(screen.getByPlaceholderText(/Опишите проблему/), 'Баг');
    await user.click(screen.getByRole('button', { name: 'Отправить' }));

    expect(await screen.findByText(/поддержка временно недоступна/)).toBeInTheDocument();
    expect(screen.queryByText(/Сообщение отправлено/)).toBeNull();
  });
});

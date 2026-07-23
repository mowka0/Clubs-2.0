import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));

// Тяжёлый модал флоу стабим — проверяем только, что он открылся и с каким canCreate.
vi.mock('../../components/manage/CreateActivityFlow', () => ({
  CreateActivityFlow: ({ open, canCreate }: { open: boolean; canCreate: boolean }) =>
    open ? <div data-testid="create-flow" data-can-create={String(canCreate)} /> : null,
}));

// Определение «организатора» контролируем напрямую — правило видимости и есть предмет теста.
const useOrganizerClubsMock = vi.fn();
vi.mock('../../queries/organizerClubs', () => ({
  useOrganizerClubs: () => useOrganizerClubsMock(),
}));

import { AppDock } from '../../components/Layout';
import { useCreateFlowStore } from '../../store/useCreateFlowStore';
import { renderWithProviders } from '../utils/renderWithProviders';

beforeEach(() => {
  useOrganizerClubsMock.mockReset();
  // Стор глобальный — сбрасываем, чтобы open-состояние не протекало между тестами.
  useCreateFlowStore.setState({ isOpen: false });
});

// Фича feedback: FAB «+» открывает flow ВСЕМ (пункт «Сообщить о проблеме» общедоступен).
// Бывший тост-гардрейл «создавать могут организаторы» переехал в состав пунктов шита —
// его проверяет CreateActivityPicker.test.tsx, здесь проверяем только canCreate-проброс.
describe('AppDock — FAB «создать» открывает flow всем', () => {
  it('не-организатор: flow открывается с canCreate=false (только обратная связь)', async () => {
    const user = userEvent.setup();
    useOrganizerClubsMock.mockReturnValue({ clubs: [], isLoading: false });
    renderWithProviders(<AppDock />, { routerEntries: ['/events'] });

    await user.click(screen.getByRole('button', { name: /создать/i }));

    expect(screen.getByTestId('create-flow')).toHaveAttribute('data-can-create', 'false');
  });

  it('организатор: flow открывается с canCreate=true', async () => {
    const user = userEvent.setup();
    useOrganizerClubsMock.mockReturnValue({
      clubs: [{ id: 'club-1', name: 'Alpha', avatarUrl: null, category: 'sport' }],
      isLoading: false,
    });
    renderWithProviders(<AppDock />, { routerEntries: ['/events'] });

    await user.click(screen.getByRole('button', { name: /создать/i }));

    expect(screen.getByTestId('create-flow')).toHaveAttribute('data-can-create', 'true');
  });
});

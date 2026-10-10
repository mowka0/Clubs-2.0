import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

/**
 * Аватар в шапке «Управления» — путь назад на страницу клуба для тех, кто не нашёл нативную
 * кнопку и свайп (PO 2026-10-08). Страница клуба обычно лежит прямо под экраном — тогда шаг
 * назад по истории; позади пусто — переход на клуб.
 */

const { navigateMock } = vi.hoisted(() => ({ navigateMock: vi.fn() }));

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

import { ManageHeader } from '../../components/manage/ManageHeader';
import { mockClubDetail } from '../mocks/handlers';

/** happy-dom не хранит state у `replaceState` — индекс истории подменяем геттером. */
function setHistoryIndex(index: number) {
  Object.defineProperty(window.history, 'state', {
    configurable: true,
    get: () => ({ idx: index }),
  });
}

function renderHeader(avatarUrl: string | null = null) {
  return render(
    <MemoryRouter>
      <ManageHeader club={{ ...mockClubDetail, id: 'club-1', avatarUrl }} />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  navigateMock.mockClear();
});

describe('ManageHeader — аватар ведёт на страницу клуба', () => {
  it('страница клуба под «Управлением» — шаг назад по истории', async () => {
    setHistoryIndex(1);
    renderHeader();

    await userEvent.click(screen.getByRole('button', { name: 'Открыть страницу клуба' }));

    expect(navigateMock).toHaveBeenCalledWith(-1);
  });

  it('позади пусто — переход на страницу клуба', async () => {
    setHistoryIndex(0);
    renderHeader();

    await userEvent.click(screen.getByRole('button', { name: 'Открыть страницу клуба' }));

    expect(navigateMock).toHaveBeenCalledWith('/clubs/club-1');
  });

  it('с картинкой — аватар клуба', () => {
    setHistoryIndex(0);
    renderHeader('https://cdn/ava.jpg');

    const avatar = screen.getByRole('button', { name: 'Открыть страницу клуба' });
    expect(avatar.querySelector('img')?.getAttribute('src')).toBe('https://cdn/ava.jpg');
  });

  it('без картинки — первая буква названия', () => {
    setHistoryIndex(0);
    renderHeader();

    const avatar = screen.getByRole('button', { name: 'Открыть страницу клуба' });
    expect(avatar.textContent).toBe(mockClubDetail.name.charAt(0).toUpperCase());
  });
});

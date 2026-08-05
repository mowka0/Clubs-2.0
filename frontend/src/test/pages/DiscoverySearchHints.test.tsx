import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
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

import { DiscoveryPage } from '../../pages/DiscoveryPage';

// jsdom не знает IntersectionObserver, а страница вешает его на sentinel бесконечной прокрутки.
// Подгрузка следующих страниц в этих тестах не проверяется — достаточно молчаливой заглушки.
class NoopIntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() { return []; }
  root = null;
  rootMargin = '';
  thresholds: readonly number[] = [];
}
vi.stubGlobal('IntersectionObserver', NoopIntersectionObserver);

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

/** Запоминает, с какими параметрами звали автодополнение, и отвечает заданными темами. */
function mockSuggest(topics: string[], captured: { clubsOnly?: string | null; q?: string | null }) {
  server.use(
    http.get('*/api/interests/suggest', ({ request }) => {
      const url = new URL(request.url);
      captured.clubsOnly = url.searchParams.get('clubsOnly');
      captured.q = url.searchParams.get('q');
      return HttpResponse.json(topics);
    }),
    http.get('*/api/clubs', () =>
      HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 20 })),
  );
}

describe('Поиск каталога — подсказки тем', () => {
  it('после ввода показывает темы и запрашивает их в режиме clubsOnly', async () => {
    const captured: { clubsOnly?: string | null; q?: string | null } = {};
    mockSuggest(['настолки', 'настольный теннис'], captured);
    renderWithProviders(<DiscoveryPage />);

    fireEvent.change(screen.getByLabelText('Поиск клубов'), { target: { value: 'наст' } });

    expect(await screen.findByText('настолки')).toBeTruthy();
    expect(screen.getByText('настольный теннис')).toBeTruthy();
    // Каталогу нельзя подсказывать интересы, по которым нет ни одного клуба.
    expect(captured.clubsOnly).toBe('true');
    expect(captured.q).toBe('наст');
  });

  it('тап по подсказке подставляет тему в поиск и закрывает список', async () => {
    mockSuggest(['настолки'], {});
    renderWithProviders(<DiscoveryPage />);

    const input = screen.getByLabelText('Поиск клубов') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'наст' } });
    fireEvent.click(await screen.findByText('настолки'));

    expect(input.value).toBe('настолки');
    await waitFor(() => expect(screen.queryByRole('listbox', { name: 'Подсказки тем' })).toBeNull());
  });

  it('Escape закрывает подсказки, не трогая введённый текст', async () => {
    mockSuggest(['настолки'], {});
    renderWithProviders(<DiscoveryPage />);

    const input = screen.getByLabelText('Поиск клубов') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'наст' } });
    await screen.findByText('настолки');

    fireEvent.keyDown(input, { key: 'Escape' });

    await waitFor(() => expect(screen.queryByText('настолки')).toBeNull());
    expect(input.value).toBe('наст');
  });

  it('до второго символа подсказок нет — не бьём в сеть на каждую букву', async () => {
    const captured: { clubsOnly?: string | null; q?: string | null } = {};
    mockSuggest(['настолки'], captured);
    renderWithProviders(<DiscoveryPage />);

    fireEvent.change(screen.getByLabelText('Поиск клубов'), { target: { value: 'н' } });

    await new Promise((r) => setTimeout(r, 400));
    expect(screen.queryByText('настолки')).toBeNull();
    expect(captured.q).toBeUndefined();
  });
});

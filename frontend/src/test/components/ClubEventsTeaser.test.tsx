import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import type { ClubEventsTeaserDto } from '../../types/api';

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
}));

vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

import { ClubEventsTeaser } from '../../components/club/ClubEventsTeaser';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const teaser: ClubEventsTeaserDto = {
  upcoming: [
    {
      id: 'e1', title: 'Пробежка 7 км', eventDatetime: '2026-08-01T08:00:00Z',
      status: 'upcoming', isUrgent: false, isOpenEvent: false, goingCount: 9, confirmedCount: 0,
    },
    {
      id: 'e2', title: 'Марафонский бранч', eventDatetime: '2026-08-03T11:00:00Z',
      status: 'stage_2', isUrgent: false, isOpenEvent: true, goingCount: 4, confirmedCount: 6,
    },
  ],
  past: [
    {
      id: 'e3', title: 'Интервалы в Битце', eventDatetime: '2026-07-19T08:00:00Z',
      status: 'completed', isUrgent: false, isOpenEvent: false, goingCount: 0, confirmedCount: 8,
    },
  ],
  totalPastCount: 12,
};

function renderTeaser() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ClubEventsTeaser clubId="club-1" lockHint="Место встреч откроется после взноса" />
    </QueryClientProvider>,
  );
}

describe('ClubEventsTeaser — афиша клуба для смотрящего без доступа', () => {
  it('показывает будущие и прошедшие встречи с фазовым счётчиком и замочной подписью', async () => {
    server.use(http.get('*/api/clubs/:id/events/teaser', () => HttpResponse.json(teaser)));

    renderTeaser();

    expect(await screen.findByText('Пробежка 7 км')).toBeInTheDocument();
    // Фаза голосования — «идут», Этап 2 — «подтвердили» (F5-21).
    expect(screen.getByText('идут 9')).toBeInTheDocument();
    expect(screen.getByText('подтвердили 6')).toBeInTheDocument();
    expect(screen.getByText('Интервалы в Битце')).toBeInTheDocument();
    expect(screen.getByText('прошла')).toBeInTheDocument();
    expect(screen.getByText('Уже прошло — 12')).toBeInTheDocument();
    expect(screen.getByText(/после взноса/)).toBeInTheDocument();
  });

  it('не рендерится вовсе при пустой афише', async () => {
    server.use(
      http.get('*/api/clubs/:id/events/teaser', () =>
        HttpResponse.json({ upcoming: [], past: [], totalPastCount: 0 }),
      ),
    );

    const { container } = renderTeaser();

    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });
});

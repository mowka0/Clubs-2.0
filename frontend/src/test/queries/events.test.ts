import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { ReactNode, createElement } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import type { EventDetailDto } from '../../types/api';

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
}));

vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

import { useCastVoteMutation, useEventQuery, useUpdateEventMutation } from '../../queries/events';
import { queryKeys } from '../../queries/queryKeys';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function makeWrapper(client: QueryClient) {
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client }, children);
}

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity, staleTime: 0 },
      mutations: { retry: false },
    },
  });
}

const mockEvent: EventDetailDto = {
  id: 'evt-1',
  clubId: 'club-1',
  title: 'Test event',
  description: null,
  locationText: 'Park',
  locationLat: null,
  locationLon: null,
  locationHint: null,
  eventDatetime: '2026-05-01T18:00:00Z',
  participantLimit: 10,
  votingOpensDaysBefore: 7,
  status: 'upcoming',
  format: 'max',
  goingCount: 0,
  maybeCount: 0,
  notGoingCount: 0,
  confirmedCount: 0,
  noAnswerCount: 0,
  stage2LeadMinutes: 1080, stage2LeadMinutesOverride: null,
  rosterDeadline: null, rosterClosed: false, waitlistedCount: 0, declineCostPoints: 0,
  attendanceMarked: false,
  attendanceFinalized: false,
  cancellationReason: null,
  photoUrl: null,
  createdAt: null,
};

describe('useEventQuery', () => {
  it('fetches event detail by id', async () => {
    server.use(http.get('*/api/events/:id', () => HttpResponse.json(mockEvent)));

    const client = makeClient();
    const { result } = renderHook(() => useEventQuery('evt-1'), {
      wrapper: makeWrapper(client),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.title).toBe('Test event');
  });
});

describe('useCastVoteMutation', () => {
  it('invalidates event detail cache on success', async () => {
    server.use(
      http.get('*/api/events/:id', () => HttpResponse.json(mockEvent)),
      http.post('*/api/events/:id/vote', () =>
        HttpResponse.json({ eventId: 'evt-1', vote: 'going', goingCount: 1, maybeCount: 0, notGoingCount: 0 }),
      ),
    );

    const client = makeClient();
    client.setQueryData(queryKeys.events.detail('evt-1'), mockEvent);

    const { result } = renderHook(() => useCastVoteMutation(), {
      wrapper: makeWrapper(client),
    });

    result.current.mutate({ eventId: 'evt-1', vote: 'going' });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const state = client.getQueryState(queryKeys.events.detail('evt-1'));
    expect(state?.isInvalidated).toBe(true);
  });
});

describe('useUpdateEventMutation', () => {
  it('sends the full payload and invalidates event detail cache on success', async () => {
    let sentBody: unknown = null;
    server.use(
      http.put('*/api/events/:id', async ({ request }) => {
        sentBody = await request.json();
        return HttpResponse.json({ ...mockEvent, eventDatetime: '2026-05-03T18:00:00Z' });
      }),
    );

    const client = makeClient();
    client.setQueryData(queryKeys.events.detail('evt-1'), mockEvent);

    const { result } = renderHook(() => useUpdateEventMutation(), {
      wrapper: makeWrapper(client),
    });

    result.current.mutate({ eventId: 'evt-1', clubId: 'club-1', body: { title: 'Тест', locationHint: 'у входа', eventDatetime: '2026-05-03T18:00:00Z', participantLimit: 20 } });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(sentBody).toEqual({ title: 'Тест', locationHint: 'у входа', eventDatetime: '2026-05-03T18:00:00Z', participantLimit: 20 });
    const state = client.getQueryState(queryKeys.events.detail('evt-1'));
    expect(state?.isInvalidated).toBe(true);
  });

  it('surfaces a 409 (stage 2 already started) as a mutation error', async () => {
    server.use(
      http.put('*/api/events/:id', () =>
        HttpResponse.json(
          { message: 'Встречу нельзя изменить: подтверждение мест уже началось, событие прошло или отменено' },
          { status: 409 },
        ),
      ),
    );

    const client = makeClient();
    const { result } = renderHook(() => useUpdateEventMutation(), {
      wrapper: makeWrapper(client),
    });

    result.current.mutate({ eventId: 'evt-1', clubId: 'club-1', body: { title: 'Тест', locationHint: 'у входа', eventDatetime: '2026-05-03T18:00:00Z', participantLimit: 20 } });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.message).toContain('подтверждение мест уже началось');
  });
});

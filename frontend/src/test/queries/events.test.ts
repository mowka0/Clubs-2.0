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

import { useCastVoteMutation, useEventQuery } from '../../queries/events';
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
  eventDatetime: '2026-05-01T18:00:00Z',
  participantLimit: 10,
  votingOpensDaysBefore: 7,
  status: 'upcoming',
  goingCount: 0,
  maybeCount: 0,
  notGoingCount: 0,
  confirmedCount: 0,
  attendanceMarked: false,
  attendanceFinalized: false,
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

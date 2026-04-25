import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { ReactNode, createElement } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
}));

vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

import {
  useApproveApplicationMutation,
  useMyApplicationsQuery,
} from '../../queries/applications';
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

describe('useMyApplicationsQuery', () => {
  it('returns empty array on happy path', async () => {
    server.use(http.get('*/api/users/me/applications', () => HttpResponse.json([])));

    const client = makeClient();
    const { result } = renderHook(() => useMyApplicationsQuery(), {
      wrapper: makeWrapper(client),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual([]);
  });
});

describe('useApproveApplicationMutation', () => {
  it('invalidates club applications + my applications caches', async () => {
    server.use(
      http.post('*/api/applications/:id/approve', () => new HttpResponse(null, { status: 204 })),
    );

    const client = makeClient();
    client.setQueryData(queryKeys.clubs.applications('club-1', 'pending'), []);
    client.setQueryData(queryKeys.applications.mine(), []);

    const { result } = renderHook(() => useApproveApplicationMutation(), {
      wrapper: makeWrapper(client),
    });

    result.current.mutate({ applicationId: 'app-1', clubId: 'club-1' });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(
      client.getQueryState(queryKeys.clubs.applications('club-1', 'pending'))?.isInvalidated,
    ).toBe(true);
    expect(client.getQueryState(queryKeys.applications.mine())?.isInvalidated).toBe(true);
  });
});

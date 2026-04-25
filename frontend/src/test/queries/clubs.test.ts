import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { ReactNode } from 'react';
import { createElement } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { mockClubDetail } from '../mocks/handlers';
import type { ClubListItemDto, MembershipDto } from '../../types/api';

// Mock Telegram SDK before importing anything that touches apiClient -> sdk
vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
}));

vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

import {
  useClubQuery,
  useClubsQuery,
  useCreateClubMutation,
  useMyClubsQuery,
} from '../../queries/clubs';
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

describe('useClubQuery', () => {
  beforeEach(() => server.resetHandlers());

  it('returns club detail on happy path', async () => {
    const client = makeClient();
    const { result } = renderHook(() => useClubQuery('club-123'), {
      wrapper: makeWrapper(client),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.id).toBe('club-123');
    expect(result.current.data?.name).toBe(mockClubDetail.name);
  });

  it('exposes error state on 404', async () => {
    server.use(
      http.get('*/api/clubs/:id', () =>
        HttpResponse.json({ message: 'Club not found' }, { status: 404 }),
      ),
    );

    const client = makeClient();
    const { result } = renderHook(() => useClubQuery('missing'), {
      wrapper: makeWrapper(client),
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.message).toMatch(/not found/i);
  });

  it('does not run when clubId is undefined', () => {
    const client = makeClient();
    const { result } = renderHook(() => useClubQuery(undefined), {
      wrapper: makeWrapper(client),
    });

    expect(result.current.fetchStatus).toBe('idle');
  });
});

describe('useMyClubsQuery', () => {
  it('returns memberships on happy path', async () => {
    const memberships: MembershipDto[] = [
      {
        id: 'mem-1',
        userId: 'u1',
        clubId: 'c1',
        status: 'active',
        role: 'member',
        joinedAt: null,
        subscriptionExpiresAt: null,
      },
    ];
    server.use(http.get('*/api/users/me/clubs', () => HttpResponse.json(memberships)));

    const client = makeClient();
    const { result } = renderHook(() => useMyClubsQuery(), {
      wrapper: makeWrapper(client),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data?.[0].clubId).toBe('c1');
  });
});

describe('useCreateClubMutation', () => {
  it('invalidates clubs.my() cache on success so MyClubsPage refetches', async () => {
    const client = makeClient();
    // Seed clubs.my() in cache so we can detect invalidation
    client.setQueryData(queryKeys.clubs.my(), [] as MembershipDto[]);

    const { result } = renderHook(() => useCreateClubMutation(), {
      wrapper: makeWrapper(client),
    });

    result.current.mutate({
      name: 'New Club',
      description: 'Description text',
      category: 'other',
      accessType: 'open',
      city: 'Moscow',
      memberLimit: 30,
      subscriptionPrice: 0,
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const state = client.getQueryState(queryKeys.clubs.my());
    expect(state?.isInvalidated).toBe(true);
  });
});

describe('useClubsQuery (infinite)', () => {
  it('initial fetch returns first page; fetchNextPage appends second page', async () => {
    const page0: ClubListItemDto[] = [
      {
        id: 'c1',
        name: 'C1',
        category: 'other',
        accessType: 'open',
        city: 'Moscow',
        subscriptionPrice: 0,
        memberCount: 1,
        memberLimit: 10,
        avatarUrl: null,
        nearestEvent: null,
        tags: [],
      },
    ];
    const page1: ClubListItemDto[] = [
      {
        id: 'c2',
        name: 'C2',
        category: 'other',
        accessType: 'open',
        city: 'Moscow',
        subscriptionPrice: 0,
        memberCount: 1,
        memberLimit: 10,
        avatarUrl: null,
        nearestEvent: null,
        tags: [],
      },
    ];

    server.use(
      http.get('*/api/clubs', ({ request }) => {
        const url = new URL(request.url);
        const page = Number(url.searchParams.get('page') ?? '0');
        const content = page === 0 ? page0 : page1;
        return HttpResponse.json({
          content,
          totalElements: 2,
          totalPages: 2,
          page,
          size: 1,
        });
      }),
    );

    const client = makeClient();
    const { result } = renderHook(() => useClubsQuery({ size: '1' }), {
      wrapper: makeWrapper(client),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.pages[0].content[0].id).toBe('c1');
    expect(result.current.hasNextPage).toBe(true);

    await result.current.fetchNextPage();

    await waitFor(() => expect(result.current.data?.pages.length).toBe(2));
    expect(result.current.data?.pages[1].content[0].id).toBe('c2');
    expect(result.current.hasNextPage).toBe(false);
  });
});

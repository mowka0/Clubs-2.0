import { ReactElement } from 'react';
import { render, RenderResult } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';

export interface RenderWithProvidersOptions {
  queryClient?: QueryClient;
  routerEntries?: string[];
}

export interface RenderWithProvidersResult extends RenderResult {
  queryClient: QueryClient;
}

/**
 * Test wrapper for components that depend on TanStack Query and react-router.
 * Defaults: fresh QueryClient per render with retry disabled and infinite gcTime
 * so tests are deterministic and don't garbage-collect cache mid-assertion.
 */
export function renderWithProviders(
  ui: ReactElement,
  options: RenderWithProvidersOptions = {},
): RenderWithProvidersResult {
  const queryClient = options.queryClient ?? new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity, staleTime: 0 },
      mutations: { retry: false },
    },
  });

  const result = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={options.routerEntries ?? ['/']}>
        {ui}
      </MemoryRouter>
    </QueryClientProvider>,
  );

  return { ...result, queryClient };
}

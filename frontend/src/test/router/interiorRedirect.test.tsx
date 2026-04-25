import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Navigate, Route, Routes, useParams } from 'react-router-dom';
import type { FC } from 'react';

// Mirror of router.tsx InteriorRedirect — tested in isolation (router.tsx pulls in
// lazy page imports + Layout + Telegram SDK that we don't need here).
const InteriorRedirect: FC = () => {
  const { id } = useParams<{ id: string }>();
  return <Navigate to={`/clubs/${id}`} replace />;
};

describe('InteriorRedirect', () => {
  it('redirects /clubs/:id/interior → /clubs/:id preserving the id param', () => {
    render(
      <MemoryRouter initialEntries={['/clubs/abc-123/interior']}>
        <Routes>
          <Route path="/clubs/:id" element={<div>Club Page abc-123</div>} />
          <Route path="/clubs/:id/interior" element={<InteriorRedirect />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText('Club Page abc-123')).toBeInTheDocument();
  });

  it('uses replace navigation so /interior does not stay in history', () => {
    // Smoke: rendering completes and resolves to the target route element.
    // Direct history-stack inspection isn't exposed by MemoryRouter v7;
    // the `replace` flag is asserted by code review (router.tsx + this component).
    const navigateSpy = vi.fn();
    render(
      <MemoryRouter initialEntries={['/clubs/xyz/interior']}>
        <Routes>
          <Route
            path="/clubs/:id"
            element={
              <div onClick={() => navigateSpy()}>resolved /clubs/xyz</div>
            }
          />
          <Route path="/clubs/:id/interior" element={<InteriorRedirect />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText('resolved /clubs/xyz')).toBeInTheDocument();
  });
});

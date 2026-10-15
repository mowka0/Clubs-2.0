import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render } from '@testing-library/react';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { useEffect } from 'react';

const { trackMock } = vi.hoisted(() => ({ trackMock: vi.fn() }));
vi.mock('../../analytics/track', () => ({ track: trackMock }));

import { ScreenTracker } from '../../analytics/ScreenTracker';

const CLUB_ID = '7c2e1d2a-0000-4000-8000-000000000001';

let go: (to: string) => void = () => {};
const Probe = () => {
  const navigate = useNavigate();
  useEffect(() => {
    go = navigate;
  }, [navigate]);
  return null;
};

/** `screen_opened` на смену пути; query и hash не считаются (AC-10). */
describe('ScreenTracker', () => {
  beforeEach(() => trackMock.mockReset());

  it('пишет первый экран, каждый переход и ничего на смену query', () => {
    render(
      <MemoryRouter initialEntries={['/my-clubs']}>
        <ScreenTracker />
        <Probe />
      </MemoryRouter>,
    );
    expect(trackMock).toHaveBeenCalledTimes(1);
    expect(trackMock).toHaveBeenLastCalledWith('screen_opened', { screen: 'my_clubs' }, undefined);

    act(() => go(`/clubs/${CLUB_ID}`));
    expect(trackMock).toHaveBeenCalledTimes(2);
    expect(trackMock).toHaveBeenLastCalledWith('screen_opened', { screen: 'club' }, CLUB_ID);

    act(() => go(`/clubs/${CLUB_ID}?tab=manage`));
    expect(trackMock).toHaveBeenCalledTimes(2);

    act(() => go(`/events/${CLUB_ID}`));
    expect(trackMock).toHaveBeenCalledTimes(3);
    expect(trackMock).toHaveBeenLastCalledWith('screen_opened', { screen: 'event', eventId: CLUB_ID }, undefined);
  });

  it('редирект экраном не считается', () => {
    render(
      <MemoryRouter initialEntries={['/organizer']}>
        <ScreenTracker />
      </MemoryRouter>,
    );
    expect(trackMock).not.toHaveBeenCalled();
  });
});

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act } from '@testing-library/react';
import type { ClubListItemDto, MembershipDto, PageResponse } from '../../types/api';

// Mock the clubs API module before importing the store
vi.mock('../../api/clubs', () => ({
  getClubs: vi.fn(),
  getMyClubs: vi.fn(),
  getClub: vi.fn(),
  createClub: vi.fn(),
  getClubByInvite: vi.fn(),
}));

// Import after mock setup
import { useClubsStore } from '../../store/useClubsStore';
import { getClubs, getMyClubs } from '../../api/clubs';

const mockGetClubs = vi.mocked(getClubs);
const mockGetMyClubs = vi.mocked(getMyClubs);

function makeClubItem(overrides: Partial<ClubListItemDto> = {}): ClubListItemDto {
  return {
    id: 'club-1',
    name: 'Test Club',
    category: 'sport',
    accessType: 'open',
    city: 'Москва',
    subscriptionPrice: 0,
    memberCount: 10,
    memberLimit: 50,
    avatarUrl: null,
    nearestEvent: null,
    tags: [],
    ...overrides,
  };
}

function makeMembership(overrides: Partial<MembershipDto> = {}): MembershipDto {
  return {
    id: 'mem-1',
    userId: 'user-1',
    clubId: 'club-1',
    status: 'active',
    role: 'member',
    joinedAt: '2025-01-01T00:00:00Z',
    subscriptionExpiresAt: null,
    ...overrides,
  };
}

describe('useClubsStore', () => {
  beforeEach(() => {
    // Reset the store state before each test
    useClubsStore.setState({
      clubs: [],
      myClubs: [],
      totalPages: 0,
      totalElements: 0,
      loading: false,
      error: null,
    });
    vi.clearAllMocks();
  });

  describe('fetchClubs', () => {
    it('sets loading=true during fetch and populates clubs on success', async () => {
      const clubs = [makeClubItem({ id: 'c1' }), makeClubItem({ id: 'c2' })];
      const pageResponse: PageResponse<ClubListItemDto> = {
        content: clubs,
        totalElements: 2,
        totalPages: 1,
        page: 0,
        size: 20,
      };

      // Create a controllable promise so we can inspect intermediate state
      let resolvePromise!: (val: PageResponse<ClubListItemDto>) => void;
      const pendingPromise = new Promise<PageResponse<ClubListItemDto>>((resolve) => {
        resolvePromise = resolve;
      });
      mockGetClubs.mockReturnValue(pendingPromise);

      // Start fetch
      let fetchPromise: Promise<void>;
      act(() => {
        fetchPromise = useClubsStore.getState().fetchClubs();
      });

      // Check loading is true while fetching
      expect(useClubsStore.getState().loading).toBe(true);
      expect(useClubsStore.getState().error).toBeNull();

      // Resolve the promise
      await act(async () => {
        resolvePromise(pageResponse);
        await fetchPromise!;
      });

      // Check final state
      const state = useClubsStore.getState();
      expect(state.loading).toBe(false);
      expect(state.clubs).toHaveLength(2);
      expect(state.clubs[0].id).toBe('c1');
      expect(state.clubs[1].id).toBe('c2');
      expect(state.totalPages).toBe(1);
      expect(state.totalElements).toBe(2);
      expect(state.error).toBeNull();
    });

    it('sets error on API failure', async () => {
      mockGetClubs.mockRejectedValue(new Error('Network error'));

      await act(async () => {
        await useClubsStore.getState().fetchClubs();
      });

      const state = useClubsStore.getState();
      expect(state.loading).toBe(false);
      expect(state.error).toBe('Network error');
      expect(state.clubs).toHaveLength(0);
    });

    it('appends clubs for page > 0', async () => {
      // Pre-populate with page 0 data
      useClubsStore.setState({
        clubs: [makeClubItem({ id: 'c1' })],
      });

      const page2Clubs = [makeClubItem({ id: 'c3' })];
      mockGetClubs.mockResolvedValue({
        content: page2Clubs,
        totalElements: 3,
        totalPages: 2,
        page: 1,
        size: 20,
      });

      await act(async () => {
        await useClubsStore.getState().fetchClubs({ page: '1' });
      });

      const state = useClubsStore.getState();
      expect(state.clubs).toHaveLength(2);
      expect(state.clubs[0].id).toBe('c1');
      expect(state.clubs[1].id).toBe('c3');
    });

    it('replaces clubs for page 0', async () => {
      // Pre-populate
      useClubsStore.setState({
        clubs: [makeClubItem({ id: 'old' })],
      });

      const freshClubs = [makeClubItem({ id: 'new1' }), makeClubItem({ id: 'new2' })];
      mockGetClubs.mockResolvedValue({
        content: freshClubs,
        totalElements: 2,
        totalPages: 1,
        page: 0,
        size: 20,
      });

      await act(async () => {
        await useClubsStore.getState().fetchClubs({ page: '0' });
      });

      const state = useClubsStore.getState();
      expect(state.clubs).toHaveLength(2);
      expect(state.clubs[0].id).toBe('new1');
    });
  });

  describe('fetchMyClubs', () => {
    it('replaces myClubs array on success', async () => {
      const memberships = [
        makeMembership({ id: 'mem-1', clubId: 'club-1' }),
        makeMembership({ id: 'mem-2', clubId: 'club-2', role: 'organizer' }),
      ];
      mockGetMyClubs.mockResolvedValue(memberships);

      await act(async () => {
        await useClubsStore.getState().fetchMyClubs();
      });

      const state = useClubsStore.getState();
      expect(state.loading).toBe(false);
      expect(state.myClubs).toHaveLength(2);
      expect(state.myClubs[0].clubId).toBe('club-1');
      expect(state.myClubs[1].role).toBe('organizer');
      expect(state.error).toBeNull();
    });

    it('completely replaces old myClubs with new data', async () => {
      // Pre-populate
      useClubsStore.setState({
        myClubs: [makeMembership({ id: 'old-mem' })],
      });

      const newMemberships = [makeMembership({ id: 'new-mem', clubId: 'club-99' })];
      mockGetMyClubs.mockResolvedValue(newMemberships);

      await act(async () => {
        await useClubsStore.getState().fetchMyClubs();
      });

      const state = useClubsStore.getState();
      expect(state.myClubs).toHaveLength(1);
      expect(state.myClubs[0].id).toBe('new-mem');
      expect(state.myClubs[0].clubId).toBe('club-99');
    });

    it('sets error on API failure', async () => {
      mockGetMyClubs.mockRejectedValue(new Error('Unauthorized'));

      await act(async () => {
        await useClubsStore.getState().fetchMyClubs();
      });

      const state = useClubsStore.getState();
      expect(state.loading).toBe(false);
      expect(state.error).toBe('Unauthorized');
    });
  });
});

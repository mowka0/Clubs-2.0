import { create } from 'zustand';
import { getClubs, getMyClubs } from '../api/clubs';
import type { ClubListItemDto, MembershipDto } from '../types/api';
import type { ClubFilters } from '../api/clubs';

interface ClubsState {
  clubs: ClubListItemDto[];
  myClubs: MembershipDto[];
  totalPages: number;
  totalElements: number;
  loading: boolean;
  error: string | null;
  fetchClubs: (filters?: ClubFilters) => Promise<void>;
  fetchMyClubs: () => Promise<void>;
}

export const useClubsStore = create<ClubsState>((set) => ({
  clubs: [],
  myClubs: [],
  totalPages: 0,
  totalElements: 0,
  loading: false,
  error: null,

  fetchClubs: async (filters) => {
    set({ loading: true, error: null });
    try {
      const res = await getClubs(filters);
      const page = Number(filters?.page ?? 0);
      set((state) => ({
        clubs: page === 0 ? res.content : [...state.clubs, ...res.content],
        totalPages: res.totalPages,
        totalElements: res.totalElements,
        loading: false,
      }));
    } catch (e) {
      set({ error: (e as Error).message, loading: false });
    }
  },

  fetchMyClubs: async () => {
    set({ loading: true, error: null });
    try {
      const myClubs = await getMyClubs();
      set({ myClubs, loading: false });
    } catch (e) {
      set({ error: (e as Error).message, loading: false });
    }
  },
}));

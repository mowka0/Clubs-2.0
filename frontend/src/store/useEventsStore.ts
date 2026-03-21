import { create } from 'zustand';
import { getClubEvents, getEvent } from '../api/events';
import type { EventDetailDto, EventListItemDto } from '../types/api';

interface EventsState {
  eventsByClub: Record<string, EventListItemDto[]>;
  currentEvent: EventDetailDto | null;
  loading: boolean;
  error: string | null;
  fetchClubEvents: (clubId: string, params?: { status?: string; page?: string; size?: string }) => Promise<void>;
  fetchEvent: (eventId: string) => Promise<void>;
}

export const useEventsStore = create<EventsState>((set) => ({
  eventsByClub: {},
  currentEvent: null,
  loading: false,
  error: null,

  fetchClubEvents: async (clubId, params) => {
    set({ loading: true, error: null });
    try {
      const res = await getClubEvents(clubId, params);
      set((state) => ({
        eventsByClub: { ...state.eventsByClub, [clubId]: res.content },
        loading: false,
      }));
    } catch (e) {
      set({ error: (e as Error).message, loading: false });
    }
  },

  fetchEvent: async (eventId) => {
    set({ loading: true, error: null });
    try {
      const event = await getEvent(eventId);
      set({ currentEvent: event, loading: false });
    } catch (e) {
      set({ error: (e as Error).message, loading: false });
    }
  },
}));

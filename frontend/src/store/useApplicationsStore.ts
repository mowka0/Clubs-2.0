import { create } from 'zustand';
import { getMyApplications } from '../api/membership';
import type { ApplicationDto } from '../api/membership';

interface ApplicationsState {
  applications: ApplicationDto[];
  loading: boolean;
  error: string | null;
  fetchMyApplications: () => Promise<void>;
}

export const useApplicationsStore = create<ApplicationsState>((set) => ({
  applications: [],
  loading: false,
  error: null,

  fetchMyApplications: async () => {
    set({ loading: true, error: null });
    try {
      const applications = await getMyApplications();
      set({ applications, loading: false });
    } catch (e) {
      set({ error: (e as Error).message, loading: false });
    }
  },
}));

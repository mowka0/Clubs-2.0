import { create } from 'zustand';
import { apiClient } from '../api/apiClient';

export interface UserDto {
  id: string;
  telegramId: number;
  telegramUsername: string | null;
  firstName: string;
  lastName: string | null;
  avatarUrl: string | null;
  city: string | null;
}

interface AuthState {
  user: UserDto | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
  login: () => Promise<void>;
  logout: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  isAuthenticated: false,
  isLoading: false,
  error: null,

  login: async () => {
    set({ isLoading: true, error: null });
    try {
      const data = await apiClient.authenticate();
      set({
        user: data.user as UserDto,
        isAuthenticated: true,
        isLoading: false,
      });
    } catch (e) {
      set({ error: (e as Error).message, isLoading: false });
    }
  },

  logout: () => {
    apiClient.clearToken();
    set({ user: null, isAuthenticated: false });
  },
}));

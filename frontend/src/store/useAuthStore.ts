import { create } from 'zustand';
import { apiClient } from '../api/apiClient';
import type { UserDto } from '../types/api';

// Тип профиля — один на всё приложение (types/api). Своей копии интерфейса здесь нет
// намеренно: два описания одного ответа расходятся при первом же новом поле.
export type { UserDto };

interface AuthState {
  user: UserDto | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
  login: () => Promise<void>;
  logout: () => void;
  setUser: (user: UserDto) => void;
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

  setUser: (user) => set({ user }),
}));

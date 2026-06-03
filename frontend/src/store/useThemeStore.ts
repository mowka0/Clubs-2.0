import { create } from 'zustand';

export type ThemeMode = 'system' | 'light' | 'dark';
export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'clubs-theme-mode';
/** Cycle order for the manual toggle: follow Telegram → light → dark → … */
export const THEME_MODES: readonly ThemeMode[] = ['system', 'light', 'dark'];

interface TelegramColorScheme {
  Telegram?: { WebApp?: { colorScheme?: 'light' | 'dark' } };
}

/** Resolve the theme the host (Telegram client, else OS) currently prefers. */
function systemTheme(): Theme {
  const tg = (window as unknown as TelegramColorScheme).Telegram?.WebApp?.colorScheme;
  if (tg === 'light' || tg === 'dark') return tg;
  if (typeof window.matchMedia === 'function') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  return 'dark';
}

function readMode(): ThemeMode {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    if (v === 'system' || v === 'light' || v === 'dark') return v;
  } catch {
    // localStorage unavailable (privacy mode) — fall through to default
  }
  return 'system';
}

function resolve(mode: ThemeMode): Theme {
  return mode === 'system' ? systemTheme() : mode;
}

interface ThemeState {
  mode: ThemeMode;
  theme: Theme;
  /** Set an explicit mode and persist it. */
  setMode: (mode: ThemeMode) => void;
  /** Advance to the next mode in THEME_MODES (used by the profile toggle). */
  cycle: () => void;
  /** Re-resolve the active theme when mode === 'system' and the host theme changed. */
  syncSystem: () => void;
}

const initialMode = readMode();

export const useThemeStore = create<ThemeState>((set, get) => ({
  mode: initialMode,
  theme: resolve(initialMode),

  setMode: (mode) => {
    try {
      localStorage.setItem(STORAGE_KEY, mode);
    } catch {
      // ignore persistence failure — in-memory state still applies
    }
    set({ mode, theme: resolve(mode) });
  },

  cycle: () => {
    const idx = THEME_MODES.indexOf(get().mode);
    const next = THEME_MODES[(idx + 1) % THEME_MODES.length];
    get().setMode(next);
  },

  syncSystem: () => {
    if (get().mode !== 'system') return;
    const next = systemTheme();
    if (next !== get().theme) set({ theme: next });
  },
}));

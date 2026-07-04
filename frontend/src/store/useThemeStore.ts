import { create } from 'zustand';

export type ThemeMode = 'system' | 'light' | 'dark';
export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'clubs-theme-mode';
/** Порядок циклического переключения: следовать за Telegram → light → dark → … */
export const THEME_MODES: readonly ThemeMode[] = ['system', 'light', 'dark'];

interface TelegramColorScheme {
  Telegram?: { WebApp?: { colorScheme?: 'light' | 'dark' } };
}

/** Определяет тему, которую сейчас предпочитает хост (клиент Telegram, иначе — ОС). */
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
    // localStorage недоступен (приватный режим) — переходим к значению по умолчанию
  }
  return 'system';
}

function resolve(mode: ThemeMode): Theme {
  return mode === 'system' ? systemTheme() : mode;
}

interface ThemeState {
  mode: ThemeMode;
  theme: Theme;
  /** Устанавливает явный режим и сохраняет его. */
  setMode: (mode: ThemeMode) => void;
  /** Переходит к следующему режиму в THEME_MODES (используется переключателем в профиле). */
  cycle: () => void;
  /** Пересчитывает активную тему, когда mode === 'system' и тема хоста изменилась. */
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
      // игнорируем сбой сохранения — состояние в памяти всё равно применяется
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

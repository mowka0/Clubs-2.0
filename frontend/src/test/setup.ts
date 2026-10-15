import '@testing-library/jest-dom';
import { vi } from 'vitest';

// Журнал событий в тестах выключен: компоненты зовут track(), а пачки никуда не должны уходить.
// Тесты самого журнала включают его обратно через vi.stubEnv.
vi.stubEnv('VITE_ANALYTICS_DISABLED', 'true');

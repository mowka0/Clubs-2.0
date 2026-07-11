import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { LocationPickerSheet } from '../../components/event/LocationPickerSheet';

// Хаптика в jsdom недоступна — мокаем хук целиком.
vi.mock('../../hooks/useHaptic', () => ({
  useHaptic: () => ({ impact: vi.fn(), notify: vi.fn() }),
}));

// Реальная карта в jsdom не поднимется — стабим только создание карты (reject = CDN
// недоступен), остальной модуль (URL-билдеры и т.п.) остаётся настоящим.
vi.mock('../../utils/yandexMaps', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../utils/yandexMaps')>();
  return {
    ...actual,
    createPickerMap: vi.fn(() => Promise.reject(new Error('CDN down'))),
  };
});

describe('LocationPickerSheet — fail-closed при недоступности карт (AC-5)', () => {
  it('CDN не загрузился: сообщение «Карты недоступны», «Готово» и поиск неактивны', async () => {
    render(<LocationPickerSheet initial={null} onSelect={vi.fn()} onClose={vi.fn()} />);

    expect(await screen.findByText('Карты недоступны, попробуйте позже')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Готово' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Найти адрес' })).toBeDisabled();
    // «Отмена» остаётся доступной — из шита всегда можно выйти
    expect(screen.getByRole('button', { name: 'Отмена' })).toBeEnabled();
  });
});

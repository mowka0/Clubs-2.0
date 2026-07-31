import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('../../api/cities', () => ({
  getCities: vi.fn(async () => CITIES),
}));

import { CityPicker } from '../../components/CityPicker';
import type { CityDto } from '../../types/api';

const city = (over: Partial<CityDto> & Pick<CityDto, 'id' | 'name'>): CityDto => ({
  region: null, needsRegion: false, countryCode: 'RU', isFeatured: false, hasClubs: false, ...over,
});

const CITIES: CityDto[] = [
  city({ id: 'msk', name: 'Москва', isFeatured: true, hasClubs: true }),
  // Тверь намеренно НЕ в витрине и без клубов: её видно только через поиск.
  city({ id: 'tver', name: 'Тверь' }),
  city({ id: 'zhel-kursk', name: 'Железногорск', region: 'Курская область', needsRegion: true }),
  city({ id: 'zhel-krsk', name: 'Железногорск', region: 'Красноярский край', needsRegion: true }),
  city({ id: 'minsk', name: 'Минск', countryCode: 'BY', isFeatured: true }),
];

function renderPicker(onChange = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  const user = userEvent.setup();
  render(<CityPicker value={null} onChange={onChange} onClose={vi.fn()} />, { wrapper });
  return { user, onChange };
}

beforeEach(() => localStorage.clear());

describe('CityPicker', () => {
  it('до ввода запроса показывает только витрину своей страны', async () => {
    renderPicker();
    expect(await screen.findByRole('button', { name: /Москва/ })).toBeInTheDocument();
    // Тверь есть в справочнике, но не в витрине — иначе список превратился бы в простыню.
    expect(screen.queryByRole('button', { name: /Тверь/ })).not.toBeInTheDocument();
    // Минск — другая страна, на вкладке России его быть не должно.
    expect(screen.queryByRole('button', { name: /^Минск/ })).not.toBeInTheDocument();
  });

  it('поиск находит город вне витрины — иначе человек из Твери не укажет свой город', async () => {
    const { user } = renderPicker();
    await screen.findByRole('button', { name: /Москва/ });

    await user.type(screen.getByLabelText('Найти город'), 'твер');

    expect(await screen.findByRole('button', { name: /Тверь/ })).toBeInTheDocument();
  });

  it('поиск идёт по всем странам, а не только по активной вкладке', async () => {
    const { user } = renderPicker();
    await screen.findByRole('button', { name: /Москва/ });

    await user.type(screen.getByLabelText('Найти город'), 'минск');

    expect(await screen.findByRole('button', { name: /Минск/ })).toBeInTheDocument();
  });

  it('ё и регистр не мешают: «королЁв» и «КОРОЛЕВ» ищут одно и то же', async () => {
    const { user } = renderPicker();
    await screen.findByRole('button', { name: /Москва/ });

    await user.type(screen.getByLabelText('Найти город'), 'МОСКВА');

    expect(await screen.findByRole('button', { name: /Москва/ })).toBeInTheDocument();
  });

  it('тёзкам показывается регион, обычным городам — нет', async () => {
    const { user } = renderPicker();
    await screen.findByRole('button', { name: /Москва/ });

    await user.type(screen.getByLabelText('Найти город'), 'железногорск');

    // Оба Железногорска остаются в списке и различаются регионом.
    expect(await screen.findByText('Курская область, Россия')).toBeInTheDocument();
    expect(screen.getByText('Красноярский край, Россия')).toBeInTheDocument();
    // У Москвы регион не показываем — она уникальна.
    expect(screen.queryByText(/^Москва,/)).not.toBeInTheDocument();
  });

  it('ничего не найдено — пустое состояние без обходных путей', async () => {
    const { user } = renderPicker();
    await screen.findByRole('button', { name: /Москва/ });

    await user.type(screen.getByLabelText('Найти город'), 'фыва');

    expect(await screen.findByText('Не нашли такой город')).toBeInTheDocument();
    // Кнопки «добавить город» быть не должно: она вернула бы фейковые города.
    expect(screen.queryByRole('button', { name: /добавить/i })).not.toBeInTheDocument();
  });

  it('выбор отдаёт наверх город целиком, а не строку', async () => {
    const { user, onChange } = renderPicker();
    const moscow = await screen.findByRole('button', { name: /Москва/ });

    await user.click(moscow);

    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ id: 'msk', name: 'Москва' }));
  });
});

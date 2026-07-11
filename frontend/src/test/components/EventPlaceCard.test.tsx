import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { EventPlaceCard } from '../../components/event/EventPlaceCard';

// Хаптика в jsdom недоступна — мокаем хук целиком.
vi.mock('../../hooks/useHaptic', () => ({
  useHaptic: () => ({ impact: vi.fn(), notify: vi.fn() }),
}));

const POINT = { lat: 55.761216, lon: 37.646488 };

describe('EventPlaceCard (event-geo, кадр C)', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_YANDEX_STATIC_API_KEY', 'test-static-key');
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  it('renders address, hint, static map and both route buttons', () => {
    render(
      <EventPlaceCard
        locationText="ул. Покровка, 47/24с1, Москва"
        locationHint="Вход со двора, домофон 12"
        point={POINT}
      />,
    );

    expect(screen.getByText('ул. Покровка, 47/24с1, Москва')).toBeInTheDocument();
    expect(screen.getByText('Вход со двора, домофон 12')).toBeInTheDocument();
    const img = screen.getByAltText('Карта места события');
    expect(img).toHaveAttribute('src', expect.stringContaining('static-maps.yandex.ru'));
    expect(screen.getByRole('button', { name: /Маршрут/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Открыть в Картах' })).toBeInTheDocument();
  });

  it('«Маршрут» opens Yandex Maps deep-link with lat,lon route target', () => {
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
    render(<EventPlaceCard locationText="Адрес" locationHint={null} point={POINT} />);

    fireEvent.click(screen.getByRole('button', { name: /Маршрут/ }));

    expect(openSpy).toHaveBeenCalledWith(
      'https://yandex.ru/maps/?rtext=~55.761216,37.646488',
      '_blank',
      'noopener,noreferrer',
    );
  });

  it('hides the map image (but keeps address and buttons) when Static API fails', () => {
    render(<EventPlaceCard locationText="Адрес" locationHint={null} point={POINT} />);

    fireEvent.error(screen.getByAltText('Карта места события'));

    expect(screen.queryByAltText('Карта места события')).not.toBeInTheDocument();
    expect(screen.getByText('Адрес')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Маршрут/ })).toBeInTheDocument();
  });

  it('does not render the hint row when hint is null', () => {
    render(<EventPlaceCard locationText="Адрес" locationHint={null} point={POINT} />);

    const addressBlock = screen.getByText('Адрес').parentElement;
    expect(addressBlock?.querySelectorAll('span:not([aria-hidden])')).toHaveLength(0);
  });
});

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  geocode,
  openMapUrl,
  reverseGeocode,
  routeUrl,
  staticMapUrl,
} from '../../utils/yandexMaps';

// Тестовая точка: Покровка (Москва). Главная ловушка форматов Яндекса — порядок координат:
// в ll/pt/geocode — lon,lat; в rtext — lat,lon. Тесты фиксируют оба порядка.
const POINT = { lat: 55.761216, lon: 37.646488 };

describe('yandexMaps URL builders', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_YANDEX_MAPS_API_KEY', 'test-maps-key');
    vi.stubEnv('VITE_YANDEX_STATIC_API_KEY', 'test-static-key');
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  it('staticMapUrl uses lon,lat order in ll and pt', () => {
    const url = staticMapUrl(POINT);
    expect(url).toContain('https://static-maps.yandex.ru/v1');
    expect(url).toContain('apikey=test-static-key');
    expect(url).toContain('ll=37.646488,55.761216');
    expect(url).toContain('pt=37.646488,55.761216,pm2rdm');
  });

  it('routeUrl uses lat,lon order in rtext (route from current position)', () => {
    expect(routeUrl(POINT)).toBe('https://yandex.ru/maps/?rtext=~55.761216,37.646488');
  });

  it('openMapUrl uses lon,lat order in pt', () => {
    expect(openMapUrl(POINT)).toBe('https://yandex.ru/maps/?pt=37.646488,55.761216&z=17');
  });
});

/** Ответ НАШЕГО прокси `/api/geo/geocode` (Яндекс-формат разбирается на бэкенде). */
function proxyResponse(address: string, lat: number, lon: number) {
  return new Response(JSON.stringify({ address, lat, lon }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('yandexMaps geocoder (через прокси бэкенда)', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  it('geocode ходит в НАШ бэкенд, а не в Яндекс — ключа на клиенте больше нет', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      proxyResponse('Россия, Москва, улица Покровка, 47/24с1', 55.761216, 37.646488),
    );

    const result = await geocode('Покровка 47');

    expect(result).toEqual({
      point: { lat: 55.761216, lon: 37.646488 },
      address: 'Россия, Москва, улица Покровка, 47/24с1',
    });
    const requestedUrl = String(fetchMock.mock.calls[0]?.[0]);
    expect(requestedUrl).toContain('/api/geo/geocode');
    // Сравниваем разобранный параметр, а не сырую строку: URLSearchParams кодирует пробел
    // как «+», а encodeURIComponent — как «%20», и дословное сравнение врало бы.
    expect(new URL(requestedUrl).searchParams.get('q')).toBe('Покровка 47');
    // Главное: наружу в Яндекс с клиента не ходим и ключ не светим.
    expect(requestedUrl).not.toContain('geocode-maps.yandex.ru');
    expect(requestedUrl).not.toContain('apikey');
  });

  it('204 от бэкенда = адрес не найден → null', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 204 }));

    expect(await geocode('абракадабра')).toBeNull();
  });

  it('503 (геокодер недоступен) пробрасывается как GeocoderHttpError — пикер не молчит', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ error: 'GEOCODER_UNAVAILABLE', message: 'нет' }), { status: 503 }),
    );

    await expect(geocode('Покровка 47')).rejects.toThrow('Geocoder HTTP 503');
  });

  it('reverseGeocode шлёт «lon,lat» тем же прокси и возвращает адрес', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      proxyResponse('Россия, Москва, улица Покровка, 47/24с1', 55.761216, 37.646488),
    );

    const address = await reverseGeocode(POINT);

    expect(address).toBe('Россия, Москва, улица Покровка, 47/24с1');
    const requestedUrl = String(fetchMock.mock.calls[0]?.[0]);
    expect(requestedUrl).toContain('/api/geo/geocode');
    expect(new URL(requestedUrl).searchParams.get('q')).toBe('37.646488,55.761216');
  });
});

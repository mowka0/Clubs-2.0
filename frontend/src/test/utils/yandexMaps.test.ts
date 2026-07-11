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

function geocoderResponse(pos: string | null, address: string | null) {
  return {
    response: {
      GeoObjectCollection: {
        featureMember: pos === null && address === null ? [] : [
          {
            GeoObject: {
              Point: pos === null ? undefined : { pos },
              metaDataProperty: address === null ? undefined : { GeocoderMetaData: { text: address } },
            },
          },
        ],
      },
    },
  };
}

describe('yandexMaps geocoder', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_YANDEX_MAPS_API_KEY', 'test-maps-key');
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  it('geocode parses "lon lat" pos and address text', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify(geocoderResponse('37.646488 55.761216', 'Россия, Москва, улица Покровка, 47/24с1'))),
    );

    const result = await geocode('Покровка 47');

    expect(result).toEqual({
      point: { lat: 55.761216, lon: 37.646488 },
      address: 'Россия, Москва, улица Покровка, 47/24с1',
    });
    const requestedUrl = String(fetchMock.mock.calls[0]?.[0]);
    expect(requestedUrl).toContain('https://geocode-maps.yandex.ru/1.x/');
    expect(requestedUrl).toContain('apikey=test-maps-key');
    expect(requestedUrl).toContain(`geocode=${encodeURIComponent('Покровка 47')}`);
    expect(requestedUrl).toContain('results=1');
  });

  it('geocode returns null when nothing is found', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify(geocoderResponse(null, null))),
    );

    expect(await geocode('абракадабра')).toBeNull();
  });

  it('geocode throws on HTTP error (лимит/ключ) — пикер покажет ошибку, не молчит', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('forbidden', { status: 403 }));

    await expect(geocode('Покровка 47')).rejects.toThrow('Geocoder HTTP 403');
  });

  it('reverseGeocode requests lon,lat and returns the address', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify(geocoderResponse('37.646488 55.761216', 'Россия, Москва, улица Покровка, 47/24с1'))),
    );

    const address = await reverseGeocode(POINT);

    expect(address).toBe('Россия, Москва, улица Покровка, 47/24с1');
    const requestedUrl = String(fetchMock.mock.calls[0]?.[0]);
    expect(requestedUrl).toContain(`geocode=${encodeURIComponent('37.646488,55.761216')}`);
  });
});

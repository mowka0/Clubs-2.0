import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  GeocoderHttpError,
  geocode,
  openMapUrl,
  resolveSuggestion,
  reverseGeocode,
  routeUrl,
  staticMapUrl,
  suggestPlaces,
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

/** Ответ НАШЕГО прокси `/api/geo/suggest`. `results` — то, что отдал бэкенд, как есть. */
function suggestResponse(results: unknown) {
  return new Response(JSON.stringify({ results }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** Ошибка гео-эндпоинта в формате GlobalExceptionHandler (503 = сервис недоступен). */
function serviceUnavailable() {
  return new Response(JSON.stringify({ error: 'SUGGEST_UNAVAILABLE', message: 'нет' }), {
    status: 503,
  });
}

// Подсказка заведения — эталон из спеки venue-search § «Контракт API».
const OLD_FRIENDS_RAW = {
  title: 'Old Friends',
  subtitle: 'Кальян-бар · Иваново, Пограничный переулок, 62',
  address: 'Иваново, Пограничный переулок, 62',
  kind: 'business',
  uri: 'ymapsbm1://org?oid=1024394521',
};

describe('yandexMaps подсказки мест (Геосаджест через прокси бэкенда)', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  it('нормальный ответ → список подсказок; clubId уходит в запрос, ключ — нет', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      suggestResponse([OLD_FRIENDS_RAW]),
    );

    const found = await suggestPlaces('Иваново, Old Friends', 'club-42');

    expect(found).toEqual([
      {
        title: 'Old Friends',
        subtitle: 'Кальян-бар · Иваново, Пограничный переулок, 62',
        address: 'Иваново, Пограничный переулок, 62',
        kind: 'business',
        uri: 'ymapsbm1://org?oid=1024394521',
      },
    ]);
    const requestedUrl = String(fetchMock.mock.calls[0]?.[0]);
    expect(requestedUrl).toContain('/api/geo/suggest');
    expect(new URL(requestedUrl).searchParams.get('q')).toBe('Иваново, Old Friends');
    // Клуб сужает выдачу до своего города — координаты города достаёт бэкенд сам.
    expect(new URL(requestedUrl).searchParams.get('clubId')).toBe('club-42');
    // AC-9: ключ Геосаджеста живёт в env бэкенда, наружу с клиента не ходим.
    expect(requestedUrl).not.toContain('suggest-maps.yandex.ru');
    expect(requestedUrl).not.toContain('apikey');
  });

  it('без clubId параметра в запросе нет — ищем без городской рамки', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(suggestResponse([]));

    await suggestPlaces('Иваново, Old Friends');

    expect(new URL(String(fetchMock.mock.calls[0]?.[0])).searchParams.has('clubId')).toBe(false);
  });

  it('пустая выдача — это [], а не ошибка (списочный эндпоинт 204 не отдаёт)', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(suggestResponse([]));

    expect(await suggestPlaces('Блаблабла Пыщь')).toEqual([]);
  });

  it('results не массив или поля нет → [], внешнему ответу на слово не верим', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch');

    fetchMock.mockResolvedValue(suggestResponse({ oops: 1 }));
    expect(await suggestPlaces('Иваново')).toEqual([]);

    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({}), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    expect(await suggestPlaces('Иваново')).toEqual([]);
  });

  it('мусорные строки отбрасываются, незнакомый kind схлопывается в other', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      suggestResponse([
        null,
        42,
        'строка вместо объекта',
        // Без uri координату не достать — строка мертва.
        { title: 'Без uri', address: 'Иваново', kind: 'business' },
        // Пустое название показывать нечем.
        { title: '', uri: 'ymapsbm1://org?oid=2', kind: 'business' },
        // Незнакомый kind не должен ронять всю выдачу; subtitle/address отсутствуют.
        { title: 'Пограничный переулок, 62', uri: 'ymapsbm1://geo?ll=40.97,57.01', kind: 'district' },
        OLD_FRIENDS_RAW,
      ]),
    );

    const found = await suggestPlaces('Иваново');

    expect(found).toHaveLength(2);
    expect(found[0]).toEqual({
      title: 'Пограничный переулок, 62',
      subtitle: '',
      address: '',
      kind: 'other',
      uri: 'ymapsbm1://geo?ll=40.97,57.01',
    });
    expect(found[1]?.title).toBe('Old Friends');
  });

  it('503 (саджест недоступен) → GeocoderHttpError: пикер отличит его от обрыва связи', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(serviceUnavailable());

    await expect(suggestPlaces('Иваново, Old Friends')).rejects.toBeInstanceOf(GeocoderHttpError);
  });
});

describe('yandexMaps resolveSuggestion (координаты выбранной подсказки)', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  it('успех → точка; uri уходит в запрос как есть (разбирать его на клиенте нельзя)', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      proxyResponse('Россия, Ивановская область, Иваново, Old Friends', 57.01283, 40.972935),
    );

    const result = await resolveSuggestion('ymapsbm1://org?oid=1024394521');

    expect(result).toEqual({
      point: { lat: 57.01283, lon: 40.972935 },
      address: 'Россия, Ивановская область, Иваново, Old Friends',
    });
    const requestedUrl = String(fetchMock.mock.calls[0]?.[0]);
    expect(requestedUrl).toContain('/api/geo/resolve');
    expect(new URL(requestedUrl).searchParams.get('uri')).toBe('ymapsbm1://org?oid=1024394521');
  });

  it('204 (uri не разрешился) → null, а не исключение', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 204 }));

    expect(await resolveSuggestion('ymapsbm1://org?oid=1')).toBeNull();
  });

  it('503 → GeocoderHttpError', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(serviceUnavailable());

    await expect(resolveSuggestion('ymapsbm1://org?oid=1')).rejects.toBeInstanceOf(GeocoderHttpError);
  });
});

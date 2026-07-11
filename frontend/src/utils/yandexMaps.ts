/**
 * Изоляция провайдера карт (Яндекс) для фичи event-geo: ВЕСЬ Яндекс-специфичный код живёт
 * здесь и в LocationPickerSheet — смена провайдера (2ГИС/OSM) не трогает страницы.
 *
 * Бюджет бесплатного тарифа ~100 запросов/сутки на сервис, поэтому:
 *  - геокодинг вызывается только по кнопке «Найти» (никакого live-саджеста);
 *  - обратный геокодинг — один раз по «Готово» в пикере;
 *  - JS API грузится лениво, только при открытии пикера.
 *
 * ⚠️ Ловушка форматов Яндекса: в `ll`/`pt`/`geocode` порядок координат **lon,lat**,
 * а в `rtext` (маршрут) — **lat,lon**.
 */

// Ключи читаются лениво (не в module scope): vitest может застабить их через vi.stubEnv
// уже после импорта модуля.
// Ключ JavaScript API v3; по связке «JavaScript API и HTTP Геокодер» им же ходим в геокодер.
function mapsApiKey(): string | undefined {
  return import.meta.env.VITE_YANDEX_MAPS_API_KEY;
}
// Отдельный ключ Static API — картинка мини-карты на странице события.
function staticApiKey(): string | undefined {
  return import.meta.env.VITE_YANDEX_STATIC_API_KEY;
}

// Дефолт-центр пикера, пока точка не выбрана (решение мокапа): Москва.
export const DEFAULT_CENTER: GeoPoint = { lat: 55.751244, lon: 37.618423 };
// Зум пикера: обзорный по умолчанию и ближний после нахождения адреса/повторного открытия.
export const PICKER_ZOOM_DEFAULT = 12;
export const PICKER_ZOOM_FOCUSED = 16;

export interface GeoPoint {
  lat: number;
  lon: number;
}

export interface GeocodeResult {
  point: GeoPoint;
  address: string;
}

/** Координаты ymaps3: [lon, lat] — именно в этом порядке. */
type LngLat = [number, number];

interface Ymaps3MapLocation {
  center?: LngLat;
  zoom?: number;
  duration?: number;
}

export interface Ymaps3Map {
  center: Readonly<LngLat>;
  setLocation(location: Ymaps3MapLocation): void;
  addChild(child: unknown): void;
  destroy(): void;
}

export interface Ymaps3Api {
  ready: Promise<unknown>;
  YMap: new (root: HTMLElement, props: { location: { center: LngLat; zoom: number } }) => Ymaps3Map;
  YMapDefaultSchemeLayer: new (props?: Record<string, unknown>) => unknown;
}

declare global {
  interface Window {
    ymaps3?: Ymaps3Api;
  }
}

// Кэш промиса загрузки скрипта: пикер может открываться много раз, скрипт грузим однажды.
// При ошибке кэш сбрасывается, чтобы повторное открытие пикера могло ретраить загрузку.
let ymaps3Promise: Promise<Ymaps3Api> | null = null;

/** Ленивый лоадер Yandex JS API v3. Бросает Error, если CDN недоступен или ключ не задан. */
export function loadYmaps3(): Promise<Ymaps3Api> {
  if (window.ymaps3) {
    const api = window.ymaps3;
    return api.ready.then(() => api);
  }
  if (!ymaps3Promise) {
    ymaps3Promise = new Promise<Ymaps3Api>((resolve, reject) => {
      const apiKey = mapsApiKey();
      if (!apiKey) {
        reject(new Error('VITE_YANDEX_MAPS_API_KEY is not set'));
        return;
      }
      const script = document.createElement('script');
      script.src = `https://api-maps.yandex.ru/v3/?apikey=${encodeURIComponent(apiKey)}&lang=ru_RU`;
      script.async = true;
      script.onload = () => {
        const api = window.ymaps3;
        if (!api) {
          reject(new Error('ymaps3 global is missing after script load'));
          return;
        }
        api.ready.then(() => resolve(api), reject);
      };
      script.onerror = () => reject(new Error('Failed to load Yandex Maps JS API'));
      document.head.appendChild(script);
    });
    ymaps3Promise.catch(() => { ymaps3Promise = null; });
  }
  return ymaps3Promise;
}

// Предел ожидания ответа геокодера, мс.
const GEOCODER_TIMEOUT_MS = 10_000;

/** HTTP-ошибка геокодера (4xx = лимит/ключ, а не сеть) — пикер различает текст сообщения. */
export class GeocoderHttpError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Geocoder HTTP ${status}`);
    this.name = 'GeocoderHttpError';
    this.status = status;
  }
}

/** Форма ответа HTTP-геокодера 1.x (все поля защитно-опциональны — внешнее API). */
interface GeocoderResponse {
  response?: {
    GeoObjectCollection?: {
      featureMember?: Array<{
        GeoObject?: {
          Point?: { pos?: string };
          metaDataProperty?: { GeocoderMetaData?: { text?: string } };
        };
      }>;
    };
  };
}

async function requestGeocoder(geocodeParam: string): Promise<GeocodeResult | null> {
  const apiKey = mapsApiKey();
  if (!apiKey) throw new Error('VITE_YANDEX_MAPS_API_KEY is not set');
  const url =
    'https://geocode-maps.yandex.ru/1.x/' +
    `?apikey=${encodeURIComponent(apiKey)}` +
    `&geocode=${encodeURIComponent(geocodeParam)}` +
    '&format=json&results=1&lang=ru_RU';
  // Таймаут: зависший fetch не должен держать пикер в «Сохраняем…» до браузерного дефолта.
  // AbortSignal.timeout недоступен в старых webview (и jsdom) — тогда едем без таймаута.
  const signal = typeof AbortSignal.timeout === 'function'
    ? AbortSignal.timeout(GEOCODER_TIMEOUT_MS)
    : undefined;
  const res = await fetch(url, { signal });
  if (!res.ok) throw new GeocoderHttpError(res.status);
  const data = (await res.json()) as GeocoderResponse;
  const geoObject = data.response?.GeoObjectCollection?.featureMember?.[0]?.GeoObject;
  const pos = geoObject?.Point?.pos; // "lon lat" через пробел
  const address = geoObject?.metaDataProperty?.GeocoderMetaData?.text;
  if (!pos || !address) return null;
  const [lonStr, latStr] = pos.split(' ');
  const lon = Number(lonStr);
  const lat = Number(latStr);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  return { point: { lat, lon }, address };
}

/** Прямой геокодинг «адрес → точка». null = ничего не найдено; ошибки сети/HTTP летят наружу. */
export function geocode(query: string): Promise<GeocodeResult | null> {
  return requestGeocoder(query);
}

/** Обратный геокодинг «точка → адрес». null = адрес не определился. */
export async function reverseGeocode(point: GeoPoint): Promise<string | null> {
  const result = await requestGeocoder(`${point.lon},${point.lat}`);
  return result?.address ?? null;
}

/** URL статичной мини-карты с пином (Static API). Порядок в ll/pt — lon,lat. */
export function staticMapUrl(point: GeoPoint): string {
  return (
    'https://static-maps.yandex.ru/v1' +
    `?apikey=${encodeURIComponent(staticApiKey() ?? '')}` +
    `&ll=${point.lon},${point.lat}&z=16&size=650,300` +
    `&pt=${point.lon},${point.lat},pm2rdm`
  );
}

/** Deep-link Яндекс.Карт «маршрут от текущего местоположения до точки» (бесключевой). rtext — lat,lon. */
export function routeUrl(point: GeoPoint): string {
  return `https://yandex.ru/maps/?rtext=~${point.lat},${point.lon}`;
}

/** Deep-link Яндекс.Карт «открыть точку» (бесключевой). pt — lon,lat. */
export function openMapUrl(point: GeoPoint): string {
  return `https://yandex.ru/maps/?pt=${point.lon},${point.lat}&z=17`;
}

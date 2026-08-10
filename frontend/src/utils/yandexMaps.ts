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


import { ApiError, apiClient } from '../api/apiClient';
// Ключи читаются лениво (не в module scope): vitest может застабить их через vi.stubEnv
// уже после импорта модуля.
// Ключ продукта «JavaScript API» (v2.1) — только скрипт карты.
function mapsApiKey(): string | undefined {
  return import.meta.env.VITE_YANDEX_MAPS_API_KEY;
}
// Отдельный ключ Static API — картинка мини-карты на странице события.
function staticApiKey(): string | undefined {
  return import.meta.env.VITE_YANDEX_STATIC_API_KEY;
}
// Ключа геокодера на клиенте БОЛЬШЕ НЕТ: с 2026-08-05 геокодинг проксирует бэкенд, ключ
// живёт в его env (VITE_-переменные публичны и попадают в бандл — см. requestGeocoder).

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

// ---- JS API v2.1 ----
// Не v3 (ymaps3): в кабинете PO продукты раздельные («JavaScript API» = v2.1 и «API
// Геокодера»), связки «JavaScript API и HTTP Геокодер», чьи ключи принимает v3, там нет —
// v3 отвечает 403 «Invalid api key» (проверено curl 2026-07-11), а v2.1 тем же ключом работает.
// ⚠️ Порядковая ловушка №2: v2.1 использует [lat, lon] — наоборот от v3 и Static API.
// Наружу порядок не торчит: интерфейс PickerMap оперирует только GeoPoint.

/** Внутренний тип карты v2.1 (минимум, официальные типы не тянем ради одного компонента). */
interface YmapsV2Map {
  getCenter(): [number, number]; // [lat, lon]
  setCenter(center: [number, number], zoom?: number, options?: { duration?: number }): unknown;
  destroy(): void;
}

interface YmapsV2Api {
  ready(callback: () => void): void;
  Map: new (
    root: HTMLElement,
    state: { center: [number, number]; zoom: number; controls: string[] },
  ) => YmapsV2Map;
}

declare global {
  interface Window {
    ymaps?: YmapsV2Api;
  }
}

/** Провайдер-нейтральная карта пикера: только GeoPoint, без координатных порядков Яндекса. */
export interface PickerMap {
  getCenter(): GeoPoint;
  panTo(point: GeoPoint, zoom: number): void;
  destroy(): void;
}

// Кэш промиса загрузки скрипта: пикер может открываться много раз, скрипт грузим однажды.
// При ошибке кэш сбрасывается, чтобы повторное открытие пикера могло ретраить загрузку.
let ymapsPromise: Promise<YmapsV2Api> | null = null;

/** Ленивый лоадер Yandex JS API v2.1. Бросает Error, если CDN недоступен или ключ не задан. */
function loadYmaps(): Promise<YmapsV2Api> {
  if (window.ymaps) {
    const api = window.ymaps;
    return new Promise((resolve) => api.ready(() => resolve(api)));
  }
  if (!ymapsPromise) {
    ymapsPromise = new Promise<YmapsV2Api>((resolve, reject) => {
      const apiKey = mapsApiKey();
      if (!apiKey) {
        reject(new Error('VITE_YANDEX_MAPS_API_KEY is not set'));
        return;
      }
      const script = document.createElement('script');
      script.src = `https://api-maps.yandex.ru/2.1/?apikey=${encodeURIComponent(apiKey)}&lang=ru_RU`;
      script.async = true;
      script.onload = () => {
        const api = window.ymaps;
        if (!api) {
          reject(new Error('ymaps global is missing after script load'));
          return;
        }
        api.ready(() => resolve(api));
      };
      script.onerror = () => reject(new Error('Failed to load Yandex Maps JS API'));
      document.head.appendChild(script);
    });
    ymapsPromise.catch(() => { ymapsPromise = null; });
  }
  return ymapsPromise;
}

/**
 * Создаёт карту пикера в контейнере (лениво грузит JS API) и оборачивает её в
 * провайдер-нейтральный PickerMap. controls: [] — чистая карта, пин рисует сам пикер.
 */
export async function createPickerMap(
  container: HTMLElement,
  center: GeoPoint,
  zoom: number,
): Promise<PickerMap> {
  const api = await loadYmaps();
  const map = new api.Map(container, { center: [center.lat, center.lon], zoom, controls: [] });
  return {
    getCenter() {
      const [lat, lon] = map.getCenter();
      return { lat, lon };
    },
    panTo(point, targetZoom) {
      map.setCenter([point.lat, point.lon], targetZoom, { duration: 300 });
    },
    destroy() {
      map.destroy();
    },
  };
}

// Предел ожидания ответа геокодера, мс.

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
/** Ответ нашего `/api/geo/geocode` (порядок полей привычный — lat/lon, не яндексовский). */
interface GeocodeApiResult {
  address: string;
  lat: number;
  lon: number;
}


/**
 * Геокодинг идёт через НАШ бэкенд, а не напрямую в Яндекс (с 2026-08-05).
 *
 * Прямой поход из браузера означал публичный ключ в бандле и ограничение по HTTP Referer как
 * единственную защиту. На проде это выстрелило трижды: ключ виден любому; поиск лежал, пока
 * домен вносили в кабинет Яндекса (раскатка заняла час); а клиент со старым бандлом в кэше
 * (assets отдаются `immutable` на год) продолжал слать старый ключ, и починить это с сервера
 * было нечем. Теперь ключ живёт только в env бэкенда.
 *
 * Параметр тот же, что понимает Яндекс: адрес строкой для прямого геокодинга либо «lon,lat»
 * для обратного — разбирать его на клиенте не нужно.
 */
async function requestGeocoder(geocodeParam: string): Promise<GeocodeResult | null> {
  try {
    const dto = await apiClient.get<GeocodeApiResult | undefined>('/api/geo/geocode', {
      q: geocodeParam,
    });
    // 204 No Content — адрес не найден; apiClient отдаёт undefined.
    if (!dto) return null;
    if (!Number.isFinite(dto.lat) || !Number.isFinite(dto.lon)) return null;
    return { point: { lat: dto.lat, lon: dto.lon }, address: dto.address };
  } catch (e) {
    // Сохраняем прежний контракт для пикера: HTTP-ошибка (503 «геокодер недоступен») — это
    // не проблема связи, и текст пользователю показывается другой.
    if (e instanceof ApiError) throw new GeocoderHttpError(e.status);
    throw e;
  }
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

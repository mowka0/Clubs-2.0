/**
 * Изоляция провайдера карт (Яндекс) для фичи event-geo: ВЕСЬ Яндекс-специфичный код живёт
 * здесь и в LocationPickerSheet — смена провайдера (2ГИС/OSM) не трогает страницы.
 *
 * Бюджет бесплатного тарифа ~100 запросов/сутки на сервис, поэтому:
 *  - геокодинг вызывается только по кнопке «Найти» (никакого live-саджеста);
 *  - подсказки мест — тоже по кнопке-лупе: 1 поиск = 1 запрос, плюс 1 на выбор строки;
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
      // duration: 0 — БЕЗ анимации, и это требование корректности, а не вкусовщина.
      // Пикер определяет «пользователь не двигал карту после выбора» сравнением getCenter()
      // с выбранной точкой. Пока летит анимация, центр — промежуточная координата: успевший
      // нажать «Готово» получил бы в событие точку, которую никто не выбирал, а название
      // заведения затёрлось бы обратным геокодингом этой середины пути.
      map.setCenter([point.lat, point.lon], targetZoom, { duration: 0 });
    },
    destroy() {
      map.destroy();
    },
  };
}

/** HTTP-ошибка геокодера (4xx = лимит/ключ, а не сеть) — пикер различает текст сообщения. */
export class GeocoderHttpError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Geocoder HTTP ${status}`);
    this.name = 'GeocoderHttpError';
    this.status = status;
  }
}

/** Ответ нашего `/api/geo/geocode` и `/api/geo/resolve` (порядок полей — lat/lon, не яндексовский). */
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
  const dto = await requestGeoApi<GeocodeApiResult>('/api/geo/geocode', { q: geocodeParam });
  return toGeocodeResult(dto);
}

/**
 * Общий поход в гео-эндпоинты бэкенда (`/geocode`, `/suggest`, `/resolve`): различаются они
 * только путём и параметрами, а обработка ошибок у всех одна.
 *
 * HTTP-ошибка заворачивается в GeocoderHttpError — пикер по типу исключения отличает
 * «сервис недоступен» (503, лимит, ключ не настроен) от обрыва связи и показывает разный
 * текст. Всё остальное (сеть, парсинг) летит наружу как есть.
 */
async function requestGeoApi<T>(
  path: string,
  params: Record<string, string>,
): Promise<T | undefined> {
  try {
    // 204 No Content — apiClient отдаёт undefined, тело разбирать не пытается.
    return await apiClient.get<T | undefined>(path, params);
  } catch (e) {
    if (e instanceof ApiError) throw new GeocoderHttpError(e.status);
    throw e;
  }
}

/** Разбор ответа `{address,lat,lon}` в GeocodeResult. null = точки нет (204 или мусор в теле). */
function toGeocodeResult(dto: GeocodeApiResult | undefined): GeocodeResult | null {
  if (!dto) return null;
  if (!Number.isFinite(dto.lat) || !Number.isFinite(dto.lon)) return null;
  // Адрес защитно приводим к строке: координата ценна сама по себе, ронять её из-за
  // отсутствующей подписи незачем — текст в пикере всё равно перекрывается выбранным местом.
  return {
    point: { lat: dto.lat, lon: dto.lon },
    address: typeof dto.address === 'string' ? dto.address : '',
  };
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

// ---- Подсказки мест (Геосаджест через бэкенд) ----
// Геосаджест знает организации, которых нет у геокодера («Old Friends»), но НЕ отдаёт
// координаты — только текст и непрозрачный uri. Поэтому выбор места стоит двух запросов:
// лупа → /suggest (список), тап по строке → /resolve (координаты по uri).
// Ключ Геосаджеста, как и ключ геокодера, живёт в env бэкенда и на клиент не уезжает.

/** Класс подсказки: заведение / дом / улица / всё прочее — пикер по нему выбирает иконку. */
export type SuggestKind = 'business' | 'house' | 'street' | 'other';

// Те же значения в рантайме: kind приходит из внешнего API, и незнакомое значение нужно
// уметь отбросить (→ 'other'), а не верить типу на слово.
const SUGGEST_KINDS: readonly SuggestKind[] = ['business', 'house', 'street', 'other'];

/** Строка списка подсказок. `uri` — идентификатор Яндекса, разбирать его на клиенте нельзя. */
export interface PlaceSuggestion {
  /** Название места: «Old Friends» либо сам адрес, если это дом/улица. */
  title: string;
  /** Пояснение под названием: «Кальян-бар · Иваново, Пограничный переулок, 62». */
  subtitle: string;
  /** Почтовый адрес отдельной строкой — из него собирается location_text события. */
  address: string;
  kind: SuggestKind;
  uri: string;
}

/** Ответ `/api/geo/suggest`. `results` — unknown: содержимое приходит извне и не гарантировано. */
interface SuggestApiResponse {
  results?: unknown;
}

function isSuggestKind(value: unknown): value is SuggestKind {
  return SUGGEST_KINDS.some((kind) => kind === value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/** Валидация одного элемента выдачи. null = строка непригодна и в список не попадает. */
function toPlaceSuggestion(raw: unknown): PlaceSuggestion | null {
  if (!isRecord(raw)) return null;
  const { title, subtitle, address, kind, uri } = raw;
  // Без названия и без uri строка мертва: показать нечего либо по тапу не достать координату.
  // Бэкенд такие отбрасывает сам, но клиент внешнему ответу на слово не верит.
  if (typeof title !== 'string' || title.length === 0) return null;
  if (typeof uri !== 'string' || uri.length === 0) return null;
  return {
    title,
    subtitle: typeof subtitle === 'string' ? subtitle : '',
    address: typeof address === 'string' ? address : '',
    kind: isSuggestKind(kind) ? kind : 'other',
    uri,
  };
}

/**
 * Подсказки по строке. Пустой массив = ничего не нашли (это валидный ответ, а не ошибка).
 * HTTP-ошибка (503 «саджест недоступен») → GeocoderHttpError, как у геокодера.
 *
 * `clubId` сужает поиск до города клуба; координаты города бэкенд достаёт сам — клиенту их
 * не отдают.
 */
export async function suggestPlaces(query: string, clubId?: string): Promise<PlaceSuggestion[]> {
  const params: Record<string, string> = { q: query };
  if (clubId) params.clubId = clubId;
  const dto = await requestGeoApi<SuggestApiResponse>('/api/geo/suggest', params);
  const items = dto?.results;
  if (!Array.isArray(items)) return [];
  return items
    .map(toPlaceSuggestion)
    .filter((suggestion): suggestion is PlaceSuggestion => suggestion !== null);
}

/** Координаты выбранной подсказки по её uri. null = не разрешилось (204). */
export async function resolveSuggestion(uri: string): Promise<GeocodeResult | null> {
  const dto = await requestGeoApi<GeocodeApiResult>('/api/geo/resolve', { uri });
  return toGeocodeResult(dto);
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

import { apiClient, ApiError } from '../api/apiClient';
import { postTrack, type TrackEventBody, type TrackProps } from '../api/track';

/** Виды, которые Mini App вправе писать в журнал (product-analytics.md § 3.2) — whitelist бэкенда `AppEventKind`. */
export type AppEventKind =
  | 'app_opened'
  | 'screen_opened'
  | 'onboarding_step'
  | 'onboarding_completed'
  | 'pay_sheet_opened';

export type { TrackProps };

/** Пачка уходит через столько после первого события в очереди: живая навигация даёт одну пачку, не десять запросов. */
const FLUSH_DELAY_MS = 3000;
/** Потолок пачки — тот же, что принимает сервер (`TrackRequest.MAX_EVENTS`). */
const BATCH_MAX = 20;
/** Очередь не растёт бесконечно без сети: старые события вытесняются. */
const QUEUE_MAX = 100;

interface Batch {
  events: TrackEventBody[];
  /** Пачка уже уходила и вернулась после сбоя сети — второй сбой её выбрасывает. */
  retried: boolean;
}

let queue: TrackEventBody[] = [];
let timer: number | null = null;
let retryBatch: Batch | null = null;
let hideListenerInstalled = false;

/** Выключатель: локальная разработка и тесты не должны слать пачки. Читается на каждом вызове, чтобы тесты могли переключать. */
function isDisabled(): boolean {
  return import.meta.env.VITE_ANALYTICS_DISABLED === 'true';
}

/**
 * Кладёт событие в очередь. Никогда не бросает и ничего не возвращает: вызывающий код не ждёт
 * и не узнаёт о сбое — журнал не должен влиять на интерфейс.
 */
export function track(kind: AppEventKind, props?: TrackProps, clubId?: string | null): void {
  if (isDisabled()) return;
  installFlushOnHide();
  queue.push({ kind, clubId: clubId ?? undefined, props });
  if (queue.length > QUEUE_MAX) queue.splice(0, queue.length - QUEUE_MAX);
  if (queue.length >= BATCH_MAX) {
    void flush(false);
    return;
  }
  if (timer === null) {
    timer = window.setTimeout(() => {
      timer = null;
      void flush(false);
    }, FLUSH_DELAY_MS);
  }
}

/** Сворачивание или закрытие Mini App: отправить накопленное сразу, запросом, который переживёт выгрузку страницы. */
function installFlushOnHide(): void {
  if (hideListenerInstalled || typeof document === 'undefined') return;
  hideListenerInstalled = true;
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') void flush(true);
  });
}

/**
 * Отправляет накопленное: пачки из очереди и, последней, пачку, ждущую повтора. Все запросы уходят
 * разом, без ожидания между ними: при сворачивании WebView может заморозить JS после первого
 * `await`, и свежие события важнее повтора старых. Без токена ничего не уходит — события
 * остаются до авторизации (таймер взведётся заново со следующим событием).
 */
async function flush(keepalive: boolean): Promise<void> {
  if (timer !== null) {
    window.clearTimeout(timer);
    timer = null;
  }
  if (!apiClient.getToken()) return;

  const batches: Batch[] = [];
  while (queue.length > 0) batches.push({ events: queue.splice(0, BATCH_MAX), retried: false });
  if (retryBatch) {
    batches.push(retryBatch);
    retryBatch = null;
  }
  await Promise.all(batches.map((batch) => send(batch, keepalive)));
}

async function send(batch: Batch, keepalive: boolean): Promise<void> {
  try {
    await postTrack(batch.events, keepalive);
  } catch (e) {
    // 4xx — ошибка в нашем коде (чужой вид, лишний ключ): повтор не поможет, чинить надо клиент.
    if (e instanceof ApiError && e.status >= 400 && e.status < 500) {
      if (import.meta.env.DEV) console.warn('[track] batch rejected', e.status, e.message);
      return;
    }
    // Сеть или 5xx: один повтор со следующей пачкой, дальше выбрасываем.
    if (!batch.retried) retryBatch = { events: batch.events, retried: true };
  }
}

/** Сброс состояния между тестами. */
export function resetTrackingForTests(): void {
  queue = [];
  retryBatch = null;
  if (timer !== null) window.clearTimeout(timer);
  timer = null;
}

/** Для тестов: что сейчас лежит в очереди. */
export function queuedForTests(): readonly TrackEventBody[] {
  return queue;
}

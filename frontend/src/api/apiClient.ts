import { getInitDataRaw } from '../telegram/sdk';

export class ApiError extends Error {
  readonly status: number;
  /** Распарсенное тело ошибки — несёт структурированные payload'ы вроде 402-пейволла (currentPlan/requiredPlan/priceKopecks). */
  readonly body: unknown;
  constructor(status: number, message: string, body?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

class ApiClient {
  private token: string | null = null;
  private authInFlight: Promise<{ token: string; user: unknown }> | null = null;

  setToken(token: string): void {
    this.token = token;
  }

  clearToken(): void {
    this.token = null;
  }

  getToken(): string | null {
    return this.token;
  }

  async request<T>(
    method: string,
    path: string,
    body?: unknown,
    params?: Record<string, string>,
    isRetry = false
  ): Promise<T> {
    const url = new URL(path, window.location.origin);
    if (params) {
      Object.entries(params).forEach(([k, v]) => url.searchParams.set(k, v));
    }

    // Фиксируем токен в момент отправки запроса, чтобы можно было отличить 401 из-за
    // устаревшего токена (другой параллельный запрос мог обновить токен раньше, чем вернулся этот).
    const tokenUsed = this.token;
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    if (tokenUsed) {
      headers['Authorization'] = `Bearer ${tokenUsed}`;
    }

    const res = await fetch(url.toString(), {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    });

    if (res.status === 401 && !isRetry) {
      const bodyText = await res.clone().text().catch(() => '');
      console.warn(`[api] 401 on ${method} ${path} — ${bodyText}`);

      // Если другой параллельный запрос уже обновил токен, пока этот был в полёте —
      // повторить с новым токеном, не запускать ещё один цикл авторизации.
      if (this.token && this.token !== tokenUsed) {
        return this.request<T>(method, path, body, params, true);
      }
      await this.authenticate();
      return this.request<T>(method, path, body, params, true);
    }

    if (!res.ok) {
      const err = (await res.json().catch(() => ({ message: 'Unknown error' }))) as {
        message?: string;
      };
      throw new ApiError(res.status, err.message ?? `HTTP ${res.status}`, err);
    }

    // 204 No Content имеет пустое тело — не вызывать res.json(), иначе бросит исключение.
    if (res.status === 204) return undefined as T;

    return res.json() as Promise<T>;
  }

  get<T>(path: string, params?: Record<string, string>): Promise<T> {
    return this.request<T>('GET', path, undefined, params);
  }

  post<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('POST', path, body);
  }

  put<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('PUT', path, body);
  }

  patch<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('PATCH', path, body);
  }

  delete<T>(path: string): Promise<T> {
    return this.request<T>('DELETE', path);
  }

  async uploadFile(path: string, file: File): Promise<{ url: string }> {
    const formData = new FormData();
    formData.append('file', file);
    const headers: Record<string, string> = {};
    if (this.token) headers['Authorization'] = `Bearer ${this.token}`;
    const res = await fetch(new URL(path, window.location.origin).toString(), {
      method: 'POST',
      headers,
      body: formData,
    });
    if (!res.ok) {
      const err = (await res.json().catch(() => ({ message: 'Upload failed' }))) as { message?: string };
      throw new ApiError(res.status, err.message ?? `HTTP ${res.status}`);
    }
    return res.json() as Promise<{ url: string }>;
  }

  async authenticate(): Promise<{ token: string; user: unknown }> {
    if (this.authInFlight) return this.authInFlight;

    this.authInFlight = (async () => {
      try {
        const initDataRaw = getInitDataRaw();
        const data = await this.request<{ token: string; user: unknown }>(
          'POST',
          '/api/auth/telegram',
          { initData: initDataRaw },
          undefined,
          true // помечаем как retry, чтобы избежать бесконечного цикла
        );
        this.token = data.token;
        return data;
      } finally {
        this.authInFlight = null;
      }
    })();

    return this.authInFlight;
  }
}

export const apiClient = new ApiClient();

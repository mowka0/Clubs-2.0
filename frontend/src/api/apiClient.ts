import { getInitDataRaw } from '../telegram/sdk';

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

    // Capture token at request-send time so we can detect stale-token 401s
    // (another concurrent request may refresh the token before this one returns).
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

      // If another concurrent request already refreshed the token while this one
      // was in flight, retry with the new token — don't trigger another auth round.
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
      throw new Error(err.message ?? `HTTP ${res.status}`);
    }

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
          true // mark as retry to avoid infinite loop
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

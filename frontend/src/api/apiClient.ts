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

    const tag = `${method} ${path}`;
    console.debug(`[api] → ${tag} (token=${tokenUsed ? tokenUsed.slice(0, 8) + '…' : 'none'})`);

    const res = await fetch(url.toString(), {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    });

    console.debug(`[api] ← ${tag} ${res.status}`);

    if (res.status === 401 && !isRetry) {
      // Peek at response body so we can surface the server-provided reason.
      // Clone is needed because we'll still need to read or discard the original body.
      const bodyText = await res.clone().text().catch(() => '');
      console.warn(`[api] 401 on ${tag} — server response:`, bodyText);

      // If another request already refreshed the token while we were in flight,
      // just retry with the current one — don't trigger another auth round.
      if (this.token && this.token !== tokenUsed) {
        console.debug(`[api] 401 on ${tag}: token already refreshed by another request, retrying`);
        return this.request<T>(method, path, body, params, true);
      }
      console.debug(`[api] 401 on ${tag}: re-authenticating`);
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
    if (this.authInFlight) {
      console.debug('[api] authenticate: joining in-flight request');
      return this.authInFlight;
    }

    console.debug('[api] authenticate: starting new auth request');
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
        console.debug(`[api] authenticate: got new token ${data.token.slice(0, 8)}…`);
        return data;
      } finally {
        this.authInFlight = null;
      }
    })();

    return this.authInFlight;
  }
}

export const apiClient = new ApiClient();

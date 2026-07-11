/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_MOCK_INIT_DATA: string;
  // Публичные ключи Яндекс.Карт (event-geo): JavaScript API v3 (+HTTP-геокодер) и Static API.
  // Локально — frontend/.env.local (gitignored), в Docker-сборке — build args из Coolify env.
  readonly VITE_YANDEX_MAPS_API_KEY: string | undefined;
  readonly VITE_YANDEX_STATIC_API_KEY: string | undefined;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

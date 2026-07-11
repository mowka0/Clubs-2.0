/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_MOCK_INIT_DATA: string;
  // Публичные ключи Яндекс.Карт (event-geo) — три отдельных продукта кабинета:
  // «JavaScript API» (v2.1), «Static API», «API Геокодера».
  // Локально — frontend/.env.local (gitignored), в Docker-сборке — build args из Coolify env.
  readonly VITE_YANDEX_MAPS_API_KEY: string | undefined;
  readonly VITE_YANDEX_STATIC_API_KEY: string | undefined;
  readonly VITE_YANDEX_GEOCODER_API_KEY: string | undefined;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

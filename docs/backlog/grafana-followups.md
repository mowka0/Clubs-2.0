# Grafana (W5): хвосты после ревью 2026-10-15

Из Reviewer- и Security-проходов ветки `devops/grafana`; ничто из этого не блокировало выкатку.
Контекст — `infra/grafana/README.md`, `docs/modules/infrastructure.md` § Grafana.

1. **Технические ошибки в Grafana** — 5xx бэкенда, исключения, сбои бота и недоставленные DM
   живут только в логах контейнеров. Дашборд «Ошибки и сбои» показывает лишь то, что есть в базе.
   Путь: Loki + Alloy отдельным ресурсом Coolify (~300 МБ памяти), датасорс Loki в
   `provisioning/datasources/`, панели по `level=ERROR` и по логгерам бота. Девопс-задача.
2. **Отдельная docker-сеть «только postgres»** (`analytics_net`, `internal: true`) для Grafana вместо
   общей `clubs_net`: сейчас из контейнера Grafana по сети достижимы redis (без пароля), minio и
   backend:8080. Поверхность мала (Security 2026-10-15: Redis — намерения привязки с TTL 15 мин,
   MinIO — то же, что публично через `/uploads/`), но при следующей правке `docker-compose.prod.yml`
   сделать вместе с `requirepass` для Redis. Требует пересоздания контейнера базы.
3. **HSTS для `grafana.clubsmeet.com`** — Grafana ставит заголовок только при `protocol=https`,
   Traefik Coolify по умолчанию не добавляет, HSTS корня без `includeSubDomains`. Вариант: middleware
   `traefik.http.middlewares.grafana-hsts.headers.stsSeconds=31536000` в Edit Labels ресурса.
4. **Индекс `product_event (club_id, created_at)`** — панели «Клубы за период» и «затихшие клубы»
   агрегируют журнал по `club_id` одним проходом; при ~1 млн строк индекс ускорит их. Миграция —
   когда таблица вырастет (алерт на 1 млн строк уже стоит).
5. **Cookie `__Host-grafana_session`** (`GF_AUTH_LOGIN_COOKIE_NAME`) — рекомендация hardening-дока;
   не включено, потому что локальная проверка идёт по http, где `__Host-` браузер отбрасывает.
6. **Кампании в DM-отчёте и в Grafana считаются по-разному** (first-touch против стартов периода) —
   описано в панели; если PO захочет одно определение, править панель под `funnel.md` § 3.3.

# Clubs — known follow-ups

Небольшие задачки, выявленные при ревью на ветке `feature/club-settings-tab` (2026-04-24). Не блокируют мерж, но не должны потеряться.

## CL-1: docs/code drift — member_limit

**Реальность:** `OrganizerPage` / `validators.ts` использует `validateStep` со старым правилом `memberLimit: 1–200`. Backend `@Min(10) @Max(80)` в `CreateClubRequest` / `UpdateClubRequest`. Пользователь введёт 5 → фронт пропустит → backend 400.

**Fix:** синхронизировать `validators.ts` с `@Min(10) @Max(80)` + текст ошибки «Лимит участников: 10–80». Обновить `docs/modules/clubs.md` TASK-008 валидации (там всё ещё `memberLimit: 1-200`).

**Scope:** мелкий bugfix, одна ветка.

---

## CL-2: Whitelist-валидация `avatarUrl` в `UpdateClubRequest`

**Риск:** `PUT /api/clubs/{id}` принимает любую строку в `avatar_url`. Пользователь может поставить URL на сторонний домен (`https://attacker.com/tracker.gif`) → leak IP/UA при каждом просмотре клуба в каталоге. Не SSRF и не XSS (React экранирует src), но open-redirect / phishing potential через поддельный баннер.

**Fix:** в `UpdateClubRequest` и `CreateClubRequest` добавить `@Pattern(regexp = "^(|${S3_BASE_URL}/.*)")` — принимать только URL на публичный префикс нашего S3/MinIO bucket. Конфигурируется через `s3.base-url`.

**Scope:** Bean Validation + прочекать все места где avatarUrl приходит с клиента. Небольшая задача.

**Ref:** CWE-601 / OWASP A01.

---

## CL-3: Per-endpoint rate-limit для `/api/upload`

**Риск:** текущий `RateLimitFilter` даёт 60 req/min per user. 60 × 5 MB = ~300 MB в минуту — приемлемо для MVP, но без монiторинга роста бакета может уйти в disk-DoS.

**Fix:** отдельный bucket для `/api/upload` (например, 20 файлов/час per user) + grafana-алерт на размер MinIO бакета. CWE-770.

**Scope:** настройка Bucket4j + Prometheus/Grafana. Среднее.

---

## CL-4: 401-retry в `apiClient.uploadFile`

**Реальность:** `apiClient.request()` ловит 401, рефрешит JWT через Telegram initData и повторяет вызов. `apiClient.uploadFile` — отдельный путь (multipart через fetch), эту логику не наследует. Если токен истечёт во время загрузки — upload упадёт без автоперелогина, пользователь увидит «Не удалось загрузить файл».

**Fix:** обернуть `uploadFile` в общую retry-обёртку с `authenticate()` при 401.

**Scope:** небольшой refactor `apiClient`. UX, не security.

---

## CL-5: Вынести `SettingsTab` и остальные `*Tab` из `OrganizerClubManage.tsx`

**Реальность:** `OrganizerClubManage.tsx` ~950 строк, все табы inline — нарушает `frontend.md § "Компонент > 150-200 строк → разбить"`. SettingsTab добавил ещё +200 строк.

**Fix:** вынести каждый Tab в `features/clubs/components/` (или `pages/organizer/tabs/`). Одна refactor-ветка. Не блокирует фичи, но упрощает дальнейшие правки.

**Scope:** структурный рефактор. Средний.

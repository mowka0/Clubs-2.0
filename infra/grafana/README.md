# Grafana — продуктовая аналитика Clubs

Grafana OSS в Coolify на том же VPS, читает прод-базу под отдельной read-only ролью и показывает
шесть экранов поверх журнала событий (`product_event`, `docs/modules/product-analytics.md`)
и таблиц сущностей: пять стартовых из хэндоффа и «Ошибки и сбои». Вход только по логину; наружу — только домен через Traefik. Поставлено по
хэндоффу W5 (`docs/backlog/session-handoff-2026-10-15-evening.md` § 3), 2026-10-15.

| Что | Где |
|---|---|
| Стек | `docker-compose.yml` (сервис `grafana`, порты наружу не публикуются) |
| Образ | `Dockerfile`: `grafana/grafana:13.0.11` (тег + digest) + `provisioning/` внутри. Репозиторий тегов — `grafana/grafana`, **не** `grafana-oss` (тот остановился на 13.0.2 и CVE не получает) |
| Датасорс | `provisioning/datasources/clubs.yml` — uid `clubs-prod`, роль `grafana_ro` |
| Дашборды | `provisioning/dashboards/*.json` ← генератор `gen-dashboards.py` (SQL и раскладка там) |
| Алерты | `provisioning/alerting/product-event-size.yml` — размер журнала событий |
| Роль в базе | `create-ro-role.sh` + `create-ro-role.sql` (запускаются на VPS) |
| Пароль роли | на VPS: `/root/.clubs-grafana-ro.<id ресурса Coolify>.pass`, права 600; копия — только в env Grafana в Coolify |
| Адрес | `https://grafana.clubsmeet.com` (A-запись в Cloudflare, серое облачко, см. § 2.1) |
| Ресурс Coolify | проект clubs-20 → «Docker Compose», Base Directory `/infra/grafana` |
| Данные Grafana | том `grafana_data` (sqlite: пользователи, сессии, контакт-поинт алертов). Дашборды и датасорс там нет — они из образа |

## 1. Как устроено

- **Provisioning запечён в образ.** Coolify (4.1.2) переписывает относительные bind-mount
  (`./provisioning:/etc/grafana/provisioning`) в `/data/coolify/applications/<uuid>/…`, где
  файлов репозитория нет, — Grafana поднялась бы пустой. Поэтому `Dockerfile` копирует каталог
  `provisioning/` внутрь образа; Coolify собирает его на сервере за секунды. Датасорс и дашборды
  перечитываются при каждом старте, правки в UI не сохраняются (`allowUiUpdates: false`) — источник
  истины репозиторий.
- **Сеть.** Контейнер в трёх сетях: `coolify` (Traefik), собственной сети ресурса (добавляет
  Coolify) и `<id прод-ресурса>_clubs_net` — внешней сети compose-стека прода, где у базы есть
  алиас `postgres`. Имя сети задаётся переменной `CLUBS_DB_NETWORK` в env Coolify, не в репозитории.
  «Connect to Predefined Network» Coolify здесь не помогает: он подключает к сети `coolify`, а
  прод-база в ней не состоит (и не должна).
- **Роль `grafana_ro`.** `LOGIN` и `SELECT` **только на то, что читают дашборды**: таблицы без ПДн
  целиком (`product_event`, `platform_payment`, `subscription_pricing`, `reputation_ledger`), остальные
  — по колонкам (`create-ro-role.sql`); реквизиты, инвайт-ссылки, скриншоты оплат, заметки и тексты
  роли не видны даже через Explore. Граница — именно GRANT'ы (в `public` создавать объекты нельзя);
  транзакции только на чтение, `statement_timeout` 30 с и лимит 8 соединений — страховки от случайной
  тяжёлой панели, сессия может снять их сама. Датасорс держит не больше 4 соединений.
  Новая колонка в дашборде → строка `GRANT` в SQL → повторный запуск скрипта (сходится к файлу).
- **Секреты** (`GF_SECURITY_ADMIN_*`, `CLUBS_DB_PASSWORD`) — только в env Coolify; в compose они
  объявлены как обязательные (`${VAR:?…}`): незаданная переменная роняет деплой, а не запускает
  Grafana с `admin/admin`.
- **Ничего наружу.** Телеметрия, проверка обновлений, публичные снапшоты, лента новостей,
  gravatar и фоновый установщик плагинов (Grafana 13 при старте докачивает «предустановленные»
  приложения с grafana.com) выключены `GF_*`-переменными в compose — рантайм в интернет не ходит,
  всё нужное есть в образе.

## 2. Установка (один раз)

### 2.1. DNS — PO

В Cloudflare для зоны `clubsmeet.com`: `A grafana → 77.42.23.177`, **серое облачко** (DNS only —
сертификат выпускает Traefik по HTTP-01). Имя вне `.ru` намеренно — VPN-ловушка, CLAUDE.md
§ Infrastructure. Проверка: `dig +short grafana.clubsmeet.com` → `77.42.23.177`.

### 2.2. Роль в прод-базе

```bash
ssh root@77.42.23.177 "mkdir -p /root/clubs-grafana"
scp infra/grafana/create-ro-role.sh infra/grafana/create-ro-role.sql root@77.42.23.177:/root/clubs-grafana/
ssh root@77.42.23.177 "cd /root/clubs-grafana && sh create-ro-role.sh qhbcadbuungspby1mxw7p7n9"
```

`qhbcadbuungspby1mxw7p7n9` — id прод-ресурса (`docker ps | grep postgres`). Скрипт создаёт роль,
кладёт пароль в `/root/.clubs-grafana-ro.qhbcadbuungspby1mxw7p7n9.pass`, проверяет вход по TCP
и что `CREATE TABLE`/`DELETE` под ролью отклоняются с `permission denied` (именно GRANT'ами, а не
сессионным read-only, который роль могла бы снять сама). Повторный запуск безопасен (пароль
переиспользуется). Сделано 2026-10-15.

### 2.3. Ресурс в Coolify

1. Проект **clubs-20** → environment **production** → **+ New** → **Docker Compose** → источник
   GitHub App (Clubs 2.0), репозиторий `mowka0/Clubs-2.0`.
2. Ветка — `devops/grafana` на время приёмки, после мержа переключить на `master`.
   **Base Directory** `/infra/grafana`, **Docker Compose Location** `/docker-compose.yml`
   (Coolify склеивает оба). Загрузить compose.
3. У сервиса `grafana` задать домен **`https://grafana.clubsmeet.com:3000`** — суффикс `:3000`
   говорит Traefik, какой порт контейнера брать (как `EXPOSE 80` у фронта). `ports:` в compose
   нет и не нужно.
4. Environment Variables:

   | Переменная | Значение |
   |---|---|
   | `GF_SECURITY_ADMIN_USER` | логин администратора (не `admin`) |
   | `GF_SECURITY_ADMIN_PASSWORD` | `openssl rand -base64 24`; читается только при первом старте (дальше — § 5) |
   | `GF_SECURITY_SECRET_KEY` | `openssl rand -base64 32` — ключ шифрования секретов в базе Grafana; задать до первого старта, потом не менять |
   | `CLUBS_DB_PASSWORD` | содержимое `/root/.clubs-grafana-ro.qhbcadbuungspby1mxw7p7n9.pass` |
   | `CLUBS_DB_NETWORK` | `qhbcadbuungspby1mxw7p7n9_clubs_net` (`docker network ls \| grep clubs_net`) |
   | `GF_SERVER_ROOT_URL` | по умолчанию `https://grafana.clubsmeet.com`; задать, если домен другой |

   `CLUBS_DB_HOST/NAME/USER` имеют дефолты (`postgres` / `clubs` / `grafana_ro`) — не задавать.
5. **Deploy.** Сборка образа ~1 мин (скачивание базового образа при первом разе дольше).
   Сертификат выпустится, когда DNS уже резолвится (п. 2.1). Auto Deploy по вебхуку GitHub
   включить: пуш в выбранную ветку → пересборка (данные в томе сохраняются).

### 2.4. Повседневный пользователь — Viewer

После первого входа администратором: **Administration → Users → New user** — второй пользователь
с ролью **Viewer** в организации. С телефона и каждый день заходить им: Viewer смотрит дашборды,
но не открывает Explore (произвольный `SELECT` к прод-базе), не правит датасорс и алерты. Админ —
только для настройки (контакт-поинт, пользователи). Так сессионная cookie на телефоне не равна
ключу к базе.

### 2.5. Куда слать алерты — в UI, один раз

Правила алертов приезжают из репозитория, но контакт-поинт содержит токен бота и настраивается
в UI (пустой токен в provisioning уронил бы старт Grafana): **Alerting → Contact points → +
Create → Telegram**: токен **отдельного** бота для алертов (создать в BotFather; токен прод-бота
в Grafana не копировать), Chat ID — ваш telegram id (тот же, что в `PLATFORM_ADMIN_TELEGRAM_IDS`);
бота нужно один раз запустить (`/start`), иначе он не сможет написать первым. Затем **Alerting →
Notification policies → Default policy → Edit** → этот контакт-поинт. Пока это не сделано, алерты
видны только в Alerting → Alert rules.

**Один раз нельзя пропустить:** `GF_SECURITY_SECRET_KEY` задаётся до первого старта. Если первый
деплой прошёл без него — удалить том `grafana_data` (ресурс → Storages) и задеплоить заново, пока
в базе нет ничего, кроме пользователя.

## 3. Приёмка

1. `https://grafana.clubsmeet.com` открывается, без логина — только форма входа:
   `curl -sI https://grafana.clubsmeet.com/ | head -1` → `302` на `/login`;
   `curl -s https://grafana.clubsmeet.com/api/health` → `"database": "ok"`;
   `curl -s https://grafana.clubsmeet.com/api/search` → `401`.
2. Connections → Data sources → **Clubs** → Test → «Database Connection OK».
3. Dashboards → папка **Clubs** — шесть дашбордов, стат-панели «Сейчас» показывают числа прода.
4. Alerting → Alert rules → группа `product-event` — два правила в состоянии Normal.
5. Роль только читает (тот же probe, что при установке):
   ```bash
   ssh root@77.42.23.177 "cd /root/clubs-grafana && sh create-ro-role.sh qhbcadbuungspby1mxw7p7n9 --check"
   ```
   → `login ok, product_event rows: N` и `write check: CREATE TABLE and DELETE under grafana_ro rejected`.
6. `ssh root@77.42.23.177 docker stats --no-stream` — Grafana в пределах 1 ГиБ (замер локально:
   ~165 МБ после старта, ~500 МБ после открытия всех дашбордов); prod/staging бэкенды не затронуты.
7. При первом входе Grafana 13 показывает промо «Grafana Assistant» — закрыть. Assistant и прочий
   «AI» работают только после подключения к Grafana Cloud — **не подключать**: данные журнала
   должны оставаться у нас (product-analytics.md § 1).

Локально до деплоя то же проверено 2026-10-15: образ собран, Grafana 13.0.11 поднята против
временной базы со всеми миграциями, датасорс «Database Connection OK», шесть дашбордов и два
правила приехали из файлов, все SQL панелей и алертов выполняются под `grafana_ro` — и на пустой
схеме, и на проде.

## 4. Дашборды

Недели — календарные по Москве, с понедельника (как недельный DM-отчёт). Верхний выбор времени
фильтрует панели «за период»; панели «сейчас» его не знают. **Журнал запусков и экранов ведётся
с 2026-10-15** — всё, что считается по `app_opened`/`screen_opened` (WAU, когорты, экраны),
начинается с этой даты; таблицы сущностей (клубы, встречи, оплаты) показывают всю историю.

| Дашборд | Что внутри | Вопросы (product-analytics.md § 7) |
|---|---|---|
| **Воронка** | старты бота → чаты → первые встречи → чекауты → оплаты (как в отчёте), кампании, запуски Mini App по входу и платформе, интро, платёжная воронка, размер журнала | В1, В2, В6 |
| **Активность клубов** | клубы/с чатом/пользователи/WAU/MAU, активные и новые по неделям, таблица клубов за период, экраны, вступления по способу, кнопки бота, поддержка | В2, В4, В5 |
| **Встречи** | создано/состоялось/отменено, голоса, явка (% и по неделям), чаты без встреч 30 дней, дни от привязки чата до первой встречи, форматы | В3, В4 |
| **Деньги** | подписки чатов и MRR, оплаты Robokassa (со страницы / автосписания, статусы, способ), список подписок, платёжная воронка, долги и сборы | В6 |
| **Удержание** | когорты по неделе первого запуска (+1…+4), состав недельной аудитории (новые / были на прошлой / вернулись), затихшие клубы | В7 |
| **Ошибки и сбои** (PO, 15.10) | что требует действия: зависшие и неоплаченные счета, подписки в грейсе и на ретрае, бот выгнан или без права на включённую функцию, пост бота не ушёл, споры о явке, неотмеченная явка, «Отдал» без ответа; по неделям — платёжные сбои, отключения, срывы (неявки, просроченные долги, отмены); таблицы чатов и счетов с проблемами | — |

«Ошибки и сбои» показывает только то, что есть в базе. Технические ошибки (5xx, исключения
бэкенда, сбои бота, недоставленные DM) живут в логах контейнеров (Coolify → Logs) и в Grafana
появятся после подключения Loki + Alloy — отдельная девопс-задача (~300 МБ памяти, ещё один ресурс).

В таблицах нет персональных данных: имена клубов — да, имена людей, username, телефоны — нет
(правило product-analytics.md § 6). Строки из `props` (экран, кнопка, платформа) приходят от
клиента — панели экранируют HTML, `GF_PANELS_DISABLE_SANITIZE_HTML` не включать.

## 5. Как менять

- **Дашборд:** править SQL/раскладку в `gen-dashboards.py` → `python3 infra/grafana/gen-dashboards.py`
  → закоммитить JSON вместе с генератором → пуш → Coolify пересоберёт образ. Быстро проверить SQL
  можно прямо в Grafana: Explore → датасорс Clubs (только `SELECT`, таймаут 30 с).
  Экспериментировать в UI — «Save as» в другую папку; дашборды из репозитория в UI не сохраняются.
- **Новая таблица или колонка в дашборде:** строка `GRANT` в `create-ro-role.sql` → повторный
  запуск скрипта на VPS (§ 2.2; сходится к файлу, лишние права снимает). Проверка полноты —
  прогон всех SQL под ролью (`--check` проверяет только вход и запрет записи).
- **Версия Grafana:** тег и digest в `Dockerfile` — теги смотреть в `grafana/grafana` на Docker Hub
  (не `grafana-oss`), советы безопасности — https://grafana.com/security/security-advisories/;
  затем деплой. База sqlite в томе мигрирует сама; откат на предыдущий тег — тоже деплой.
- **Пароль роли:** `sh create-ro-role.sh <id> --rotate` на VPS → новое значение `CLUBS_DB_PASSWORD`
  в Coolify → Restart.
- **Пароль администратора Grafana:** в UI (Profile → Change password) или
  `docker exec <grafana> grafana cli admin reset-admin-password '<новый>'`. Переменная
  `GF_SECURITY_ADMIN_PASSWORD` читается только при создании первого администратора — её смена в
  Coolify после первого старта ничего не меняет.
- **Алерты:** правила — в YAML (uid менять нельзя — по нему Grafana обновляет правило); контакт —
  в UI (§ 2.4).

## 6. Снять

Coolify → ресурс Grafana → Stop / Delete (том `grafana_data` удалится вместе с ресурсом — там
только пользователи и контакт-поинт). Роль в базе:

```bash
ssh root@77.42.23.177 'docker exec $(docker ps -q -f name=postgres-qhbcadbuungspby1mxw7p7n9) sh -c "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -c \"alter default privileges for role \$POSTGRES_USER in schema public revoke select on tables from grafana_ro; drop owned by grafana_ro; drop role grafana_ro\""; rm /root/.clubs-grafana-ro.*.pass'
```

## 7. Отвергнуто

- **Metabase** — 1–1,5 ГБ памяти против ~0,5 ГБ у Grafana в работе (PO, хэндофф 15.10; оценка
  «150–250 МБ» для Grafana 13 не подтвердилась, см. § 3 п. 6).
- **Bind-mount каталога provisioning из репозитория** — Coolify переписывает путь (§ 1).
- **Токен прод-бота в Grafana** для алертов — лишнее место хранения секрета; отдельный бот.
- **Второй датасорс на staging** — не сейчас (хэндофф § 5, вопрос 2); при необходимости — та же
  роль в staging-базе, вторая внешняя сеть в compose и второй блок в `clubs.yml`.
- **HSTS от Grafana** (`GF_SECURITY_STRICT_TRANSPORT_SECURITY`) — Grafana ставит заголовок только
  при `protocol = https` у себя, а TLS терминирует Traefik; заголовок остаётся на Traefik
  (middleware в лейблах Coolify — в бэклоге, `docs/backlog/grafana-followups.md`).
- **Отдельная docker-сеть «только postgres»** для Grafana — требует правки прод-compose и
  пересоздания контейнера базы; поверхность и так мала (Redis хранит только намерения привязки
  с TTL 15 мин, MinIO отдаёт то же, что публично через `/uploads/`). Бэклог, вместе с
  `requirepass` для Redis.

# Хэндофф сессии 2026-10-04 → 2026-10-05: воронка и фото сбора в проде, домен пережил холд

> **Читать первым в новой сессии.** Предыдущие хэндоффы по темам: `sprint-1.0-day4-5-funnel-handoff.md`
> (воронка, влита), `sprint-1.0-day4-billing-handoff.md` § 2d (Robokassa, рекуррент),
> `sprint-1.0-day2-3-handoff.md` (чат-пивот, этап 2). План спринта — `docs/design/sprint-1.0-chat-pivot.md`.

## 1. Где мы

- **master = `58df0cc0`, прод на нём** (бэк и фронт healthy, схема **101**). Влито за сессию:
  - PR #181 `6f0079d9` — **День 4,5: разметка воронки + недельный DM-отчёт** (V100). Тест PO на staging пройден
    2026-10-04. **Первый боевой отчёт ушёл 2026-10-05 09:00 МСК** (`Funnel report sent: isoWeek=40 recipients=1`),
    `PLATFORM_ADMIN_TELEGRAM_IDS` в проде задан PO.
  - PR #182 `58df0cc0` — **фото сбора**: (а) на странице сбора фото целиком карточкой вместо обложки-хиро из
    PR #165 (обложка резала чек); (б) пост о сборе в чате и DM при создании уходят **картинкой с подписью**, как
    живой закреп встречи, флаг `skladchina_chat_posts.has_photo` (**V101**), правки через `editMessageCaption`,
    длинная подпись (> 1024) или сбой — текстом; (в) заметка в доках о ловушке Coolify (п. 3). Тест PO пройден
    2026-10-05 («окей, всё норм»; «нет прогресс-бара» оказалось ошибкой PO — у «Скинуться» полоса есть всегда).
- Спеки выровнены: `skladchina-v3.md` § 6/§ 8/§ 9.1/AC-15, `club-chat-link.md` слайс 3.5, `funnel.md`,
  `infrastructure.md`, `docs/INDEX.md`. PRD не трогали (решение PO 2026-08-16: одним заходом в конце спринта).
- **Robokassa боевая без рекуррента** (PR #179/#180, проверено с VPS: `ClubsApp`, SHA256, `TEST_MODE=false`,
  `RECURRING_ENABLED=false`). Рекуррент — ждём ответ Robokassa («скоро подключат», PO). Порядок включения —
  billing-handoff § 2d п. 7 и `platform-billing.md` § 11 (только при нуле `PENDING` материнских счетов).
- **Запарковано локально, НЕ пушить:** ветка `feature/billing-autopay-available` (коммит `2f178870` от
  `aa4f0865`): поле `autopayAvailable` в `BillingStatusDto` + заблокированный ползунок + ручка стаба
  `BILLING_STUB_RECURRING_ENABLED`, бэк/фронт зелёные. PO 2026-10-04: «пока не трогай, скоро подключат рекуррент».
  Если рекуррент включат — ветку удалить; если застрянет — пушить на staging.

## 2. Инцидент дня: домен Mini App `clubsmeet.com` был приостановлен регистратором

**Обнаружено 2026-10-05 ~06:00 UTC** сразу после деплоя #182 (curl снаружи → `000` за 2 мс): в whois и в реестре
`.com` NS подменены на `ns1/ns2.verification-hold.suspended-domain.com`, зона отдаёт `127.0.0.1`. Причина — ICANN
WHOIS verification: регистратор (PDR через Timeweb) обязан приостановить домен, если владелец за **15 дней** не
подтвердил контактный e-mail; домен куплен 20.09 → срок истёк ровно 05.10. Деплой ни при чём.
**PO подтвердил e-mail ~06:20 UTC, холд снят ~06:35 UTC**: реестр снова делегирует на `lara/rustam.ns.cloudflare.com`,
Cloudflare отдаёт `77.42.23.177`, подменные NS перестали отвечать (резолверы падают на родителя), кэш 1.1.1.1 сброшен
через `POST https://1.1.1.1/api/v1/purge?domain=clubsmeet.com&type=A|NS`, снаружи 200. Пока домен лежал (~1 ч),
Mini App не открывался ни у кого; оплата через Robokassa проходила (ResultURL на `clubsapp.ru`), ломалась только
страница возврата `clubsmeet.com/pay/return`.
**Ловушка:** `curl https://clubsmeet.com` **с VPS** давал 200 и в холде — там 127.0.0.1 это сам Traefik. Проверять
домен снаружи: `dig +short clubsmeet.com @1.1.1.1` и `whois clubsmeet.com | grep -i "name server"`.
**Хвост PO:** проверить, что у `clubsapp.ru` (Timeweb) контакт тоже подтверждён — тот же механизм.
Memory: `reference_domain_verification_hold`.

## 3. Инцидент дня: staging лежал ~16 часов из-за пустой env-переменной Coolify

После теста воронки PO «вернул `FUNNEL_REPORT_CRON` на дефолт», **очистив** значение в UI Coolify. Очищенная
переменная приезжает в контейнер пустой строкой (`VAR=`, и в `/data/coolify/applications/<uuid>/.env`),
compose-дефолт `${VAR:-…}` не срабатывает, Spring считает пустое свойство заданным → `@Scheduled(cron="")` →
«One-time task only supported with specified initial delay» → бэкенд падал на старте (5429 рестартов), фронт не
стартовал (`depends_on: service_healthy`), staging 503. GitHub Action «Deploy to Staging» при этом зелёный — он
только дёргает вебхук. **Решение PO: кодом не защищать** (SpEL-защита + тест были написаны и откачены), PO задал
`FUNNEL_REPORT_CRON=0 0 9 * * MON` на staging явно. Правило в доках (`infrastructure.md`, `funnel.md` § 6 п. 6):
крон-переменные (`FUNNEL_REPORT_CRON`, `SUBSCRIPTION_LIFECYCLE_CRON`, `BILLING_RECONCILE_CRON`,
`MEMBERSHIP_EXPIRY_CRON`) **задавать явно или удалять целиком, не очищать**; изменения env применяются только
после Redeploy. Диагностика за минуту — memory `reference_coolify_empty_env_var` (рестарт-луп, логи, очередь деплоев
Coolify из `coolify-db`).

## 4. Состояние окружений

| | prod | staging |
|---|---|---|
| Образ | `58df0cc0` (PR #182) | `2e8b353d` (ветка #182 до squash; свободен для следующей ветки) |
| Схема | 101 | 101 |
| Биллинг | `robokassa`, без рекуррента | `stub`, `BILLING_TRIAL_DAYS=1`, `SUBSCRIPTION_PERIOD_DAYS=1`, `SUBSCRIPTION_LIFECYCLE_CRON=0 */5 * * * *` |
| Воронка | `PLATFORM_ADMIN_TELEGRAM_IDS` задан, крон дефолт | `PLATFORM_ADMIN_TELEGRAM_IDS` задан, `FUNNEL_REPORT_CRON=0 0 9 * * MON` |

Сервер: Coolify `concurrent_builds` = **1** (стоит; сборки встают в очередь, две разом больше VPS не положат),
**Rescale до 8 ГБ не сделан** (3,8 ГБ). Деплой-дисциплина: пуш ветки → дождаться, пока staging-контейнеры встанут на
SHA (`ssh root@77.42.23.177 docker ps`, тег образа = SHA коммита) → только потом merge → проверить, что прод реально
на новом SHA и Flyway накатил миграцию (деплой #155 однажды контейнеры не подменил).

## 5. Хвосты PO (не блокируют)

- Robokassa: рекуррент (п. 1); реквизиты выплат с отдельной СберКарты; банковское автоформирование чеков выключить.
- BotFather `@clubs_v2_bot`: Description / About / Commands — тексты в `telegram-bot.md` § «BotFather».
- Юрист не смотрел оферту, политику, текст возврата.
- Сброс root-пароля RU VPS (засветился 18.09); CAA в Cloudflare опционально; Rescale 8 ГБ.
- Проверить верификацию контакта у `clubsapp.ru` (п. 2).
- Wishlist PO п. 15–17 (`docs/backlog/po-wishlist.md`): счёт из встречи любым участником с верификацией через
  организатора, гибкие настройки для создателя встреч, кнопки back везде.

## 6. Долги кода (бэклог, не блокируют)

- Биллинг: billing-handoff § 6 п. 5–9 (напоминание «за день» по календарным дням МСК, `reconcilePending` без проверки
  суммы, гонка двух первых оплат → 500, `findLatestByClub` без индекса, `isCard` по подстроке, `CHAT_PRICE_LINE`
  хардкодит 199 ₽); режим `BILLING_PROVIDER=none` обещан, не сделан; `recipientInn` в `BillingStatusDto`.
- Фото сбора: подпись, переросшая 1024 ПОСЛЕ создания (список «Ждём: @…» до 15 имён), не обновляется до
  укорочения — принято как у встреч, записано в `skladchina-v3.md` § 6; при жалобе — не искать баг.
- `EventServiceTest`: `verify { publishEvent(any()) }` проверяет пустоту (нужен `ofType<>()`), 6 мест.
- Политика ПД дублируется руками (`PrivacySections.kt` ↔ `privacyText.ts`).

## 7. Следующий шаг спринта

**AI-опрос v0** (дни 7–11, план `docs/design/sprint-1.0-chat-pivot.md` § 5): начинать со спеки и решений PO по
формату; новая ветка от master `58df0cc0`. Открытый хвост этапа 2: тур по странице клуба к «Пригласить»
(`sprint-1.0-day2-3-handoff.md` § «Что дальше»). Реклама — после AI-опроса, воронка уже считает.

## 8. Ловушки сессии (чтобы не наступать второй раз)

- Serena `find_referencing_symbols` снова вернула пусто (`toStatusDto`) — перепроверять grep-ом.
- Полный `npm test` параллельно валит СЛУЧАЙНЫЙ файл («модуль не экспортирует X», msw); `npx vitest run
  --no-file-parallelism` — зелёный. Не регрессия.
- Кодоген jOOQ на временном PG: ждать **второго** «database system is ready to accept connections» в `docker logs`
  + `sleep 2`, иначе миграции падают на перезапуске Postgres. Рецепт — `branch-handoff-de-stars-member-admin.md` § 7.
- Один gradle-прогон за раз: параллельный второй ломает оба.
- У сбора ОДНО поле `photo_url` (с V14), отдельного «чека сбора» нет и не было; `receipt_url` — только у долга
  участника (V90). PO резко отверг второе поле/миграцию — не предлагать.
- Пароли Robokassa (20-символьные строки) один раз оказались в незакоммиченной правке `po-wishlist.md` — gitleaks
  такой формат не ловит, вычищать руками до коммита.

## 9. Стартовая команда новой сессии

> «продолжи: хэндофф `docs/backlog/session-handoff-2026-10-05.md`. Проверить `dig +short clubsmeet.com @1.1.1.1`
> (должен быть `77.42.23.177`). Дальше — AI-опрос v0 (`sprint-1.0-chat-pivot.md` § 5), новая ветка от master;
> рекуррент Robokassa — по `sprint-1.0-day4-billing-handoff.md` § 2d п. 7, когда ответят.»

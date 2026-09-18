# Российский reverse proxy перед Hetzner

**Что чинит.** Диагностика PO 2026-09-18: с домашних и мобильных провайдеров РФ без VPN не
открываются ни `https://clubsapp.ru`, ни `https://77-42-23-177.sslip.io`, ни `http://77.42.23.177`.
Значит, режется подсеть Hetzner целиком, DNS ни при чём. Из российских дата-центров (check-host,
18.09) сайт отвечает 200 за 19 мс. Нужна точка входа с российским IP, которая пробрасывает трафик
на Hetzner из дата-центра.

Кому это важно: модератору Robokassa (открывает сайт обычным браузером — без прокси откажет).
Mini App (`app.clubsapp.ru`) идёт на Hetzner напрямую и без VPN из РФ по-прежнему не открывается —
осознанное решение PO: аудитория открывает Telegram с VPN (см. шаг 6).

## Как устроено

```
браузер / WebView ──TLS──▶ RU VPS :443 (nginx stream) ──TCP + PROXY protocol──▶ Hetzner :443
                                                                   (Traefik → nginx фронта → бэкенд)
```

- nginx на российском VPS работает в режиме `stream`: TLS **не расшифровывает**, пересылает байты
  как есть. Сертификат `clubsapp.ru`, роутинг Traefik, приложение — всё остаётся на Hetzner.
- Перед первыми байтами каждого соединения nginx посылает строку PROXY protocol с адресом клиента.
  Traefik принимает её **только с IP этого VPS**, строку с чужого адреса отбрасывает, и дальше
  работает так, будто клиент подключился напрямую: `X-Forwarded-For` собирается как раньше,
  `ClientIpResolver` на бэкенде не меняется, rate limit и allowlist ResultURL Robokassa видят
  настоящие адреса.
- Порт 80 пробрасывается так же: по нему Traefik делает редирект на https и проходит HTTP-01
  проверка Let's Encrypt при продлении сертификата (ближайшее — около 2026-11-15).
- Бот не затрагивается: он ходит в Telegram с Hetzner по long polling, сайт в этом не участвует.
- Staging (`staging.77-42-23-177.sslip.io`) через прокси не идёт — тестируется с VPN, как сейчас.

**Что меняется для нас.** Вторая точка отказа: лёг RU VPS — не открывается `clubsapp.ru` (откат —
DNS обратно, 10 минут). Задержка растёт на один прыжок, ~10–30 мс. Плата за VPS ~790 ₽/мес (Timeweb).

**⚠️ Обратная сторона (найдено 18.09 при включении).** С российского ДЦ трафик в сторону диапазонов
VPN-провайдеров фильтруется на аплинке: SYN от клиента через VPN до RU VPS доходит, SYN-ACK
обратно — нет, трассировка обрывается сразу за границей сети Timeweb (проверено на VPN с выходом
через австрийский хостер AS216416; `mirror.timeweb.ru` с него недоступен так же). Итог: через
прокси `clubsapp.ru` открывается без VPN из РФ и из обычных зарубежных сетей (Hetzner DE — 200),
но **не открывается с VPN, чей диапазон фильтруют**. Раньше было наоборот. Поэтому Mini App живёт на
**`app.clubsapp.ru` — поддомене с A-записью напрямую на Hetzner, минуя прокси** (шаг 6):
VPN-пользователи идут прямым путём, как раньше через sslip.io. GeoDNS (РФ-резолверы → прокси,
остальные → Hetzner) понадобится, только если аудитория окажется без VPN; DNS Timeweb его не умеет.

**RU VPS — доверенный узел уровня прода.** После переключения DNS он держит порт 80 домена, а
значит, кто владеет этой машиной, может пройти HTTP-01 у Let's Encrypt, получить сертификат
`clubsapp.ru` и расшифровывать трафик. Поэтому: доступ только по SSH-ключу (установщик выключает
парольный вход и не запускается без ключа), файрвол, автообновления, а выдача сертификатов
привязывается к ACME-аккаунту Traefik CAA-записью (шаг 4).

## Почему так, а не иначе

- **Не переезд стека в РФ.** Бэкенд обязан ходить в `api.telegram.org`; из российского
  дата-центра это может быть заблокировано. Hetzner остаётся домом приложения.
- **Не L7-прокси с терминацией TLS.** Сертификат в двух местах, оба хотят отвечать на одну
  ACME-проверку, а в `X-Forwarded-For` появляется лишний прыжок — пришлось бы менять
  `ClientIpResolver` и различать прямые и проксированные запросы. Passthrough + PROXY protocol
  делает прокси прозрачным.
- **Не `proxyProtocol.insecure=true` в Traefik.** Строку PROXY примут от кого угодно, и любой
  клиент подставит чужой IP — обход rate limit и allowlist ResultURL. Только `trustedIPs`.
- **UDP 443 (HTTP/3) не пробрасываем и в Traefik не включаем.** Сейчас h3 у Coolify-Traefik v2.11
  не активен (нет `--experimental.http3`, `Alt-Svc` прод не шлёт). PROXY protocol по UDP не
  существует: с пробросом все h3-клиенты схлопнулись бы в IP прокси — общий rate limit на всех.

## Шаги (порядок важен: Traefik → nginx → проверка → DNS)

### 0. Купить VPS

Российский провайдер с дата-центром в РФ: Timeweb Cloud (там же домен), Selectel, Beget и т.п.
Минимальный тариф: 1 vCPU, 1 ГБ, публичный IPv4, Ubuntu 24.04, **root по SSH-ключу** — ключ
добавить при создании машины, установщик без него не запустится. Скрипт сам проверит, что из
этого дата-центра Hetzner отвечает, и остановится, если нет — тогда провайдера сменить.

### 1. Traefik на Hetzner — доверять строке PROXY с адреса прокси

Coolify → Servers → localhost → Proxy → Configuration. В список `command:` после строки
`--entrypoints.https.address=:443` добавить две строки, подставив IP нового VPS:

```yaml
      - '--entrypoints.http.proxyProtocol.trustedIPs=<RU_IP>/32'
      - '--entrypoints.https.proxyProtocol.trustedIPs=<RU_IP>/32'
```

Save → Restart Proxy (несколько секунд простоя всех сайтов). Проверить с VPN, что
`https://clubsapp.ru` и staging открываются. На текущий трафик настройка не влияет: соединения
без строки PROXY обрабатываются как раньше, а строку от чужого адреса Traefik отбрасывает.

Файл `/data/coolify/proxy/docker-compose.yml` на диске — зеркало этой настройки; править в UI,
Coolify перезаписывает файл из своей базы.

### 2. Поставить nginx на RU VPS

```bash
scp -r infra/ru-proxy infra/host root@<RU_IP>:/tmp/
ssh root@<RU_IP> "cd /tmp/ru-proxy && bash install.sh"
```

Скрипт ставит nginx с модулем stream, проверяет достижимость Hetzner, проверяет и кладёт
`nginx.conf`, выключает парольный SSH, включает файрвол (SSH с лимитом, 80, 443),
автообновления и MSS clamping из `infra/host` (прокси сам завершает TCP с клиентами, и PMTU-дыра
с белыми экранами относится к нему так же, как к Hetzner). Идемпотентен, повторный запуск безопасен.
С Mac через VPN до RU VPS TCP может не проходить (фильтр VPN-диапазонов) — тогда через Hetzner:
`ssh -J root@77.42.23.177 root@<RU_IP>` и `scp -o ProxyJump=root@77.42.23.177 …`.

### 3. Проверка до переключения DNS

С машины **без VPN** (домашняя сеть или телефон в режиме модема):

```bash
curl -sSI --resolve clubsapp.ru:443:<RU_IP> https://clubsapp.ru/ | head -1
```

Ожидается `HTTP/2 200`. Вторая проверка — что до бэкенда доходит настоящий IP, а не адрес прокси
(работает после выкатки этой ветки в прод: лог фронта переведён на формат с `X-Forwarded-For`).
На Hetzner, сразу после curl:

```bash
ssh root@77.42.23.177 'docker logs --since 2m $(docker ps --format "{{.Names}}" | grep "^frontend-qhbcadbuungspby1mxw7p7n9" | head -1) 2>&1 | grep -v "^127.0.0.1"'
```

(`grep -v 127.0.0.1` прячет healthcheck контейнера, `head -1` — на случай двух контейнеров во
время деплоя.) Последнее поле в кавычках — `X-Forwarded-For`. Там должен быть публичный IP
машины, с которой делали curl (узнать: `curl -s https://api.ipify.org`), а не `<RU_IP>`. Если
`<RU_IP>` — Traefik не принял строку PROXY: перепроверить шаг 1 (IP, `/32`, перезапуск прокси).

### 4. CAA: сертификат `clubsapp.ru` выдаётся только ACME-аккаунту Traefik

**Пока пропущено (18.09): DNS-панель Timeweb не поддерживает тип CAA** (только A, AAAA, MX, CNAME,
TXT, SRV). Защита остаётся на уровне самого VPS (ключ, файрвол, автообновления) плюс периодическая
проверка выпусков на `https://crt.sh/?q=clubsapp.ru` — чужой сертификат там виден. Чтобы CAA всё же
поставить, DNS-хостинг переносится к провайдеру с поддержкой CAA (Yandex Cloud DNS, Selectel и т.п.):
домен остаётся у Timeweb, меняются только NS-записи. Тогда запись такая (флаг `0`, тег `issue`):

```
clubsapp.ru.  CAA  0 issue "letsencrypt.org; accounturi=https://acme-v02.api.letsencrypt.org/acme/acct/3755959236"
```

Одной записи достаточно: без отдельной `issuewild` то же ограничение действует и на
wildcard-сертификаты (RFC 8659). `accounturi` — аккаунт Let's Encrypt, которым Traefik на Hetzner
выпускает и продлевает сертификат (`jq -r '.letsencrypt.Account.Registration.uri'
/data/coolify/proxy/acme.json`); чужой аккаунт, даже контролируя порт 80, сертификат не получит.

**Ловушка:** переустановка Coolify или потеря `acme.json` меняет аккаунт — продление начнёт
падать, пока `accounturi` не обновлён.

### 5. Переключить DNS

TTL A-записей — 600 с (проверено на `ns1.timeweb.ru` 18.09; перед переключением убедиться в
панели, что не выше). Timeweb → домен `clubsapp.ru` → DNS: `A @` и `A www` → `<RU_IP>`. За 10
минут разъедется. Проверка: `dig +short clubsapp.ru` = `<RU_IP>`; открыть `https://clubsapp.ru`
с телефона без VPN.

### 6a. DNS-хостинг: NS Timeweb недоступны с фильтруемых VPN-сетей → Cloudflare (DNS only)

Найдено 18.09 при переключении BotFather на `app.clubsapp.ru`: телефон PO с VPN не открывал ни
`app.`, ни корень, а `77-42-23-177.sslip.io` (тот же IP!) открывал. Захват пакетов: для имён в
`clubsapp.ru` до серверов не доходило ничего. Причина — **все четыре NS Timeweb (`ns1/ns2.timeweb.ru`,
`ns3/ns4.timeweb.org`) стоят в РФ и недоступны с VPN-диапазонов, которые фильтрует российский
аплинк** (проверено с Mac через VPN: таймаут по UDP и TCP, а NS nip.io для sslip отвечают). Крупные
публичные резолверы пробиваются, собственные резолверы VPN-провайдеров — нет, и для них любое имя
в `clubsapp.ru` не существует. Сервер и поддомен тут ни при чём.

Лечение — перенос DNS-хостинга к провайдеру с anycast-серверами по всему миру: **Cloudflare, Free,
только DNS**. Домен остаётся у Timeweb, меняются NS. Записи зоны на 18.09 (выгружены с
`ns1.timeweb.ru`):

```
A    @    147.45.189.189     ; RU-прокси
A    www  147.45.189.189     ; RU-прокси
A    app  77.42.23.177       ; Hetzner напрямую — Mini App
MX   @    10 mx1.timeweb.ru.
MX   @    20 mx2.timeweb.ru.
TXT  @    "v=spf1 include:_spf.timeweb.ru ~all"
```

Порядок: Cloudflare → Add a site → `clubsapp.ru` (Free) → сверить импорт с таблицей выше → у всех
записей **серое облачко (DNS only)** — оранжевое пустит трафик через сеть Cloudflare, которую в РФ
душат, и сломает PROXY protocol → добавить CAA `0 issue "letsencrypt.org; accounturi=…"` (шаг 4,
Cloudflare его умеет) → Timeweb → домен → NS-серверы → два NS Cloudflare вместо четырёх Timeweb →
дождаться «Active» → проверить `dig NS clubsapp.ru @8.8.8.8` и открыть `app.clubsapp.ru` с телефона
через VPN. До переезда BotFather держать на `77-42-23-177.sslip.io`. Запасной провайдер с той же
логикой — Gcore DNS.

### 6. Mini App на `app.clubsapp.ru` — напрямую на Hetzner, минуя прокси

1. Timeweb DNS: `A app → 77.42.23.177` (на Hetzner, **не** на прокси).
2. Coolify → прод-приложение → сервис `frontend` → Domains: добавить `https://app.clubsapp.ru`,
   Save, Redeploy. Сертификат Let's Encrypt выпустится сам: DNS ведёт прямо на Hetzner.
   `77-42-23-177.sslip.io` оставить — старые кнопки в чатах ведут на него.
3. Coolify env прода: `TELEGRAM_WEBAPP_BASE_URL=https://app.clubsapp.ru` (кнопки бота, страницы
   возврата после оплаты). Дефолт в коде — тот же.
4. Кабинет Яндекса: добавить `app.clubsapp.ru` в Referer обоих браузерных ключей (JS API карты и
   Static API), иначе карта в пикере места молча ляжет.
5. BotFather: Bot Settings → Menu Button URL и Configure Mini App → `https://app.clubsapp.ru` —
   **только после переезда DNS (шаг 6a)**, иначе с фильтруемых VPN имя не резолвится.
6. Robokassa — модерация магазина по адресу `https://clubsapp.ru` (через прокси, модератор без
   VPN доходит).

Почему не `clubsapp.ru` для Mini App: через прокси сайт недоступен с фильтруемых VPN-диапазонов
(«Обратная сторона» выше), а Telegram у аудитории открыт с VPN. Почему не sslip.io: чужой
бесплатный DNS без SLA, имя с зашитым IP сервера (переезд = смена URL везде), домен не в Public
Suffix List (общие лимиты Let's Encrypt), репутация wildcard-доменов у фильтров.

## Откат

1. **Сначала** убрать обе строки `trustedIPs` из Traefik (шаг 1 наоборот) → Restart Proxy.
   Освободившийся IP провайдер отдаст следующему арендатору, и строке PROXY с него Traefik верить
   не должен: иначе новый владелец подставит любой адрес клиента — обход rate limit и allowlist.
2. Вернуть `A @` и `A www` на `77.42.23.177` — через 10 минут трафик снова идёт напрямую.
3. На RU VPS — `systemctl disable --now nginx` или удалить машину.

## Эксплуатация

- **`clubsapp.ru` не открывается, а `77-42-23-177.sslip.io` с VPN открывается** → проблема на
  RU VPS: `systemctl status nginx`, `tail /var/log/nginx/error.log`, оттуда же
  `curl -sI --resolve clubsapp.ru:443:77.42.23.177 https://clubsapp.ru/` — достижим ли Hetzner из
  этого дата-центра.
- **После любого Save/Restart прокси в Coolify и после обновления Coolify** — повторить проверку
  `X-Forwarded-For` из шага 3. Настройка `trustedIPs` живёт только в базе Coolify; если она
  потеряется, ошибок не будет, но все клиенты из РФ сольются в один адрес: общий rate limit
  (429 на входе для всех без VPN) и 403 на ResultURL Robokassa.
- **Сменился IP RU VPS** → сначала убрать старый адрес из `trustedIPs` (см. «Откат», п. 1), затем
  шаги 1, 3 и 5 с новым.
- **Продление сертификата** (Traefik продлевает за 30 дней до конца; ближайшее ~2026-11-15).
  После этой даты проверить:
  `echo | openssl s_client -connect clubsapp.ru:443 -servername clubsapp.ru 2>/dev/null | openssl x509 -noout -dates`.
  Не продлился — порт 80 через прокси не доходит (смотреть `stream-access.log`) или CAA указывает
  на другой аккаунт (шаг 4).
- Логи прокси: `/var/log/nginx/stream-access.log` (адрес клиента, байты, длительность); ротация —
  штатный logrotate nginx.
- **IPv6.** У `clubsapp.ru` нет AAAA, прокси слушает только IPv4. Если AAAA появится — добавить в
  `nginx.conf` `listen [::]:443;` и `listen [::]:80;`; `trustedIPs` менять не нужно, на Hetzner
  прокси ходит по IPv4.
- `ClientIpResolver.TRUSTED_PROXY_HOPS` **не трогать**: прокси в `X-Forwarded-For` не появляется.

## Ловушки

1. nginx с `proxy_protocol on` **до** шага 1 → Traefik принимает строку PROXY за мусор, TLS рвётся,
   через прокси сайт не открывается. Порядок только такой: Traefik → nginx → DNS.
2. Проверять `curl --resolve` нужно с машины **без VPN** — с VPN всё открывается и напрямую.
3. `www.clubsapp.ru` в сертификате нет (SAN только `clubsapp.ru`) — так было и до прокси.

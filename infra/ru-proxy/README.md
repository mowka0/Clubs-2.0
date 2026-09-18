# Российский reverse proxy перед Hetzner

**Что чинит.** Диагностика PO 2026-09-18: с домашних и мобильных провайдеров РФ без VPN не
открываются ни `https://clubsapp.ru`, ни `https://77-42-23-177.sslip.io`, ни `http://77.42.23.177`.
Значит, режется подсеть Hetzner целиком, DNS ни при чём. Из российских дата-центров (check-host,
18.09) сайт отвечает 200 за 19 мс. Нужна точка входа с российским IP, которая пробрасывает трафик
на Hetzner из дата-центра.

Кому это важно: модератору Robokassa (открывает сайт обычным браузером — без прокси откажет) и
пользователям Mini App без VPN — URL в BotFather ведёт на тот же заблокированный IP.

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
DNS обратно, 10 минут). Задержка растёт на один прыжок, ~10–30 мс. Плата за VPS ~300–500 ₽/мес.

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

## Шаги (порядок важен: Traefik → nginx → проверка → CAA → DNS)

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
scp -r infra/ru-proxy root@<RU_IP>:/tmp/
ssh root@<RU_IP> "cd /tmp/ru-proxy && bash install.sh"
```

Скрипт ставит nginx с модулем stream, проверяет достижимость Hetzner, проверяет и кладёт
`nginx.conf`, выключает парольный SSH, включает файрвол (SSH с лимитом, 80, 443) и
автообновления. Идемпотентен, повторный запуск безопасен.

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

Timeweb → домен `clubsapp.ru` → DNS → добавить одну запись типа CAA (флаг `0`, тег `issue`):

```
clubsapp.ru.  CAA  0 issue "letsencrypt.org; accounturi=https://acme-v02.api.letsencrypt.org/acme/acct/3755959236"
```

Одной записи достаточно: без отдельной `issuewild` то же ограничение действует и на
wildcard-сертификаты (RFC 8659).

`accounturi` — аккаунт Let's Encrypt, которым Traefik на Hetzner выпускает и продлевает
сертификат (`jq -r '.letsencrypt.Account.Registration.uri' /data/coolify/proxy/acme.json`).
Чужой аккаунт, даже контролируя порт 80, сертификат не получит; продления Traefik идут как шли.
Если панель Timeweb не принимает параметры после `;` — оставить `0 issue "letsencrypt.org"`
(отсекает другие CA) и раз в месяц смотреть выпуски на `https://crt.sh/?q=clubsapp.ru`.
**Ловушка:** переустановка Coolify или потеря `acme.json` меняет аккаунт — продление начнёт
падать, пока `accounturi` не обновлён.

### 5. Переключить DNS

TTL A-записей — 600 с (проверено на `ns1.timeweb.ru` 18.09; перед переключением убедиться в
панели, что не выше). Timeweb → домен `clubsapp.ru` → DNS: `A @` и `A www` → `<RU_IP>`. За 10
минут разъедется. Проверка: `dig +short clubsapp.ru` = `<RU_IP>`; открыть `https://clubsapp.ru`
с телефона без VPN.

### 6. Дальше по хэндоффу биллинга

BotFather → Mini App URL `https://clubsapp.ru`. Robokassa — модерация магазина.

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

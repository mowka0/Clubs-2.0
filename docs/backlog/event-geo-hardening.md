# Backlog: hardening после фичи event-geo

> Источник: security-ревью feature/event-geo 2026-07-11. Оба пункта — Low, приняты PO
> как осознанный риск на текущем масштабе; не блокировали мерж.

## 1. CSP-заголовок в nginx (расширение доверия к третьей стороне)

Фича впервые вводит исполняемый third-party код: JS API Яндекса грузится скриптом с
`api-maps.yandex.ru` и получает полный доступ к DOM/памяти страницы (JWT живёт в
Zustand-памяти). CSP-заголовка в `frontend/nginx.conf` нет вообще (pre-existing).

Предлагаемый заголовок (devops-задача, проверить на staging — не поломать аватарки/uploads):

```
Content-Security-Policy:
  script-src 'self' https://api-maps.yandex.ru;
  connect-src 'self' https://geocode-maps.yandex.ru https://api-maps.yandex.ru;
  img-src 'self' data: https://static-maps.yandex.ru;
```

(плюс текущие хосты аватарок/загрузок — проверить фактические источники img.)

## 2. Quota-burn DoS на создание событий (публичный ключ + fail-closed)

Ключ геокодера публичный (в бандле), referer-ограничение обходится вне браузера
(`curl -H "Referer: ..."`). Выжигание лимита 100 запросов/сутки → геокодер отдаёт ошибки →
из-за fail-closed организаторы не могут создавать события до конца суток. Данные не
затрагиваются.

При росте масштаба / первом инциденте: серверный прокси-геокодер с rate-limit по
user_id (bucket4j уже в стеке) либо платный тариф Яндекса.

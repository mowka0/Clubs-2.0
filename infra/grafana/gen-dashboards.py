#!/usr/bin/env python3
"""Генератор дашбордов Grafana для Clubs (W5).

Пишет provisioning/dashboards/<uid>.json — пять стартовых экранов из хэндоффа
(воронка, активность клубов, встречи, деньги, удержание) и шестой «Ошибки и сбои»
(PO, 2026-10-15). SQL и раскладка живут здесь, а не в JSON: тысячи строк JSON-обвязки
никто не прочитает, а запросы — главное, что в дашбордах есть. После правки:
`python3 infra/grafana/gen-dashboards.py`, JSON закоммитить.

Соглашения:
- датасорс — uid `clubs-prod` (provisioning/datasources/clubs.yml);
- недели — календарные по Москве, с понедельника (как недельный отчёт, funnel.md § 3.3);
- в запросах с периодом стоит макрос Grafana $__timeFilter(col) — верхний выбор времени
  фильтрует данные; стат-панели «сейчас» периода не знают;
- в таблицах нет ПДн: имена клубов — да, имена людей и username — нет;
- панели по product_event фильтруют по kind или user_id первыми — под индексы
  (kind, created_at) и (user_id, created_at); агрегаты по клубам считаются одним проходом
  по журналу и джойнятся к clubs, а не коррелированным подзапросом на каждый клуб.
"""
from __future__ import annotations

import json
from pathlib import Path

OUT_DIR = Path(__file__).parent / "provisioning" / "dashboards"
DATASOURCE = {"type": "grafana-postgresql-datasource", "uid": "clubs-prod"}
# Версия схемы дашборда Grafana 12/13 (классическая, не v2) — на ней Grafana молча мигрирует вверх.
SCHEMA_VERSION = 41
TAG = "clubs"
# Пороги из product-analytics.md § 2.2: при миллионе строк — агрегировать screen_opened старше
# 180 дней. Байтовый порог — страховка на случай тяжёлых props. Жёлтая зона — 70 % порога.
EVENT_ROWS_LIMIT = 1_000_000
EVENT_BYTES_LIMIT = 1024 * 1024 * 1024
WARN_SHARE = 0.7


def week(col: str) -> str:
    """Начало календарной недели (понедельник 00:00 МСК) как timestamptz — ось времени панелей."""
    return f"date_trunc('week', {col} at time zone 'Europe/Moscow') at time zone 'Europe/Moscow'"


def day(col: str) -> str:
    """Начало календарных суток по Москве как timestamptz — для панелей по дням."""
    return f"date_trunc('day', {col} at time zone 'Europe/Moscow') at time zone 'Europe/Moscow'"


# Ссылочная строка на дашборды с тем же тегом — навигация между экранами.
LINKS = [{
    "type": "dashboards", "tags": [TAG], "title": "Clubs", "asDropdown": False,
    "includeVars": False, "keepTime": True, "icon": "external link", "targetBlank": False,
}]


def target(sql: str, ref: str = "A") -> dict:
    return {
        "refId": ref, "datasource": DATASOURCE, "rawQuery": True, "editorMode": "code",
        "format": "table", "rawSql": sql.strip(),
    }


def panel(kind: str, title: str, sqls: list[str] | str, grid: tuple[int, int, int, int], *,
          description: str = "", unit: str | None = None, options: dict | None = None,
          custom: dict | None = None, overrides: list | None = None,
          thresholds: list | None = None, color: dict | None = None) -> dict:
    x, y, w, h = grid
    if isinstance(sqls, str):
        sqls = [sqls]
    defaults: dict = {"custom": custom or {}}
    if unit:
        defaults["unit"] = unit
    if thresholds:
        defaults["thresholds"] = {"mode": "absolute", "steps": thresholds}
    if color:
        defaults["color"] = color
    return {
        "type": kind, "title": title, "description": description,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "datasource": DATASOURCE,
        "targets": [target(sql, chr(ord("A") + i)) for i, sql in enumerate(sqls)],
        "fieldConfig": {"defaults": defaults, "overrides": overrides or []},
        "options": options or {},
    }


def bars(title, sqls, grid, *, stacked=False, unit=None, overrides=None, description=""):
    """Столбики по неделям: один столбик на точку оси времени (понедельник)."""
    custom = {
        "drawStyle": "bars", "fillOpacity": 80, "lineWidth": 1, "showPoints": "never",
        "stacking": {"mode": "normal" if stacked else "none", "group": "A"},
        "axisPlacement": "auto",
    }
    options = {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
               "tooltip": {"mode": "multi", "sort": "none"}}
    return panel("timeseries", title, sqls, grid, unit=unit, options=options, custom=custom,
                 overrides=overrides, description=description)


def stat(title, sql, grid, *, unit=None, overrides=None, thresholds=None, description=""):
    """Несколько чисел в одной панели: каждая числовая колонка — отдельное значение с подписью."""
    options = {
        "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
        "textMode": "value_and_name", "colorMode": "value" if thresholds else "none",
        "graphMode": "none", "orientation": "auto", "justifyMode": "auto", "wideLayout": True,
    }
    steps = thresholds or [{"color": "text", "value": None}]
    return panel("stat", title, sql, grid, unit=unit, options=options, overrides=overrides,
                 thresholds=steps, description=description)


# Значения, которые в норме равны нулю: зелёный ноль, красное всё остальное.
ZERO_IS_GOOD = [{"color": "green", "value": None}, {"color": "red", "value": 1}]


def table(title, sql, grid, *, overrides=None, description=""):
    options = {"showHeader": True, "cellHeight": "sm", "footer": {"show": False, "reducer": ["sum"]}}
    # cellOptions type auto: текст экранируется, HTML из props не рендерится (product-analytics.md § 6).
    custom = {"align": "auto", "cellOptions": {"type": "auto"}, "filterable": False}
    return panel("table", title, sql, grid, options=options, custom=custom, overrides=overrides,
                 description=description)


def pie(title, sql, grid, *, description=""):
    options = {
        "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": True},
        "pieType": "pie", "displayLabels": ["percent"],
        "legend": {"displayMode": "list", "placement": "right", "showLegend": True},
        "tooltip": {"mode": "single", "sort": "none"},
    }
    return panel("piechart", title, sql, grid, options=options, description=description)


def funnel(title, sql, grid, *, description=""):
    """Горизонтальные полосы — шаги воронки, по полосе на числовую колонку."""
    options = {
        "orientation": "horizontal", "displayMode": "gradient", "showUnfilled": True,
        "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
        "valueMode": "color", "namePlacement": "top", "sizing": "auto", "minVizHeight": 16,
    }
    return panel("bargauge", title, sql, grid, options=options, description=description,
                 color={"mode": "continuous-BlPu"})


def hbar(title, sql, grid, *, description=""):
    """Категории по горизонтали (экраны, кнопки): x — строковая колонка, длина — число."""
    options = {"orientation": "horizontal", "showValue": "auto", "barWidth": 0.8, "groupWidth": 0.7,
               "legend": {"showLegend": False, "displayMode": "list", "placement": "bottom"},
               "tooltip": {"mode": "single", "sort": "none"}}
    return panel("barchart", title, sql, grid, options=options, description=description,
                 custom={"fillOpacity": 80, "lineWidth": 1})


def text(title, markdown, grid):
    """Статичная заметка из репозитория (markdown) — пояснение, что здесь видно, а что нет."""
    return {
        "type": "text", "title": title, "gridPos": {"x": grid[0], "y": grid[1], "w": grid[2], "h": grid[3]},
        "options": {"mode": "markdown", "content": markdown.strip()},
        "fieldConfig": {"defaults": {}, "overrides": []},
    }


def unit_override(field: str, unit: str, **extra) -> dict:
    props = [{"id": "unit", "value": unit}]
    for key, value in extra.items():
        props.append({"id": key, "value": value})
    return {"matcher": {"id": "byName", "options": field}, "properties": props}


def dashboard(uid: str, title: str, panels: list[dict], *, description: str = "",
              time_from: str = "now-90d") -> dict:
    return {
        "uid": uid, "title": title, "description": description, "tags": [TAG],
        "timezone": "Europe/Moscow", "weekStart": "monday", "editable": True,
        "graphTooltip": 1, "refresh": "", "schemaVersion": SCHEMA_VERSION, "version": 1,
        "time": {"from": time_from, "to": "now"},
        "timepicker": {}, "templating": {"list": []}, "annotations": {"list": []},
        "links": LINKS, "panels": panels,
    }


RUB = "currencyRUB"


def rub(expr: str) -> str:
    """Копейки → рубли с двумя знаками (без round numeric даёт хвост из нулей)."""
    return f"round(({expr}) / 100.0, 2)"


# Клубы с привязанным чатом — так считает «состоялось» недельный отчёт (funnel.md § 3.3):
# без условия на бота, встреча прошедшей недели состоялась, даже если бота выгнали вчера.
WITH_CHAT = "exists (select 1 from club_chat_links l where l.club_id = events.club_id)"


# ---------------------------------------------------------------------------------------------
# 1. Воронка — привлечение, запуски Mini App, интро, оплата, размер журнала (funnel.md § 3.3, В1, В2, В6)
# ---------------------------------------------------------------------------------------------
FUNNEL_KINDS = "'bot_started','chat_connected','chat_disconnected','trial_started','checkout_started','payment_succeeded'"

funnel_dash = dashboard("clubs-funnel", "Clubs · Воронка", [
    stat("Привлечение за выбранный период", f"""
select
  count(distinct telegram_id) filter (where kind = 'bot_started') as "Старты бота",
  count(*) filter (where kind = 'chat_connected') as "Чаты подключены",
  count(*) filter (where kind = 'chat_disconnected') as "Чаты отключены",
  count(*) filter (where kind = 'trial_started') as "Первые встречи",
  count(*) filter (where kind = 'checkout_started') as "Чекауты",
  count(*) filter (where kind = 'payment_succeeded') as "Оплаты"
from product_event
where kind in ({FUNNEL_KINDS}) and $__timeFilter(created_at)
""", (0, 0, 24, 4), description="Те же шаги, что в недельном DM-отчёте (funnel.md § 3.3): старты — уникальные telegram_id, остальное — строки журнала."),

    bars("Привлечение по неделям", f"""
select {week('created_at')} as time,
  count(distinct telegram_id) filter (where kind = 'bot_started') as "Старты бота",
  count(*) filter (where kind = 'chat_connected') as "Чаты подключены",
  count(*) filter (where kind = 'trial_started') as "Первые встречи",
  count(*) filter (where kind = 'checkout_started') as "Чекауты",
  count(*) filter (where kind = 'payment_succeeded') as "Оплаты"
from product_event
where kind in ({FUNNEL_KINDS}) and $__timeFilter(created_at)
group by 1 order by 1
""", (0, 4, 16, 8)),

    table("Кампании за период", """
with starts as (
  select coalesce(campaign, 'органика') as campaign, telegram_id, min(created_at) as started_at
  from product_event
  where kind = 'bot_started' and $__timeFilter(created_at)
  group by 1, 2
)
select s.campaign as "Кампания",
  count(*) as "Старты",
  count(*) filter (where exists (
    select 1 from product_event c
    where c.kind = 'chat_connected' and c.telegram_id = s.telegram_id and c.created_at >= s.started_at)) as "Подключили чат",
  count(*) filter (where exists (
    select 1 from product_event p join users u on u.id = p.user_id
    where p.kind = 'payment_succeeded' and u.telegram_id = s.telegram_id and p.created_at >= s.started_at)) as "Оплатили"
from starts s
group by 1 order by 2 desc
limit 50
""", (16, 4, 8, 8), description="Старты бота с меткой /start ad_<slug> за период; «подключили» и «оплатили» — тот же человек в любой момент после старта. Не first-touch, как в DM-отчёте: там метка — первый старт человека за всё время, а конверсии — только недели отчёта."),

    bars("Запуски Mini App по входу", f"""
select {week('created_at')} as time,
  count(*) filter (where props->>'entry' = 'none') as "Напрямую",
  count(*) filter (where props->>'entry' = 'club') as "Из поста клуба",
  count(*) filter (where props->>'entry' = 'event') as "Из поста встречи",
  count(*) filter (where props->>'entry' = 'skladchina') as "Из поста сбора",
  count(*) filter (where props->>'entry' = 'billing') as "Из оплаты",
  count(*) filter (where props->>'entry' = 'invite') as "По приглашению"
from product_event
where kind = 'app_opened' and $__timeFilter(created_at)
group by 1 order by 1
""", (0, 12, 12, 8), stacked=True, description="app_opened.entry — вид startapp-параметра; кнопки-ссылки бота видны только так (product-analytics.md § 3.3)."),

    pie("Платформы запусков за период", """
select coalesce(props->>'platform', '—') as "Платформа", count(*) as "Запусков"
from product_event
where kind = 'app_opened' and $__timeFilter(created_at)
group by 1 order by 2 desc
""", (12, 12, 6, 8)),

    funnel("Интро за период (людей)", """
select
  count(distinct user_id) filter (where kind = 'onboarding_step' and props->>'step' = '1') as "Слайд 1",
  count(distinct user_id) filter (where kind = 'onboarding_step' and props->>'step' = '2') as "Слайд 2",
  count(distinct user_id) filter (where kind = 'onboarding_step' and props->>'step' = '3') as "Слайд 3",
  count(distinct user_id) filter (where kind = 'onboarding_completed') as "«Погнали!»"
from product_event
where kind in ('onboarding_step', 'onboarding_completed') and $__timeFilter(created_at)
""", (18, 12, 6, 8), description="В1: где новичок отваливается в интро."),

    funnel("Оплата за период (клубов)", """
select
  count(distinct club_id) filter (where kind = 'pay_sheet_opened') as "Открыли шит оплаты",
  count(distinct club_id) filter (where kind = 'checkout_started') as "Перешли к оплате",
  count(distinct club_id) filter (where kind = 'payment_succeeded') as "Оплатили"
from product_event
where kind in ('pay_sheet_opened', 'checkout_started', 'payment_succeeded') and $__timeFilter(created_at)
""", (0, 20, 8, 6), description="В6: где теряем на оплате. Считаются клубы, не нажатия."),

    stat("Журнал событий сейчас", """
select (select count(*) from product_event) as "Строк",
  pg_total_relation_size('product_event') as "Размер с индексами"
""", (8, 20, 8, 6),
         thresholds=[{"color": "green", "value": None},
                     {"color": "orange", "value": int(EVENT_ROWS_LIMIT * WARN_SHARE)},
                     {"color": "red", "value": EVENT_ROWS_LIMIT}],
         overrides=[unit_override("Размер с индексами", "bytes", thresholds={
             "mode": "absolute", "steps": [{"color": "green", "value": None},
                                           {"color": "orange", "value": int(EVENT_BYTES_LIMIT * WARN_SHARE)},
                                           {"color": "red", "value": EVENT_BYTES_LIMIT}]})],
         description="Порог 1 млн строк / 1 ГиБ — пора агрегировать screen_opened старше 180 дней (product-analytics.md § 2.2); жёлтая зона — 70 % порога. Те же пороги — в алертах."),

    bars("Строк в журнале в день", f"""
select {day('created_at')} as time,
  count(*) filter (where source = 'app') as "Mini App",
  count(*) filter (where source = 'bot') as "Бот",
  count(*) filter (where source = 'server') as "Сервер"
from product_event
where $__timeFilter(created_at)
group by 1 order by 1
""", (16, 20, 8, 6), stacked=True),
], description="Привлечение (funnel.md § 3.3), запуски и интро Mini App, платёжная воронка, рост журнала.")


# ---------------------------------------------------------------------------------------------
# 2. Активность клубов — WAU, новые, клубы за период, экраны, вступления, кнопки бота (В2, В4, В5)
# ---------------------------------------------------------------------------------------------
activity_dash = dashboard("clubs-activity", "Clubs · Активность клубов", [
    stat("Сейчас", """
select
  (select count(*) from clubs where is_active) as "Клубов",
  (select count(*) from clubs c join club_chat_links l on l.club_id = c.id
     where c.is_active and l.bot_status in ('member', 'administrator')) as "С чатом",
  (select count(*) from users) as "Пользователей",
  (select count(distinct user_id) from memberships where status = 'active') as "Участников клубов",
  (select count(distinct user_id) from product_event
     where kind = 'app_opened' and user_id is not null and created_at >= now() - interval '7 days') as "WAU (7 дней)",
  (select count(distinct user_id) from product_event
     where kind = 'app_opened' and user_id is not null and created_at >= now() - interval '28 days') as "MAU (28 дней)"
""", (0, 0, 24, 4), description="«С чатом» — бот в чате (member/administrator), как в биллинге и недельном отчёте."),

    bars("Активные пользователи и клубы по неделям", f"""
select {week('created_at')} as time,
  count(distinct user_id) filter (where kind = 'app_opened') as "Пользователей (запускали приложение)",
  count(distinct club_id) filter (where kind = 'screen_opened' and club_id is not null) as "Клубов (открывали экраны клуба)"
from product_event
where user_id is not null and kind in ('app_opened', 'screen_opened') and $__timeFilter(created_at)
group by 1 order by 1
""", (0, 4, 12, 8), description="Журнал запусков ведётся с 2026-10-15 — раньше недель нет."),

    bars("Новые пользователи, клубы и вступления по неделям", [f"""
select {week('created_at')} as time, count(*) as "Новые пользователи"
from users where $__timeFilter(created_at) group by 1 order by 1
""", f"""
select {week('created_at')} as time, count(*) as "Новые клубы"
from clubs where $__timeFilter(created_at) group by 1 order by 1
""", f"""
select {week('joined_at')} as time, count(*) as "Вступлений в клубы"
from memberships where $__timeFilter(joined_at) group by 1 order by 1
"""], (12, 4, 12, 8), description="По таблицам сущностей — вся история, не только с 2026-10-15."),

    table("Клубы за период", """
with screens as (
  select club_id, count(distinct user_id) as people, count(*) as opens
  from product_event
  where kind = 'screen_opened' and club_id is not null and $__timeFilter(created_at)
  group by 1
),
members as (
  select club_id, count(*) as cnt from memberships where status = 'active' group by 1
),
new_events as (
  select club_id, count(*) as cnt from events where $__timeFilter(created_at) group by 1
),
new_skladchinas as (
  select club_id, count(*) as cnt from skladchinas where $__timeFilter(created_at) group by 1
)
select c.name as "Клуб",
  case when l.bot_status in ('member', 'administrator') then 'да' else 'нет' end as "Чат",
  coalesce(m.cnt, 0) as "Участников",
  coalesce(s.people, 0) as "Открывали (людей)",
  coalesce(s.opens, 0) as "Экранов",
  coalesce(e.cnt, 0) as "Встреч создано",
  coalesce(k.cnt, 0) as "Сборов создано"
from clubs c
left join club_chat_links l on l.club_id = c.id
left join members m on m.club_id = c.id
left join screens s on s.club_id = c.id
left join new_events e on e.club_id = c.id
left join new_skladchinas k on k.club_id = c.id
where c.is_active
order by 4 desc, 3 desc
limit 50
""", (0, 12, 14, 10)),

    hbar("Экраны за период", """
select coalesce(props->>'screen', '—') as "Экран", count(*) as "Открытий"
from product_event
where kind = 'screen_opened' and $__timeFilter(created_at)
group by 1 order by 2 desc
limit 20
""", (14, 12, 10, 10), description="В4: какими экранами пользуются (имена — product-analytics.md § 3.4)."),

    bars("Вступления по способу", f"""
select {week('created_at')} as time,
  count(*) filter (where props->>'via' = 'open') as "Свободно",
  count(*) filter (where props->>'via' = 'invite') as "По приглашению",
  count(*) filter (where props->>'via' = 'application') as "По заявке"
from product_event
where kind = 'club_joined' and $__timeFilter(created_at)
group by 1 order by 1
""", (0, 22, 12, 7), stacked=True),

    table("Кнопки бота за период", """
select props->>'button' as "Кнопка", count(*) as "Нажатий", count(distinct telegram_id) as "Людей"
from product_event
where kind = 'bot_button_clicked' and $__timeFilter(created_at)
group by 1 order by 2 desc
""", (12, 22, 9, 7), description="В5: работают ли inline-кнопки бота. Кнопки-ссылки сюда не попадают — они видны как запуски «из поста»."),

    stat("Поддержка за период", """
select count(*) as "Сообщений «Сообщить о проблеме»"
from product_event
where kind = 'feedback_sent' and $__timeFilter(created_at)
""", (21, 22, 3, 7)),
], description="Кто и сколько пользуется: WAU/MAU, новые, клубы за период, экраны, вступления, кнопки бота.")


# ---------------------------------------------------------------------------------------------
# 3. Встречи — создано/состоялось/отменено, голоса, явка, чаты без встреч, путь до первой встречи (В3, В4)
# ---------------------------------------------------------------------------------------------
events_dash = dashboard("clubs-events", "Clubs · Встречи", [
    stat("За выбранный период", f"""
select
  (select count(*) from events where $__timeFilter(created_at)) as "Создано",
  (select count(*) from events where status = 'completed' and {WITH_CHAT} and $__timeFilter(event_datetime)) as "Состоялось",
  (select count(*) from events where status = 'cancelled' and $__timeFilter(event_datetime)) as "Отменено",
  (select count(*) from events where status = 'completed' and attendance_marked and $__timeFilter(event_datetime)) as "С отметкой явки",
  (select count(*) from events where status not in ('completed', 'cancelled') and event_datetime > now()) as "Предстоит (сейчас)"
""", (0, 0, 24, 4), description="«Состоялось» — как в недельном отчёте (funnel.md § 3.3): status = completed по дате встречи, только клубы с привязанным чатом, явка не обязательна. Остальные числа — по всем клубам."),

    bars("По неделям", [f"""
select {week('created_at')} as time, count(*) as "Создано"
from events where $__timeFilter(created_at) group by 1 order by 1
""", f"""
select {week('event_datetime')} as time, count(*) as "Состоялось (клубы с чатом)"
from events where status = 'completed' and {WITH_CHAT} and $__timeFilter(event_datetime) group by 1 order by 1
""", f"""
select {week('event_datetime')} as time, count(*) as "Отменено"
from events where status = 'cancelled' and $__timeFilter(event_datetime) group by 1 order by 1
"""], (0, 4, 12, 8)),

    bars("Голоса по неделям", f"""
select {week('stage_1_timestamp')} as time,
  count(*) filter (where stage_1_vote = 'going') as "Пойду",
  count(*) filter (where stage_1_vote = 'maybe') as "Может быть",
  count(*) filter (where stage_1_vote = 'not_going') as "Не пойду"
from event_responses
where stage_1_timestamp is not null and $__timeFilter(stage_1_timestamp)
group by 1 order by 1
""", (12, 4, 12, 8), stacked=True, description="Последний голос каждого участника по времени голоса (event_responses). Каждое нажатие, включая смену голоса, — vote_cast в журнале с 2026-10-15."),

    stat("Явка за период", """
select
  count(*) filter (where r.attendance = 'attended') as "Пришли",
  count(*) filter (where r.attendance = 'absent') as "Не пришли",
  count(*) filter (where r.attendance = 'disputed') as "Спорных",
  round(100.0 * count(*) filter (where r.attendance = 'attended')
        / nullif(count(*) filter (where r.attendance in ('attended', 'absent')), 0), 1) as "Явка, %"
from event_responses r
join events e on e.id = r.event_id
where e.status = 'completed' and e.attendance_marked and r.attendance is not null
  and $__timeFilter(e.event_datetime)
""", (0, 12, 8, 7), overrides=[unit_override("Явка, %", "percent")],
         description="Только встречи с отметкой явки; «спорных» в долю не входят."),

    bars("Явка по неделям", f"""
select {week('e.event_datetime')} as time,
  count(*) filter (where r.attendance = 'attended') as "Пришли",
  count(*) filter (where r.attendance = 'absent') as "Не пришли",
  round(100.0 * count(*) filter (where r.attendance = 'attended')
        / nullif(count(*) filter (where r.attendance in ('attended', 'absent')), 0), 1) as "Явка, %"
from event_responses r
join events e on e.id = r.event_id
where e.status = 'completed' and e.attendance_marked and r.attendance is not null
  and $__timeFilter(e.event_datetime)
group by 1 order by 1
""", (8, 12, 16, 7), overrides=[{
        "matcher": {"id": "byName", "options": "Явка, %"},
        "properties": [{"id": "unit", "value": "percent"},
                       {"id": "custom.drawStyle", "value": "line"},
                       {"id": "custom.axisPlacement", "value": "right"},
                       {"id": "custom.lineWidth", "value": 2},
                       {"id": "custom.fillOpacity", "value": 0},
                       {"id": "custom.stacking", "value": {"mode": "none", "group": "B"}},
                       {"id": "min", "value": 0}, {"id": "max", "value": 100}],
    }]),

    table("Чаты без встреч 30 дней", """
select c.name as "Клуб",
  l.linked_at as "Чат привязан",
  (current_date - (l.linked_at at time zone 'Europe/Moscow')::date) as "Дней с привязки",
  (select max(e.event_datetime) from events e where e.club_id = c.id and e.status <> 'cancelled') as "Последняя встреча"
from club_chat_links l
join clubs c on c.id = l.club_id
where c.is_active and l.bot_status in ('member', 'administrator')
  and l.linked_at <= now() - interval '30 days'
  and not exists (
    select 1 from events e
    where e.club_id = c.id and e.status <> 'cancelled'
      and e.event_datetime >= now() - interval '30 days' and e.event_datetime < now() + interval '30 days')
order by l.linked_at
""", (0, 19, 12, 8), description="Та же метрика, что строка «смотреть сюда» в недельном отчёте: ни прошедшей, ни запланированной встречи за ±30 дней."),

    stat("От привязки чата до первой встречи", """
with first_event as (
  select l.club_id, l.linked_at, min(e.created_at) as first_created
  from club_chat_links l
  join events e on e.club_id = l.club_id and e.created_at >= l.linked_at and e.status <> 'cancelled'
  group by 1, 2
)
select
  count(*) as "Чатов с встречей",
  round((percentile_cont(0.5) within group (order by extract(epoch from first_created - linked_at) / 86400))::numeric, 1) as "Медиана, дней",
  round(avg(extract(epoch from first_created - linked_at) / 86400)::numeric, 1) as "Среднее, дней",
  (select count(*) from club_chat_links l2
     join clubs c2 on c2.id = l2.club_id
     where c2.is_active and l2.bot_status in ('member', 'administrator')
       and not exists (select 1 from events e2 where e2.club_id = l2.club_id and e2.status <> 'cancelled')) as "Чатов без единой встречи"
from first_event
""", (12, 19, 6, 8), description="В3: доходит ли чат до первой (не отменённой) встречи и за сколько дней. Вся история по club_chat_links.linked_at."),

    table("Форматы за период", """
with going as (
  select event_id, count(*) as cnt from event_responses where stage_1_vote = 'going' group by 1
)
select case when e.participant_limit is null then 'Открытая' else 'С местами' end as "Формат",
  count(*) as "Встреч",
  round(avg(coalesce(g.cnt, 0)), 1) as "«Пойду» в среднем"
from events e
left join going g on g.event_id = e.id
where $__timeFilter(e.created_at)
group by 1 order by 2 desc
""", (18, 19, 6, 8)),
], description="Создано, состоялось, отменено; голоса; явка; чаты без встреч; дни от привязки чата до первой встречи.")


# ---------------------------------------------------------------------------------------------
# 4. Деньги — подписки и MRR, оплаты Robokassa, платёжная воронка, долги и сборы (В6)
# ---------------------------------------------------------------------------------------------
money_dash = dashboard("clubs-money", "Clubs · Деньги", [
    stat("Подписки сейчас и оплаты за период", f"""
with price as (
  select price_kopecks from subscription_pricing
  where plan = 'CHAT' and effective_from <= now()
  order by effective_from desc limit 1
)
select
  (select count(*) from service_subscription
     where subject_club_id is not null and status in ('ACTIVE', 'CANCELLED_PENDING_END')) as "Платят (чатов)",
  {rub("(select count(*) from service_subscription where subject_club_id is not null and status = 'ACTIVE') * (select price_kopecks from price)")} as "MRR, ₽",
  (select count(*) from service_subscription where subject_club_id is not null and status = 'PAST_DUE') as "В грейсе (PAST_DUE)",
  (select count(*) from service_subscription where subject_club_id is not null and status = 'CANCELLED_PENDING_END') as "Отменили, доживают",
  {rub("select coalesce(sum(amount_kopecks), 0) from platform_payment where status = 'SUCCEEDED' and $__timeFilter(paid_at)")} as "Оплачено за период, ₽",
  (select round(coalesce(sum(provider_fee), 0), 2) from platform_payment
     where status = 'SUCCEEDED' and $__timeFilter(paid_at)) as "Комиссия Robokassa, ₽"
""", (0, 0, 24, 4), overrides=[unit_override("MRR, ₽", RUB), unit_override("Оплачено за период, ₽", RUB),
                                unit_override("Комиссия Robokassa, ₽", RUB)],
         description="«Платят» и MRR — определения funnel.md § 3.3: ACTIVE × цена плана CHAT; отменившие не продлятся."),

    bars("Оплаты по неделям", f"""
select {week('paid_at')} as time,
  {rub("coalesce(sum(amount_kopecks) filter (where kind = 'MOTHER'), 0)")} as "Со страницы оплаты",
  {rub("coalesce(sum(amount_kopecks) filter (where kind = 'RECURRING'), 0)")} as "Автосписания"
from platform_payment
where status = 'SUCCEEDED' and $__timeFilter(paid_at)
group by 1 order by 1
""", (0, 4, 12, 8), stacked=True, unit=RUB),

    bars("Счета по статусу", f"""
select {week('created_at')} as time,
  count(*) filter (where status = 'SUCCEEDED') as "Оплачены",
  count(*) filter (where status = 'FAILED') as "Не оплачены",
  count(*) filter (where status = 'PENDING') as "Висят"
from platform_payment
where $__timeFilter(created_at)
group by 1 order by 1
""", (12, 4, 6, 8), stacked=True),

    pie("Способ оплаты за период", """
select coalesce(payment_method, '—') as "Способ", count(*) as "Оплат"
from platform_payment
where status = 'SUCCEEDED' and $__timeFilter(paid_at)
group by 1 order by 2 desc
""", (18, 4, 6, 8), description="BankCard ⇒ карта сохранена, автопродление возможно; SBP — нет (platform-billing.md)."),

    table("Подписки чатов (кроме завершённых)", """
select c.name as "Клуб",
  s.status as "Статус",
  s.current_period_end as "Период до",
  case when s.autopay then 'да' else 'нет' end as "Автопродление",
  case when s.autopay_possible then 'да' else 'нет' end as "Карта сохранена",
  s.charge_attempts as "Попыток списания",
  s.created_at as "Оформлена"
from service_subscription s
join clubs c on c.id = s.subject_club_id
where s.status <> 'ENDED'
order by s.current_period_end
""", (0, 12, 14, 9)),

    funnel("Платёжная воронка за период (клубов)", """
select
  count(distinct club_id) filter (where kind = 'trial_started') as "Бесплатный период начат",
  count(distinct club_id) filter (where kind = 'paywall_seen') as "Упёрлись в пейволл",
  count(distinct club_id) filter (where kind = 'pay_sheet_opened') as "Открыли шит оплаты",
  count(distinct club_id) filter (where kind = 'checkout_started') as "Перешли к оплате",
  count(distinct club_id) filter (where kind = 'payment_succeeded') as "Оплатили",
  count(distinct club_id) filter (where kind = 'subscription_ended') as "Подписка закончилась"
from product_event
where kind in ('trial_started', 'paywall_seen', 'pay_sheet_opened', 'checkout_started', 'payment_succeeded', 'subscription_ended')
  and $__timeFilter(created_at)
""", (14, 12, 10, 9), description="В6. Шаги — product-analytics.md § 3.1 и § 3.2; считаются клубы."),

    stat("Долги сейчас", f"""
select
  count(*) filter (where status in ('waiting', 'promised', 'claimed')) as "Открытых",
  {rub("coalesce(sum(amount_kopecks) filter (where status in ('waiting', 'promised', 'claimed')), 0)")} as "Открытых, ₽",
  count(*) filter (where status in ('waiting', 'promised') and due_at < now()) as "Просрочено",
  count(*) filter (where status = 'claimed') as "Ждут «Получил»"
from debts
where debtor_id <> creditor_id
""", (0, 21, 8, 7), overrides=[unit_override("Открытых, ₽", RUB)],
         description="Доля создателя сбора (должник = получатель) исключена: она закрывается сразу."),

    bars("Долги по неделям", [f"""
select {week('created_at')} as time, {rub("sum(amount_kopecks)")} as "Выставлено"
from debts where debtor_id <> creditor_id and $__timeFilter(created_at) group by 1 order by 1
""", f"""
select {week('confirmed_at')} as time, {rub("sum(amount_kopecks)")} as "Получено"
from debts where debtor_id <> creditor_id and status = 'received' and confirmed_at is not null
  and $__timeFilter(confirmed_at) group by 1 order by 1
"""], (8, 21, 10, 7), unit=RUB),

    bars("Сборы по виду", f"""
select {week('created_at')} as time,
  count(*) filter (where kind = 'shared') as "Скинуться",
  count(*) filter (where kind = 'per_head') as "Кто берёт?",
  count(*) filter (where kind = 'voluntary') as "По желанию"
from skladchinas
where $__timeFilter(created_at)
group by 1 order by 1
""", (18, 21, 6, 7), stacked=True),
], description="Подписки чатов и MRR, оплаты Robokassa, платёжная воронка, долги и сборы.")


# ---------------------------------------------------------------------------------------------
# 5. Удержание — когорты по неделе первого запуска, состав недельной аудитории, затихшие клубы (В7)
# ---------------------------------------------------------------------------------------------
WEEKLY_USERS = f"""
with wk as (
  select distinct user_id, {week('created_at')} as w
  from product_event
  where kind = 'app_opened' and user_id is not null
)"""


def cohort_column(offset: int) -> str:
    return f"""
  case when {week('now()')} >= f.cohort + interval '{7 * offset} days'
       then round(100.0 * count(*) filter (where exists (
              select 1 from acts a where a.user_id = f.user_id and a.w = f.cohort + interval '{7 * offset} days'))
            / count(*), 0) end as "Нед. +{offset}" """


retention_dash = dashboard("clubs-retention", "Clubs · Удержание", [
    stat("Сейчас", WEEKLY_USERS + f"""
select
  (select count(*) from wk where w = {week('now()')}) as "На этой неделе",
  (select count(*) from wk where w = {week('now()')} - interval '7 days') as "На прошлой неделе",
  (select count(*) from wk a
     where a.w = {week('now()')}
       and exists (select 1 from wk b where b.user_id = a.user_id and b.w = a.w - interval '7 days')) as "Были и на прошлой",
  (select count(distinct user_id) from product_event
     where kind = 'app_opened' and user_id is not null and created_at >= now() - interval '28 days') as "MAU (28 дней)",
  (select round(avg(d), 1) from (
     select count(distinct user_id) as d from product_event
     where kind = 'app_opened' and user_id is not null and created_at >= now() - interval '28 days'
     group by {day('created_at')}) t) as "DAU в среднем по дням с запусками (28 дней)"
""", (0, 0, 24, 4), description="Недели календарные по Москве. Журнал запусков ведётся с 2026-10-15. DAU усредняется по дням, когда запуски были, — дни без запусков в среднее не входят."),

    table("Когорты по неделе первого запуска, % вернувшихся", f"""
with first as (
  select user_id, min({week('created_at')}) as cohort
  from product_event
  where kind = 'app_opened' and user_id is not null
  group by 1
),
acts as (
  select distinct user_id, {week('created_at')} as w
  from product_event
  where kind = 'app_opened' and user_id is not null
)
select to_char(f.cohort at time zone 'Europe/Moscow', 'DD.MM.YYYY') as "Неделя с",
  count(*) as "Людей",{cohort_column(1)},{cohort_column(2)},{cohort_column(3)},{cohort_column(4)}
from first f
where $__timeFilter(f.cohort)
group by f.cohort
order by f.cohort desc
""", (0, 4, 24, 9), overrides=[{
        "matcher": {"id": "byRegexp", "options": "Нед\\. .*"},
        "properties": [{"id": "unit", "value": "percent"}, {"id": "min", "value": 0}, {"id": "max", "value": 100},
                       {"id": "custom.cellOptions", "value": {"type": "color-background", "mode": "gradient"}},
                       {"id": "color", "value": {"mode": "continuous-BlPu"}}],
    }], description="В7: кто из запустивших приложение на неделе N запустил на неделе N+k. Пусто — неделя ещё не прошла. Все, кто пользовался приложением до 2026-10-15, попадают в первую когорту."),

    bars("Состав недельной аудитории", WEEKLY_USERS + """,
first as (select user_id, min(w) as first_w from wk group by 1)
select wk.w as time,
  count(*) filter (where wk.w = f.first_w) as "Новые",
  count(*) filter (where wk.w > f.first_w and exists (
    select 1 from wk p where p.user_id = wk.user_id and p.w = wk.w - interval '7 days')) as "Были и на прошлой неделе",
  count(*) filter (where wk.w > f.first_w and not exists (
    select 1 from wk p where p.user_id = wk.user_id and p.w = wk.w - interval '7 days')) as "Вернулись после перерыва"
from wk
join first f on f.user_id = wk.user_id
where $__timeFilter(wk.w)
group by 1 order by 1
""", (0, 13, 12, 8), stacked=True),

    table("Клубы с чатом, в которых затихли", """
with last_seen as (
  select club_id, max(created_at) as at
  from product_event
  where kind = 'screen_opened' and club_id is not null
  group by 1
),
members as (
  select club_id, count(*) as cnt from memberships where status = 'active' group by 1
)
select c.name as "Клуб",
  s.at as "Последняя активность в приложении",
  (current_date - (s.at at time zone 'Europe/Moscow')::date) as "Дней тишины",
  coalesce(m.cnt, 0) as "Участников"
from clubs c
join club_chat_links l on l.club_id = c.id
left join last_seen s on s.club_id = c.id
left join members m on m.club_id = c.id
where c.is_active and l.bot_status in ('member', 'administrator')
  and (s.at is null or s.at < now() - interval '14 days')
order by s.at nulls first
""", (12, 13, 12, 8), description="Никто не открывал экраны клуба 14 дней (или ни разу с 2026-10-15)."),
], description="Когорты и возвраты по запускам Mini App (app_opened), затихшие клубы.")


# ---------------------------------------------------------------------------------------------
# 6. Ошибки и сбои — что в базе выглядит как поломка или требует действия (PO, 2026-10-15)
# ---------------------------------------------------------------------------------------------
# Признак «функция чата включена, а права у бота нет»: бот молча не сможет закрепить/впустить/
# замьютить — организатор видит это только в «Проверить права» (club-chat-link.md).
CHAT_RIGHTS_GAP = """(l.live_pin_enabled and not l.can_pin_messages)
     or (l.door_enabled and not l.can_invite_users)
     or (l.strict_mode_enabled and not l.can_restrict_members)
     or (l.award_tags_enabled and not l.can_manage_tags)"""

errors_dash = dashboard("clubs-errors", "Clubs · Ошибки и сбои", [
    stat("Платежи и подписки сейчас", """
select
  (select count(*) from platform_payment
     where status = 'PENDING' and created_at < now() - interval '6 hours') as "Счета висят дольше 6 ч",
  (select count(*) from platform_payment
     where status = 'FAILED' and created_at >= now() - interval '7 days') as "Не оплачены за 7 дней",
  (select count(*) from service_subscription
     where subject_club_id is not null and status = 'PAST_DUE') as "Подписки в грейсе",
  (select count(*) from service_subscription
     where subject_club_id is not null and status in ('ACTIVE', 'PAST_DUE') and charge_attempts >= 1) as "Списание на ретрае"
""", (0, 0, 12, 5), thresholds=ZERO_IS_GOOD,
         description="В норме всё по нулям. «Висят дольше 6 ч» — шедулер должен был перевести счёт в FAILED (BILLING_PENDING_TIMEOUT_HOURS); «в грейсе» — списание не прошло, идут ретраи 0/+1/+3 дня (platform-billing.md)."),

    stat("Чаты, встречи, долги сейчас", f"""
select
  (select count(*) from club_chat_links l join clubs c on c.id = l.club_id
     where c.is_active and l.bot_status not in ('member', 'administrator')) as "Бота выгнали из чата",
  (select count(*) from club_chat_links l join clubs c on c.id = l.club_id
     where c.is_active and l.bot_status = 'administrator' and ({CHAT_RIGHTS_GAP})) as "Функция включена без права",
  (select count(*) from event_chat_pins where message_id is null and closed_at is null) as "Статус встречи не ушёл в чат",
  (select count(*) from event_responses r join events e on e.id = r.event_id
     where r.attendance = 'disputed' and not e.attendance_finalized) as "Открытых споров о явке",
  (select count(*) from events
     where status = 'completed' and attendance_reminder_sent and not attendance_marked
       and event_datetime < now() - interval '3 days') as "Явка не отмечена 3+ дня",
  (select count(*) from debts where status = 'claimed' and claimed_at < now() - interval '48 hours') as "«Отдал» без ответа 48 ч+",
  (select count(*) from debt_settlements where status = 'claimed' and claimed_at < now() - interval '48 hours') as "Сальдо без ответа 48 ч+"
""", (12, 0, 12, 5), thresholds=ZERO_IS_GOOD,
         description="Что требует действия: бот без чата или без права на включённую функцию; пост бота не отправился; организаторы не отмечают явку и не отвечают на «Отдал»."),

    bars("Платёжные сбои по неделям", f"""
select {week('created_at')} as time,
  count(*) filter (where kind = 'MOTHER' and status = 'FAILED') as "Счёт со страницы оплаты не оплачен",
  count(*) filter (where kind = 'RECURRING' and status = 'FAILED') as "Автосписание не прошло"
from platform_payment
where status = 'FAILED' and $__timeFilter(created_at)
group by 1 order by 1
""", (0, 5, 12, 8), stacked=True, description="FAILED = провайдер отказал или неоплаченный счёт протух. Автосписание падает, если карта не сохранена или отклонена банком."),

    bars("Отключения и концы подписок по неделям", f"""
select {week('created_at')} as time,
  count(*) filter (where kind = 'chat_disconnected') as "Чат отвязан или бота выгнали",
  count(*) filter (where kind = 'subscription_ended') as "Подписка закончилась",
  count(*) filter (where kind = 'paywall_seen') as "Упёрлись в пейволл"
from product_event
where kind in ('chat_disconnected', 'subscription_ended', 'paywall_seen') and $__timeFilter(created_at)
group by 1 order by 1
""", (12, 5, 12, 8), stacked=True),

    table("Чаты с проблемами", f"""
select c.name as "Клуб",
  l.bot_status as "Бот в чате",
  case when l.live_pin_enabled and not l.can_pin_messages then 'да' else '' end as "Закреп без права",
  case when l.door_enabled and not l.can_invite_users then 'да' else '' end as "Дверь без права",
  case when l.strict_mode_enabled and not l.can_restrict_members then 'да' else '' end as "Строгий режим без права",
  case when l.award_tags_enabled and not l.can_manage_tags then 'да' else '' end as "Теги без права",
  case when l.history_visible_to_new_members = false then 'скрыта' else '' end as "История для новичков",
  l.updated_at as "Права обновлены"
from club_chat_links l
join clubs c on c.id = l.club_id
where c.is_active
  and (l.bot_status not in ('member', 'administrator') or ({CHAT_RIGHTS_GAP}) or l.history_visible_to_new_members = false)
order by l.updated_at desc
""", (0, 13, 12, 9), description="Скрытая история — новички не видят закрепов; бот включить её не может, только организатор в настройках группы (docs/backlog/chat-history-hidden-for-new-members.md)."),

    table("Счета с проблемами за период", f"""
select c.name as "Клуб",
  p.kind as "Вид",
  p.status as "Статус",
  {rub("p.amount_kopecks")} as "Сумма, ₽",
  p.inv_id as "InvId",
  p.created_at as "Выставлен",
  p.payment_method as "Способ"
from platform_payment p
join clubs c on c.id = p.club_id
where p.status in ('FAILED', 'PENDING') and $__timeFilter(p.created_at)
order by p.created_at desc
limit 100
""", (12, 13, 12, 9), overrides=[unit_override("Сумма, ₽", RUB)],
         description="InvId — номер счёта в кабинете Robokassa, по нему искать причину отказа."),

    bars("Срывы по неделям", [f"""
select {week('occurred_at')} as time,
  count(*) filter (where kind in ('no_show', 'open_no_show')) as "Обещал и не пришёл",
  count(*) filter (where kind = 'spectator') as "«Может быть» и не пришёл",
  count(*) filter (where kind = 'late_decline_uncovered') as "Поздний отказ без замены",
  count(*) filter (where kind = 'skladchina_expired') as "Долг просрочен (−40)"
from reputation_ledger
where points < 0 and $__timeFilter(occurred_at)
group by 1 order by 1
""", f"""
select {week('closed_at')} as time, count(*) as "Сборы отменены"
from skladchinas where status = 'cancelled' and closed_at is not null and $__timeFilter(closed_at) group by 1 order by 1
""", f"""
select {week('event_datetime')} as time, count(*) as "Встречи отменены"
from events where status = 'cancelled' and $__timeFilter(event_datetime) group by 1 order by 1
"""], (0, 22, 16, 8), description="Отрицательные исходы леджера репутации по времени поведения (reputation.md), отменённые сборы и встречи."),

    text("Чего здесь нет", """
**Технические ошибки** — 5xx бэкенда, исключения, сбои бота и недоставленные DM — в базе не
хранятся: они в логах контейнеров (Coolify → приложение → `backend` → Logs; уровни — `LOGGING_LEVEL_*`).
В Grafana они появятся после подключения Loki + Alloy — отдельная девопс-задача (~300 МБ памяти,
ещё один ресурс Coolify).

**Доступность снаружи** (сайт не открывается из РФ, сертификат) — внешний UptimeRobot на
`clubsmeet.com`, не этот дашборд.
""", (16, 22, 8, 8)),
], description="Что в базе выглядит как поломка или требует действия: платежи, подписки, права бота, споры, долги без ответа, срывы.")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for dash in (funnel_dash, activity_dash, events_dash, money_dash, retention_dash, errors_dash):
        for i, p in enumerate(dash["panels"], start=1):
            p["id"] = i
        path = OUT_DIR / f"{dash['uid']}.json"
        path.write_text(json.dumps(dash, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{path.relative_to(Path(__file__).parent)}: {len(dash['panels'])} panels")


if __name__ == "__main__":
    main()

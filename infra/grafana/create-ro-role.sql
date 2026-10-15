-- Read-only роль grafana_ro для датасорса Grafana. Повторный запуск безопасен и сходится к этому
-- файлу: лишние права снимаются, нужные выдаются. Запускается через create-ro-role.sh (psql внутри
-- контейнера базы); пароль приходит переменной окружения GRAFANA_RO_PASSWORD (\getenv), не через argv.
--
-- Граница — права: SELECT только на таблицы и колонки, которые читают дашборды (gen-dashboards.py);
-- таблицы с ПДн и секретами (реквизиты, инвайт-ссылки, скриншоты оплат, заметки, тексты) — по
-- колонкам. Ни INSERT/UPDATE/DELETE, ни CREATE в схеме. Страховки на уровне роли (транзакции только
-- на чтение, таймаут, лимит соединений) — от случайной тяжёлой панели, а не защита: сессия может
-- снять их сама.
--
-- Новая колонка или таблица в дашборде → строка GRANT здесь → повторный запуск скрипта.
-- Проверка полноты: scratch-прогон всех SQL панелей под grafana_ro (README § 3).

\getenv password GRAFANA_RO_PASSWORD

select format('create role grafana_ro login password %L', :'password')
where not exists (select 1 from pg_roles where rolname = 'grafana_ro')
\gexec

-- Пароль задаётся всегда: при первом запуске — тот же, что в create role; при --rotate — новый.
alter role grafana_ro with login password :'password';

alter role grafana_ro set default_transaction_read_only = on;
alter role grafana_ro set statement_timeout = '30s';
alter role grafana_ro connection limit 8;

grant connect on database :"dbname" to grafana_ro;
grant usage on schema public to grafana_ro;
-- В public создаёт объекты только владелец (PG 16 так делает по умолчанию — закрепляем явно).
revoke create on schema public from public;

-- Сначала снять всё, что могла выдать прежняя редакция («SELECT на все таблицы» и права на будущие
-- таблицы), затем выдать ровно нужное.
alter default privileges for role :"owner" in schema public revoke select on tables from grafana_ro;
revoke select on all tables in schema public from grafana_ro;

-- Таблицы без ПДн и секретов — целиком.
grant select on product_event, platform_payment, subscription_pricing, reputation_ledger to grafana_ro;

-- Таблицы с ПДн или секретами — только колонки, которые читают дашборды.
grant select (id, telegram_id, created_at) on users to grafana_ro;
grant select (id, name, is_active, created_at) on clubs to grafana_ro;
grant select (club_id, bot_status, linked_at, updated_at,
              live_pin_enabled, can_pin_messages, door_enabled, can_invite_users,
              strict_mode_enabled, can_restrict_members, award_tags_enabled, can_manage_tags,
              history_visible_to_new_members) on club_chat_links to grafana_ro;
grant select (club_id, user_id, status, joined_at) on memberships to grafana_ro;
grant select (id, club_id, status, event_datetime, created_at, participant_limit,
              attendance_marked, attendance_finalized, attendance_reminder_sent) on events to grafana_ro;
grant select (event_id, stage_1_vote, stage_1_timestamp, attendance) on event_responses to grafana_ro;
grant select (event_id, message_id, closed_at) on event_chat_pins to grafana_ro;
grant select (subject_club_id, status, current_period_end, autopay, autopay_possible,
              charge_attempts, created_at) on service_subscription to grafana_ro;
grant select (club_id, kind, status, created_at, closed_at) on skladchinas to grafana_ro;
grant select (status, amount_kopecks, due_at, debtor_id, creditor_id,
              created_at, confirmed_at, claimed_at) on debts to grafana_ro;
grant select (status, claimed_at) on debt_settlements to grafana_ro;

#!/bin/sh
# Создаёт (или обновляет) read-only роль grafana_ro в базе ресурса Coolify и кладёт её пароль
# в файл root на сервере. Запускать НА VPS из каталога с create-ro-role.sql:
#
#   sh create-ro-role.sh <id ресурса Coolify>            # создать/подтвердить роль и права
#   sh create-ro-role.sh <id ресурса Coolify> --rotate   # то же с новым паролем
#   sh create-ro-role.sh <id ресурса Coolify> --check    # только проверка: вход и «писать нельзя»
#
# Повторный запуск без --rotate переиспользует пароль из файла (роль и права просто
# подтверждаются). После --rotate обновить CLUBS_DB_PASSWORD в env Grafana в Coolify и
# перезапустить Grafana.
#
# Пароль не печатается и не попадает в аргументы процессов: в контейнер он уходит первой
# строкой stdin, оттуда — в переменную окружения psql. Прочитать его — cat файла, путь печатается.
set -eu

RESOURCE_ID=${1:?usage: $0 <coolify-resource-id> [--rotate|--check]}
MODE=${2:-}
PASS_FILE="/root/.clubs-grafana-ro.${RESOURCE_ID}.pass"
cd "$(dirname "$0")"

# Якоря в фильтре: name= у docker — поиск по подстроке, а id другого ресурса мог бы совпасть
# по префиксу; контейнер должен быть ровно один.
CONTAINER=$(docker ps -q -f "name=^postgres-${RESOURCE_ID}-")
if [ -z "$CONTAINER" ] || [ "$(printf '%s\n' "$CONTAINER" | wc -l)" -ne 1 ]; then
  echo "expected exactly one postgres container for resource ${RESOURCE_ID}, got: '${CONTAINER}'" >&2
  exit 1
fi

if [ "$MODE" = "--check" ]; then
  PASSWORD=$(cat "$PASS_FILE")
elif [ -f "$PASS_FILE" ] && [ "$MODE" != "--rotate" ]; then
  PASSWORD=$(cat "$PASS_FILE")
  echo "password: reusing ${PASS_FILE}"
else
  # 24 байта = 48 hex-знаков; umask 077 — файл читает только root.
  PASSWORD=$(openssl rand -hex 24)
  umask 077
  printf '%s\n' "$PASSWORD" > "$PASS_FILE"
  echo "password: new, written to ${PASS_FILE}"
fi

if [ "$MODE" != "--check" ]; then
  # Первая строка stdin — пароль (read забирает ровно её), дальше — SQL для psql -f -.
  { printf '%s\n' "$PASSWORD"; cat create-ro-role.sql; } | docker exec -i "$CONTAINER" sh -c '
    read -r GRAFANA_RO_PASSWORD; export GRAFANA_RO_PASSWORD
    exec psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
      -v dbname="$POSTGRES_DB" -v owner="$POSTGRES_USER" -f -
  '
fi

# Проверка входа по TCP под ролью (как ходит Grafana) и что граница — именно GRANT'ы:
# read-only по умолчанию роль может снять сама, поэтому пишем в явной транзакции READ WRITE
# и ждём «permission denied». Если DDL вдруг прошёл — убираем следы и падаем.
# Подключаемся к сетевому адресу контейнера, не к 127.0.0.1: loopback у образа postgres в
# pg_hba.conf стоит как trust, и пароль там не проверяется вовсе.
printf '%s\n' "$PASSWORD" | docker exec -i "$CONTAINER" sh -c '
  read -r PGPASSWORD; export PGPASSWORD
  PGHOST=$(hostname -i | cut -d" " -f1); export PGHOST
  psql -X -v ON_ERROR_STOP=1 -U grafana_ro -d "$POSTGRES_DB" -tAc \
    "select '"'"'login ok (scram via '"'"' || inet_server_addr() || '"'"'), product_event rows: '"'"' || count(*) from product_event"
  if psql -X -U grafana_ro -d "$POSTGRES_DB" -qc \
       "begin read write; create table grafana_ro_probe(); commit" 2>/dev/null; then
    psql -X -U grafana_ro -d "$POSTGRES_DB" -qc "begin read write; drop table grafana_ro_probe; commit" || true
    echo "ERROR: grafana_ro was able to CREATE TABLE — grants are too wide" >&2
    exit 1
  fi
  if psql -X -U grafana_ro -d "$POSTGRES_DB" -qc \
       "begin read write; delete from product_event where false; commit" 2>/dev/null; then
    echo "ERROR: grafana_ro was able to DELETE — grants are too wide" >&2
    exit 1
  fi
  echo "write check: CREATE TABLE and DELETE under grafana_ro rejected (permission denied), as expected"
'
echo "done: role grafana_ro ready for resource ${RESOURCE_ID}; CLUBS_DB_PASSWORD = contents of ${PASS_FILE}"

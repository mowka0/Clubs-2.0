#!/usr/bin/env bash
# Ежедневный дамп баз Clubs (прод и staging) из контейнеров Coolify + копия в Hetzner Storage Box.
#
# Ставится на VPS установщиком install.sh, запускается кроном (clubs-db-backup.cron).
# Настройки — /etc/clubs-backup.env (образец: clubs-backup.env.example). Runbook — README.md.
#
# Одна база упала — остальные всё равно дампятся, а скрипт выходит с кодом 1, чтобы сбой
# был виден в логе. Файл last-success обновляется только при полном успехе.

set -uo pipefail

CONFIG_FILE="${CLUBS_BACKUP_CONFIG:-/etc/clubs-backup.env}"
# Блокировка от наложения запусков: ручной прогон во время кронового не должен портить файлы.
LOCK_FILE=/run/clubs-db-backup.lock

log() { echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') $*"; }

[ -r "$CONFIG_FILE" ] || { log "ERROR config $CONFIG_FILE not found"; exit 2; }
# shellcheck source=/dev/null
. "$CONFIG_FILE"

# Значения по умолчанию, если в конфиге их нет.
# Каталог дампов на VPS.
BACKUP_DIR="${BACKUP_DIR:-/var/backups/clubs}"
# Сколько суток хранить дампы на самом VPS.
BACKUP_KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
# Сколько суток хранить копии в Storage Box: дольше, чем на VPS, — страховка на случай,
# если порча данных обнаружится поздно.
STORAGEBOX_KEEP_DAYS="${STORAGEBOX_KEEP_DAYS:-30}"
# Каталог внутри Storage Box.
STORAGEBOX_DIR="${STORAGEBOX_DIR:-clubs-backups}"
# Ключ SSH для Storage Box (порт 23, ограниченный shell без пайпов и find).
STORAGEBOX_SSH_KEY="${STORAGEBOX_SSH_KEY:-/root/.ssh/clubs_storagebox}"
# Адрес Storage Box вида uXXXXX@uXXXXX.your-storagebox.de; пусто — внешняя копия выключена.
STORAGEBOX_TARGET="${STORAGEBOX_TARGET:-}"

: "${BACKUP_TARGETS:?BACKUP_TARGETS is empty: expected 'prod:<coolify-project-id> staging:<id>'}"

exec 9>"$LOCK_FILE"
flock -n 9 || { log "ERROR another backup run is in progress"; exit 2; }

umask 077
mkdir -p "$BACKUP_DIR"
STAMP="$(date -u '+%Y%m%d-%H%M')"
FAILED=0
NEW_FILES=()

# Дамп одной базы. Логин и имя базы берём из окружения самого контейнера: пароль не нужен,
# pg_dump ходит через локальный сокет внутри контейнера.
dump_target() {
    local name="$1" project="$2"
    local container
    container="$(docker ps -q -f "name=postgres-${project}")"
    if [ -z "$container" ] || [ "$(echo "$container" | wc -l)" -ne 1 ]; then
        log "ERROR [$name] expected exactly one postgres container for project $project, got: '${container}'"
        return 1
    fi

    local final="$BACKUP_DIR/${name}-${STAMP}.dump"
    local tmp="${final}.partial"
    # shellcheck disable=SC2016 # переменные раскрываются внутри контейнера, а не здесь
    if ! docker exec "$container" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner' > "$tmp"; then
        log "ERROR [$name] pg_dump failed"
        rm -f -- "$tmp"
        return 1
    fi
    # Проверка, что архив читается: битый или обрезанный дамп хуже отсутствующего — на него надеются.
    local entries
    if ! entries="$(docker exec -i "$container" pg_restore --list < "$tmp" | grep -c ' TABLE DATA ')"; then
        log "ERROR [$name] dump is unreadable by pg_restore"
        rm -f -- "$tmp"
        return 1
    fi
    mv "$tmp" "$final"
    NEW_FILES+=("$final")
    log "OK [$name] $(basename "$final") $(du -h "$final" | cut -f1) tables_with_data=$entries"
}

# Чистка старых дампов на VPS: только наши файлы по шаблону имени, ручные дампы не трогаем.
prune_local() {
    find "$BACKUP_DIR" -maxdepth 1 -type f -name '*-[0-9]*-[0-9]*.dump' -mtime +"$BACKUP_KEEP_DAYS" -print -delete \
        | while read -r f; do log "PRUNE local $(basename "$f")"; done
    find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.dump.partial' -delete
}

storagebox_ssh() {
    ssh -p 23 -i "$STORAGEBOX_SSH_KEY" -o BatchMode=yes -o ConnectTimeout=20 "$STORAGEBOX_TARGET" "$@"
}

# Копия в Storage Box. Без --delete: сломанный или пустой каталог на VPS не должен стирать копии.
upload_offsite() {
    [ -n "$STORAGEBOX_TARGET" ] || { log "SKIP offsite: STORAGEBOX_TARGET is empty"; return 0; }
    [ "${#NEW_FILES[@]}" -gt 0 ] || return 0
    storagebox_ssh mkdir "$STORAGEBOX_DIR" >/dev/null 2>&1 || true
    if ! rsync -t -e "ssh -p 23 -i $STORAGEBOX_SSH_KEY -o BatchMode=yes -o ConnectTimeout=20" \
        "${NEW_FILES[@]}" "$STORAGEBOX_TARGET:$STORAGEBOX_DIR/"; then
        log "ERROR offsite upload failed"
        return 1
    fi
    log "OK offsite uploaded ${#NEW_FILES[@]} file(s)"
    prune_offsite
}

# В Storage Box нет find и пайпов, поэтому возраст считаем по дате в имени файла.
prune_offsite() {
    local cutoff listing file day
    cutoff="$(date -u -d "-${STORAGEBOX_KEEP_DAYS} days" '+%Y%m%d')"
    listing="$(storagebox_ssh ls -1 "$STORAGEBOX_DIR")" || { log "ERROR offsite listing failed"; return 1; }
    while read -r file; do
        [[ "$file" =~ -([0-9]{8})-[0-9]{4}\.dump$ ]] || continue
        day="${BASH_REMATCH[1]}"
        if [ "$day" -lt "$cutoff" ]; then
            storagebox_ssh rm "$STORAGEBOX_DIR/$file" && log "PRUNE offsite $file"
        fi
    done <<< "$listing"
}

for pair in $BACKUP_TARGETS; do
    dump_target "${pair%%:*}" "${pair#*:}" || FAILED=1
done
prune_local
upload_offsite || FAILED=1

if [ "$FAILED" -eq 0 ]; then
    date -u '+%Y-%m-%dT%H:%M:%SZ' > "$BACKUP_DIR/last-success"
    log "DONE"
else
    log "DONE WITH ERRORS"
fi
exit "$FAILED"

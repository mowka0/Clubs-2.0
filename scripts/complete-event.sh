#!/usr/bin/env bash
#
# Clubs 2.0 — прогнать встречу через все этапы до «завершена, явка отмечена, репутация начислена».
#
# Зачем: чтобы вручную тестировать всё, что живёт ПОСЛЕ встречи (разделение счёта, история,
# репутация), не дожидаясь реальной даты и шедулеров. Скрипт ставит данные в то состояние,
# в котором их оставил бы штатный ход событий, а начисление репутации делает сам бэкенд —
# ledger руками не пишем, иначе разъедутся агрегаты, XP и уровни.
#
# Что делает:
#   1. находит встречу (по id, по части названия или просто последнюю созданную);
#   2. сдвигает её в прошлое и проводит участников через оба этапа: «Иду» → «Подтверждаю» → «Пришёл»;
#   3. помечает явку отмеченной так, что окно оспаривания (48 ч) уже позади;
#   4. перезапускает бэкенд — на старте его шедулеры финализируют явку и пишут репутацию
#      (без перезапуска то же самое случится само, но в течение часа: поллеры ходят раз в час);
#   5. показывает, что реально попало в reputation_ledger.
#
# Использование:
#   scripts/complete-event.sh                          # последняя созданная встреча на staging
#   scripts/complete-event.sh --event "Ужин"           # по части названия
#   scripts/complete-event.sh --event <uuid> --yes     # без подтверждения
#   scripts/complete-event.sh --attended 3             # пометить пришедшими только троих
#   scripts/complete-event.sh --no-restart             # не трогать контейнер, подождать поллер
#
# Требуется ssh-доступ к VPS (тот же, что у ~/clubs-db.sh). Прод требует ввести слово prod руками.

set -euo pipefail

VPS=${CLUBS_VPS:-root@77.42.23.177}
PROJECT_STAGING=u91a5392n24ubfq17kl251z4
PROJECT_PROD=qhbcadbuungspby1mxw7p7n9

# Окно оспаривания явки (ATTENDANCE_DISPUTE_WINDOW_MINUTES, по умолчанию 2880 = 48 ч). Отметку явки
# датируем заведомо раньше, чтобы финализация сработала на первом же тике.
MARKED_AGO='3 days'
# Насколько сдвигаем саму встречу в прошлое: она должна быть прошедшей, но свежее 30 дней —
# иначе по ней уже нельзя разделить счёт (SplitBillTemplate.MAX_EVENT_AGE_DAYS).
EVENT_AGO='4 days'

ENVIRONMENT=staging
EVENT_REF=""
ATTENDED_LIMIT=""
ASSUME_YES=false
RESTART=true

usage() {
    sed -n '3,28p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --env)        ENVIRONMENT="${2:?--env требует значение}"; shift 2 ;;
        --event|-e)   EVENT_REF="${2:?--event требует значение}"; shift 2 ;;
        --attended|-n) ATTENDED_LIMIT="${2:?--attended требует число}"; shift 2 ;;
        --yes|-y)     ASSUME_YES=true; shift ;;
        --no-restart) RESTART=false; shift ;;
        --help|-h)    usage 0 ;;
        *) echo "Неизвестный аргумент: $1" >&2; usage 1 ;;
    esac
done

case "$ENVIRONMENT" in
    staging) PROJECT=$PROJECT_STAGING ;;
    prod)    PROJECT=$PROJECT_PROD ;;
    *) echo "--env принимает staging или prod" >&2; exit 1 ;;
esac

if [[ "$ENVIRONMENT" == prod ]]; then
    echo "⚠️  Это ПРОДАКШЕН: скрипт подделает ход встречи и начислит людям настоящую репутацию."
    read -r -p "Введите слово prod, чтобы продолжить: " confirm_prod || true
    [[ "${confirm_prod:-}" == "prod" ]] || { echo "Отменено."; exit 1; }
fi

# --- доступ к контейнерам окружения -------------------------------------------------------------

container() { # $1 = префикс имени (postgres | backend)
    ssh "$VPS" "docker ps -q -f name=$1-$PROJECT" | head -1
}

PG_CID=$(container postgres)
[[ -n "$PG_CID" ]] || { echo "Не нашёл контейнер postgres-$PROJECT на $VPS" >&2; exit 1; }

# psql внутри контейнера: пароль/пользователь/база берутся из его же переменных окружения,
# наружу порт 5432 не открыт (см. ~/clubs-db.sh).
psql_run() { # SQL на stdin; -qAt → голые значения, поля через |
    ssh "$VPS" "docker exec -i $PG_CID sh -c 'PGPASSWORD=\$POSTGRES_PASSWORD psql -v ON_ERROR_STOP=1 -qAt -U \$POSTGRES_USER -d \$POSTGRES_DB'"
}

# Одинарные кавычки в пользовательской строке удваиваем — она уезжает внутрь SQL-литерала.
REF_SQL=${EVENT_REF//\'/\'\'}

# --- 1. Какую встречу двигаем -------------------------------------------------------------------

EVENT_ROW=$(psql_run <<SQL
SELECT e.id, e.title, to_char(e.event_datetime, 'DD.MM.YYYY HH24:MI'), e.status,
       coalesce(e.participant_limit::text, 'нет'), coalesce(e.min_participants::text, 'нет'),
       c.name, e.club_id, c.owner_id
FROM events e
JOIN clubs c ON c.id = e.club_id
WHERE ('$REF_SQL' = '' OR e.id::text = '$REF_SQL' OR e.title ILIKE '%$REF_SQL%')
ORDER BY e.created_at DESC
LIMIT 1;
SQL
)

[[ -n "$EVENT_ROW" ]] || { echo "Встреча не найдена (--event '$EVENT_REF')" >&2; exit 1; }

IFS='|' read -r EVENT_ID TITLE WHEN STATUS PLIMIT MINP CLUB_NAME CLUB_ID OWNER_ID <<< "$EVENT_ROW"

echo "Встреча:  $TITLE"
echo "Клуб:     $CLUB_NAME"
echo "Когда:    $WHEN  (станет $EVENT_AGO назад)"
echo "Статус:   $STATUS → completed"
echo "Места:    $PLIMIT, минимум: $MINP"
echo "ID:       $EVENT_ID"

# Открытая встреча (без лимита мест) намеренно живёт вне репутации — ledger по ней останется пуст.
if [[ "$PLIMIT" == "нет" ]]; then
    echo
    echo "⚠️  Встреча открытая (без мест) — она ВНЕ репутации по дизайну: явка отметится,"
    echo "   но строк в reputation_ledger не будет. Нужна репутация — берите встречу с местами."
fi

# --- 2. Кого проводим через этапы ---------------------------------------------------------------

# Все активные участники клуба, но не больше, чем есть мест (и не больше --attended).
LIMIT_SQL="(SELECT least(coalesce(participant_limit, 100000), ${ATTENDED_LIMIT:-100000}) FROM events WHERE id = '$EVENT_ID')"

ROSTER=$(psql_run <<SQL
SELECT u.first_name || coalesce(' ' || u.last_name, '') ||
       CASE WHEN m.user_id = '$OWNER_ID' THEN '  (владелец клуба — репутацию в своём клубе не копит)' ELSE '' END
FROM memberships m
JOIN users u ON u.id = m.user_id
WHERE m.club_id = '$CLUB_ID' AND m.status = 'active'
ORDER BY m.joined_at NULLS LAST, m.user_id
LIMIT $LIMIT_SQL;
SQL
)

echo
echo "Пометим пришедшими:"
echo "$ROSTER" | sed 's/^/  · /'
ROSTER_COUNT=$(grep -c . <<< "$ROSTER" || true)
echo "Итого: $ROSTER_COUNT чел."
[[ "$ROSTER_COUNT" -ge 2 ]] || echo "⚠️  Меньше двух пришедших — счёт по такой встрече разделить нельзя."

if [[ "$ASSUME_YES" != true ]]; then
    echo
    read -r -p "Двигаем? [y/N] " answer || true
    [[ "${answer:-}" == "y" || "${answer:-}" == "Y" ]] || { echo "Отменено."; exit 1; }
fi

# --- 3. Прогон по этапам ------------------------------------------------------------------------

psql_run <<SQL
BEGIN;

-- Этапы 1 и 2 глазами участника: проголосовал «Иду», подтвердил бронь, пришёл.
INSERT INTO event_responses (
    event_id, user_id, stage_1_vote, stage_1_timestamp, stage_2_vote, stage_2_timestamp,
    final_status, attendance, attendance_finalized
)
SELECT '$EVENT_ID', m.user_id,
       'going',     now() - interval '$EVENT_AGO' - interval '2 days',
       'confirmed', now() - interval '$EVENT_AGO' - interval '6 hours',
       'confirmed', 'attended', false
FROM memberships m
WHERE m.club_id = '$CLUB_ID' AND m.status = 'active'
ORDER BY m.joined_at NULLS LAST, m.user_id
LIMIT $LIMIT_SQL
ON CONFLICT (event_id, user_id) DO UPDATE SET
    stage_1_vote      = 'going',
    stage_1_timestamp = excluded.stage_1_timestamp,
    stage_2_vote      = 'confirmed',
    stage_2_timestamp = excluded.stage_2_timestamp,
    final_status      = 'confirmed',
    attendance        = 'attended',
    updated_at        = now();

-- Сама встреча: прошла, состав собрался, организатор отметил явку. attendance_finalized и
-- reputation_processed НЕ трогаем — их выставит бэкенд, и только так репутация ляжет штатно.
UPDATE events SET
    event_datetime       = now() - interval '$EVENT_AGO',
    status               = 'completed',
    stage_2_triggered    = true,
    attendance_marked    = true,
    attendance_marked_at = now() - interval '$MARKED_AGO',
    attendance_finalized = false,
    reputation_processed = false,
    updated_at           = now()
WHERE id = '$EVENT_ID';

COMMIT;
SQL

echo "Данные готовы: встреча завершена, явка отмечена, окно оспаривания позади."

# --- 4. Даём бэкенду начислить репутацию --------------------------------------------------------

if [[ "$RESTART" == true ]]; then
    BE_CID=$(container backend)
    [[ -n "$BE_CID" ]] || { echo "Не нашёл контейнер backend-$PROJECT — репутация начислится поллером в течение часа." >&2; exit 0; }
    echo "Перезапускаю бэкенд, чтобы его шедулеры отработали сейчас, а не через час…"
    ssh "$VPS" "docker restart $BE_CID" >/dev/null
    # JVM поднимается около полутора минут, шедулеры финализации и репутации идут первым тиком.
    for _ in $(seq 1 24); do
        sleep 10
        state=$(ssh "$VPS" "docker inspect -f '{{.State.Health.Status}}' $BE_CID" 2>/dev/null || echo starting)
        [[ "$state" == healthy ]] && break
    done
    echo "Бэкенд: ${state:-unknown}"
    sleep 10
else
    echo "Перезапуск пропущен: репутация начислится почасовым поллером."
fi

# --- 5. Что получилось --------------------------------------------------------------------------

echo
echo "Репутация по встрече:"
psql_run <<SQL
SELECT coalesce(
    (SELECT string_agg(u.first_name || ': ' || l.kind || ' ' || l.points, E'\n' ORDER BY l.points DESC)
     FROM reputation_ledger l JOIN users u ON u.id = l.user_id
     WHERE l.source_type = 'event' AND l.source_id = '$EVENT_ID'),
    'строк пока нет — если встреча открытая, так и задумано; иначе подождите тик поллера'
);
SQL

echo
echo "Готово. Встреча: $TITLE ($EVENT_ID)"

# Бэкапы баз Clubs

Каждую ночь в 03:15 МСК сервер снимает дамп баз прода и staging, хранит дампы 14 дней у себя
и кладёт копию в Hetzner Storage Box (там — 30 дней). До 2026-10-15 автобэкапов не было вовсе:
только ручные дампы в `/root/backups` перед рискованными операциями.

| Что | Где |
|---|---|
| Скрипт | `/usr/local/sbin/clubs-db-backup` (← `clubs-db-backup.sh`) |
| Расписание | `/etc/cron.d/clubs-db-backup` (← `clubs-db-backup.cron`), 00:15 UTC |
| Настройки | `/etc/clubs-backup.env`, права 600 (образец — `clubs-backup.env.example`) |
| Дампы на VPS | `/var/backups/clubs/<prod\|staging>-YYYYMMDD-HHMM.dump` |
| Метка успеха | `/var/backups/clubs/last-success` — время последнего прогона без ошибок |
| Лог | `/var/log/clubs-backup.log` |
| Копия вне VPS | Storage Box, каталог `clubs-backups/` |
| Ключ к Storage Box | `/root/.ssh/clubs_storagebox` (отдельный, только для бэкапов) |

**Формат.** `pg_dump --format=custom`: сжатый, восстанавливается `pg_restore`, умеет поднимать
отдельные таблицы. Каждый дамп сразу проверяется `pg_restore --list` — нечитаемый файл не
сохраняется и даёт ошибку в логе.

**Почему без пароля.** Скрипт запускает `pg_dump` внутри контейнера базы через локальный сокет,
логин и имя базы берёт из окружения контейнера. Пароли на диск не пишутся.

**Почему id проектов не в репозитории.** Имя контейнера базы — `postgres-<id проекта Coolify>-…`;
пары «имя:id» живут только в `/etc/clubs-backup.env` на сервере.

## 1. Установка (один раз на сервер; повтор безопасен)

```bash
scp infra/backup/clubs-db-backup.sh infra/backup/clubs-db-backup.cron \
    infra/backup/clubs-backup.env.example infra/backup/install.sh root@77.42.23.177:/tmp/
ssh root@77.42.23.177 "cd /tmp && sh install.sh"
```

Первый запуск создаёт `/etc/clubs-backup.env` из образца и останавливается. Впишите в нём
`BACKUP_TARGETS` (id проектов — из `docker ps --format '{{.Names}}' | grep postgres`) и запустите
установщик ещё раз: он сделает пробный прогон. Повторный запуск обновляет скрипт и крон, конфиг
не трогает.

Поставлено 2026-10-15: пробный прогон прошёл, восстановление проверено (§ 4).

## 2. Проверка, что бэкапы идут

```bash
ssh root@77.42.23.177 "cat /var/backups/clubs/last-success; tail -5 /var/log/clubs-backup.log; ls -lt /var/backups/clubs | head"
```

`last-success` должен быть не старше суток. Строка `DONE WITH ERRORS` в логе — разбираться.

## 3. Подключение Storage Box (один раз)

Пока `STORAGEBOX_TARGET` пуст, скрипт пишет в лог `SKIP offsite` и копию вне VPS не делает.

1. **Заказать ящик** — Hetzner Console → Storage Boxes → самый маленький тариф (BX11). Локацию
   лучше выбрать другую, чем у сервера: тогда авария одного дата-центра не заденет обе копии.
2. **Включить SSH** — в настройках ящика: «SSH support» = on. Если с сервера не подключится —
   там же включить «External reachability».
3. **Привязать ключ сервера** (ключ уже создан, нужен пароль ящика из консоли Hetzner — вводится
   один раз):
   ```bash
   ssh root@77.42.23.177
   cat /root/.ssh/clubs_storagebox.pub | ssh -p23 uXXXXX@uXXXXX.your-storagebox.de install-ssh-key
   ```
4. **Вписать адрес** — в `/etc/clubs-backup.env`: `STORAGEBOX_TARGET=uXXXXX@uXXXXX.your-storagebox.de`.
5. **Пробный прогон и проверка**:
   ```bash
   ssh root@77.42.23.177 "/usr/local/sbin/clubs-db-backup && ssh -p23 -i /root/.ssh/clubs_storagebox uXXXXX@uXXXXX.your-storagebox.de ls -l clubs-backups"
   ```
   В логе — `OK offsite uploaded 2 file(s)`, в ящике — свежие файлы.

На Storage Box нет `find` и пайпов, поэтому старые копии там удаляются по дате в имени файла.
Загрузка идёт без `--delete`: если на VPS пропадут дампы, копии в ящике останутся.

## 4. Восстановление

**Проверочное — в пустую базу на staging-сервере** (так приёмку и проходили 2026-10-15; прод не
трогает). Восстанавливает последний прод-дамп во временную базу, сравнивает число строк с живым
продом и удаляет временную базу:

```bash
ssh root@77.42.23.177 'set -e
S=$(docker ps -q -f name=postgres-<staging-id>); P=$(docker ps -q -f name=postgres-<prod-id>)
F=$(ls -1t /var/backups/clubs/prod-*.dump | head -1)
docker exec $S sh -c "createdb -U \$POSTGRES_USER clubs_restore_check"
docker exec -i $S sh -c "pg_restore -U \$POSTGRES_USER -d clubs_restore_check --no-owner --exit-on-error" < "$F"
Q="select (select count(*) from users), (select count(*) from clubs), (select count(*) from memberships)"
docker exec $S sh -c "psql -U \$POSTGRES_USER -d clubs_restore_check -tAc \"$Q\""
docker exec $P sh -c "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -tAc \"$Q\""
docker exec $S sh -c "dropdb -U \$POSTGRES_USER clubs_restore_check"'
```

**Боевое — откат прода на дамп.** Только с согласия PO: всё, что случилось после дампа, пропадёт.

1. Снять свежий дамп текущего (испорченного) состояния — на случай, если откат ошибочен:
   `ssh root@77.42.23.177 /usr/local/sbin/clubs-db-backup`.
2. Остановить бэкенд прода в Coolify (Stop у сервиса `backend`), чтобы шедулеры и запросы не
   писали в базу во время восстановления.
3. Восстановить поверх, пересоздавая объекты:
   ```bash
   ssh root@77.42.23.177 'P=$(docker ps -q -f name=postgres-<prod-id>)
   docker exec -i $P sh -c "pg_restore -U \$POSTGRES_USER -d \$POSTGRES_DB --clean --if-exists --no-owner --exit-on-error" < /var/backups/clubs/prod-YYYYMMDD-HHMM.dump'
   ```
4. Запустить бэкенд. Flyway увидит ту же историю миграций, что в дампе, и догонит недостающие.
5. Смоук: открыть Mini App, «Мои клубы», карточка клуба.

**Из Storage Box** (если VPS потерян) — скачать файл и восстанавливать так же:
`scp -P 23 uXXXXX@uXXXXX.your-storagebox.de:clubs-backups/prod-YYYYMMDD-HHMM.dump .`

## 5. Снять

```bash
ssh root@77.42.23.177 "rm /etc/cron.d/clubs-db-backup"
```

Дампы в `/var/backups/clubs` и копии в Storage Box при этом остаются.

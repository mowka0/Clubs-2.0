#!/bin/sh
# Ставит ежедневный бэкап баз Clubs на VPS.
#
# Запускать НА СЕРВЕРЕ от root, из каталога с этими файлами:
#   scp infra/backup/clubs-db-backup.sh infra/backup/clubs-db-backup.cron \
#       infra/backup/clubs-backup.env.example infra/backup/install.sh root@77.42.23.177:/tmp/
#   ssh root@77.42.23.177 "cd /tmp && sh install.sh"
#
# Идемпотентен: повторный запуск обновляет скрипт и крон, а /etc/clubs-backup.env не трогает.

set -eu

SCRIPT_DST=/usr/local/sbin/clubs-db-backup
CRON_DST=/etc/cron.d/clubs-db-backup
CONFIG_DST=/etc/clubs-backup.env

if [ "$(id -u)" -ne 0 ]; then
    echo "Нужны права root: крон в /etc/cron.d и доступ к docker" >&2
    exit 1
fi

for f in ./clubs-db-backup.sh ./clubs-db-backup.cron ./clubs-backup.env.example; do
    [ -f "$f" ] || { echo "Не найден $f — запускайте из каталога с файлами" >&2; exit 1; }
done

install -m 755 ./clubs-db-backup.sh "$SCRIPT_DST"
# cron.d молча пропускает файлы с точкой в имени, поэтому имя без расширения и права 644.
install -m 644 ./clubs-db-backup.cron "$CRON_DST"

if [ ! -f "$CONFIG_DST" ]; then
    install -m 600 ./clubs-backup.env.example "$CONFIG_DST"
    echo "Создан $CONFIG_DST из образца — впишите BACKUP_TARGETS и запустите установщик ещё раз."
    exit 0
fi

echo "Пробный прогон:"
"$SCRIPT_DST"
echo
echo "Готово. Крон: $CRON_DST, лог: /var/log/clubs-backup.log"

#!/bin/sh
# Ставит MSS clamping как systemd-юнит: правила возвращаются сами после каждой перезагрузки.
#
# Запускать НА СЕРВЕРЕ от root, из каталога с этими файлами:
#   scp infra/host/clubs-mss-clamp.sh infra/host/clubs-mss-clamp.service \
#       infra/host/install-mss-clamp.sh root@77.42.23.177:/tmp/
#   ssh root@77.42.23.177 "cd /tmp && sh install-mss-clamp.sh"
#
# Идемпотентен: повторный запуск просто обновляет скрипт и перезапускает юнит.

set -eu

SCRIPT_SRC="${SCRIPT_SRC:-./clubs-mss-clamp.sh}"
UNIT_SRC="${UNIT_SRC:-./clubs-mss-clamp.service}"
SCRIPT_DST=/usr/local/sbin/clubs-mss-clamp
UNIT_DST=/etc/systemd/system/clubs-mss-clamp.service

if [ "$(id -u)" -ne 0 ]; then
    echo "Нужны права root: правила iptables и юниты systemd ставит только он" >&2
    exit 1
fi

for f in "$SCRIPT_SRC" "$UNIT_SRC"; do
    [ -f "$f" ] || { echo "Не найден $f — запускайте из каталога с файлами" >&2; exit 1; }
done

install -m 755 "$SCRIPT_SRC" "$SCRIPT_DST"
install -m 644 "$UNIT_SRC" "$UNIT_DST"

systemctl daemon-reload
systemctl enable --now clubs-mss-clamp.service

echo
echo "Готово. Проверка:"
if ! "$SCRIPT_DST" status; then
    echo "Правила не встали — смотрите: systemctl status clubs-mss-clamp" >&2
    exit 1
fi

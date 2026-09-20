#!/bin/bash
# Ставит nginx как L4-прокси (TCP passthrough + PROXY protocol) перед Hetzner и закрывает VPS:
# SSH только по ключу, файрвол 22/80/443, автообновления. После переключения DNS этот VPS держит
# порт 80 домена, то есть способен пройти HTTP-01 и выпустить сертификат прода — доверие к нему
# равно доверию к Hetzner, отсюда hardening здесь же, а не «потом».
#
# Запускать НА РОССИЙСКОМ VPS от root, из каталога с этими файлами:
#   scp -r infra/ru-proxy infra/host root@<RU_IP>:/tmp/
#   ssh root@<RU_IP> "cd /tmp/ru-proxy && bash install.sh"
#
# Идемпотентен: повторный запуск обновляет конфиг и перезагружает nginx.
# Порядок шагов целиком (сначала Traefik на Hetzner, DNS последним) — README.md.

set -euo pipefail

CONF_SRC="${CONF_SRC:-./nginx.conf}"
CONF_DST=/etc/nginx/nginx.conf
# Прод на Hetzner. Достижимость проверяется через --resolve: не зависит ни от DNS clubsapp.ru
# (после переключения он ведёт на этот же VPS), ни от sslip-имени, которое могут убрать из Coolify.
UPSTREAM_IP=77.42.23.177
# 00-, а не 50-: cloud-init провайдера кладёт 50-cloud-init.conf с PasswordAuthentication yes,
# а sshd берёт первое значение в алфавитном порядке include — наш файл обязан идти раньше.
SSHD_DROPIN=/etc/ssh/sshd_config.d/00-clubs.conf

if [ "$(id -u)" -ne 0 ]; then
    echo "Нужны права root: пакеты, /etc/nginx, sshd и файрвол ставит только он" >&2
    exit 1
fi
if [ ! -f "$CONF_SRC" ]; then
    echo "Не найден $CONF_SRC — запускайте из каталога с этими файлами" >&2
    exit 1
fi
# Ниже выключается парольный вход; без ключа это отрезало бы доступ к машине.
if [ ! -s /root/.ssh/authorized_keys ]; then
    echo "В /root/.ssh/authorized_keys нет SSH-ключа: добавьте его (панель провайдера или ssh-copy-id) и запустите снова" >&2
    exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -q
# libnginx-mod-stream явно: без него блок stream в конфиге — ошибка «unknown directive».
apt-get install -y -q nginx libnginx-mod-stream curl ufw unattended-upgrades openssh-server

# Главная проверка: из этого дата-центра Hetzner должен отвечать, иначе прокси бесполезен —
# РКН режет подсети Hetzner не везде одинаково, и провайдера тогда нужно менять.
# 10 с: ответ ДЦ→ДЦ занимает доли секунды, дольше — не «медленно», а «режут».
code=$(curl -sS -o /dev/null --max-time 10 -w '%{http_code}' \
    --resolve "clubsapp.ru:443:${UPSTREAM_IP}" https://clubsapp.ru/ || true)
if [ "$code" != "200" ]; then
    echo "Hetzner (${UPSTREAM_IP}) отсюда не отвечает (код '$code'):" >&2
    echo "подсеть режут и в этом дата-центре, нужен VPS у другого провайдера" >&2
    exit 1
fi

# Конфиг проверяется ДО копирования: битый файл на диске пережил бы работающий nginx и уронил
# бы прокси при первом рестарте, например от unattended-upgrades.
nginx -t -c "$(readlink -f "$CONF_SRC")"
# Дефолтный nginx.conf дистрибутива сохраняем один раз — для отката руками.
[ -f "$CONF_DST.dist" ] || cp "$CONF_DST" "$CONF_DST.dist"
install -m 644 "$CONF_SRC" "$CONF_DST"
systemctl enable --now nginx
systemctl reload nginx

# SSH: только ключ, root без пароля. Drop-in, а не правка sshd_config — переживает обновления пакета.
cat > "$SSHD_DROPIN" <<'SSHD'
PasswordAuthentication no
PermitRootLogin prohibit-password
SSHD
sshd -t
systemctl reload ssh
# Итог проверяем по эффективному конфигу, а не по факту записи файла: другой drop-in может перебить.
# Без конвейера: grep -q закрыл бы канал на первом совпадении, sshd получил бы SIGPIPE, и pipefail
# выдал бы ложную ошибку.
sshd_effective=$(sshd -T 2>/dev/null || true)
if ! grep -qx 'passwordauthentication no' <<<"$sshd_effective"; then
    echo "Парольный вход всё ещё включён: в /etc/ssh/sshd_config.d/ есть drop-in раньше $SSHD_DROPIN" >&2
    exit 1
fi

# Файрвол: наружу только SSH (limit — не больше 6 подключений за 30 с с одного адреса, против
# перебора), HTTP и HTTPS. Порт SSH берём из живого конфига: у части провайдеров он нестандартный.
ssh_port=$({ sshd -T 2>/dev/null || true; } | awk '$1 == "port" { print $2; exit }')
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw limit "${ssh_port:-22}/tcp" >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw --force enable >/dev/null

# MSS clamping — та же «чёрная дыра» PMTU, что лечится на Hetzner (infra/host): прокси сам
# завершает TCP с клиентами, поэтому без клампинга большие ответы (бандл) виснут у части людей.
if [ -f ../host/install-mss-clamp.sh ]; then
    (cd ../host && sh install-mss-clamp.sh)
else
    echo "ВНИМАНИЕ: каталог infra/host не скопирован рядом — MSS clamping не поставлен (см. README, шаг 2)" >&2
fi

echo
echo "Готово. Проверка с машины без VPN (DNS ещё не переключён, адрес подставляется вручную):"
echo "  curl -sSI --resolve clubsapp.ru:443:<IP этого VPS> https://clubsapp.ru/ | head -1"
echo "Ожидается HTTP/2 200. Дальше — README.md, шаг «Проверка до переключения DNS»."

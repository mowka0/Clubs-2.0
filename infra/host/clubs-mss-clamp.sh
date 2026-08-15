#!/bin/sh
# Зажимает TCP MSS исходящих соединений до 1200 байт.
#
# Зачем: на пути от прода к части пользователей MTU меньше 1500, а ICMP «нужна фрагментация»
# до сервера не доходит — сервер шлёт полноразмерные пакеты в пустоту и ретрансмиттит до
# таймаута клиента. Подпись беды: index.html (сотни байт) открывается мгновенно, а бандл и
# картинки висят до таймаута — пользователь видит пустой белый экран, и в логах бэкенда его
# нет вовсе. Инцидент 2026-08-02/03, разбор — docs/modules/infrastructure.md.
#
# Почему 1200, а не --clamp-mss-to-pmtu: клампинг по PMTU опирается на тот самый ICMP, который
# до нас не доходит. 1200 подобрано эмпирически (столько же использует QUIC у Cloudflare,
# который эти дыры обходит) и подтверждено диагностикой на живом клиенте.
#
# Идемпотентен: apply не плодит дубли правил, remove снимает ровно свои.

set -eu

IPTABLES="${IPTABLES:-iptables}"
MSS="${MSS:-1200}"

# Обе цепочки нужны: FORWARD — трафик, проходящий через хост в контейнеры Docker,
# POSTROUTING — то, что уходит с самого хоста.
CHAINS="FORWARD POSTROUTING"

apply() {
    for chain in $CHAINS; do
        if $IPTABLES -t mangle -C "$chain" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss "$MSS" 2>/dev/null; then
            echo "MSS clamp: правило в $chain уже есть, пропускаю"
        else
            $IPTABLES -t mangle -A "$chain" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss "$MSS"
            echo "MSS clamp: правило добавлено в $chain (--set-mss $MSS)"
        fi
    done
}

remove() {
    for chain in $CHAINS; do
        while $IPTABLES -t mangle -C "$chain" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss "$MSS" 2>/dev/null; do
            $IPTABLES -t mangle -D "$chain" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss "$MSS"
            echo "MSS clamp: правило удалено из $chain"
        done
    done
}

status() {
    missing=0
    for chain in $CHAINS; do
        if $IPTABLES -t mangle -C "$chain" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss "$MSS" 2>/dev/null; then
            echo "OK   $chain: MSS $MSS зажат"
        else
            echo "НЕТ  $chain: правила нет — белые экраны вернутся"
            missing=1
        fi
    done
    return $missing
}

case "${1:-}" in
    apply) apply ;;
    remove) remove ;;
    status) status ;;
    *)
        echo "Использование: $0 {apply|remove|status}" >&2
        exit 2
        ;;
esac

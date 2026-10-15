# Grafana для продуктовой аналитики Clubs (W5). Образ = официальный образ Grafana OSS + наши
# provisioning-файлы (датасорс, дашборды, алерты), запечённые внутрь.
#
# Почему не bind-mount каталога из репозитория: Coolify (4.1.2) переписывает относительные
# пути томов в /data/coolify/applications/<uuid>/…, где файлов репозитория нет, — Grafana
# стартовала бы без датасорса и дашбордов. Образ собирается на сервере за секунды.
#
# Репозиторий — grafana/grafana (это и есть OSS-образ на Alpine: curl для healthcheck и uid 472
# на месте). НЕ grafana/grafana-oss: тот остановился на 13.0.2 (2026-06-02) и патчей с CVE
# (например CVE-2026-76154, stored XSS → Org Admin) не получает. Не -slim/-distroless — там нет curl.
# Тег + digest манифеста (multi-arch, тот же на amd64 и arm64): обновлять вручную по
# https://grafana.com/security/security-advisories/ и тегам на Docker Hub, затем деплой.
FROM grafana/grafana:13.0.11@sha256:2abd2114bb65e4d6f0614206b7c882c1e7c6a992827ba08396aa5afc23ca07f5

# Провиженинг читается при старте: datasources/, dashboards/ (провайдер + JSON), alerting/.
# Владелец — uid 472 (пользователь grafana из базового образа), как у остального /etc/grafana.
COPY --chown=472:0 provisioning/ /etc/grafana/provisioning/

# Контейнер остаётся под пользователем grafana (USER 472 из базового образа), порт 3000.

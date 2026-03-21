## Выполнено: TASK-002

### Изменённые файлы
- `docker-compose.yml` -- создан (PostgreSQL 16 + Redis 7)
- `.env.example` -- создан (шаблон переменных окружения)
- `.gitignore` -- создан (исключает .env и *.env.local)
- `tasks.json` -- обновлён статус TASK-002 на "done"

### Acceptance Criteria
- [x] `docker-compose.yml` -- PostgreSQL 16 + Redis 7 со всеми healthcheck
- [x] `.env.example` -- шаблон переменных окружения (без реальных секретов)
- [x] `docker-compose.yml` использует только `${ENV_VAR}` -- никаких захардкоженных секретов
- [x] PostgreSQL healthcheck: `pg_isready`
- [x] Redis healthcheck: `redis-cli ping`
- [x] Volumes для persistence данных (postgres_data, redis_data)
- [x] `docker compose up -d` стартует PostgreSQL и Redis успешно
- [x] `docker compose ps` показывает healthy для всех сервисов

### Test Steps
1. `docker compose up -d` -- оба контейнера запустились
2. `docker compose ps` -- clubs2_postgres (healthy), clubs2_redis (healthy)
3. `docker compose exec postgres psql -U clubs -d clubs -c "SELECT 1;"` -- вернул 1
4. `docker compose exec redis redis-cli ping` -- вернул PONG

### Решения и заметки
- Контейнерам присвоены имена `clubs2_postgres` и `clubs2_redis` (с префиксом `clubs2_`) чтобы не конфликтовать с контейнерами из старого проекта "Clubs reviewed" (`clubs_postgres`, `clubs_redis`)
- Docker Compose project name автоматически = `clubs20` (из имени директории "Clubs 2.0")
- Все переменные окружения имеют дефолтные значения через синтаксис `${VAR:-default}`, что позволяет запускать без .env файла для быстрого старта
- Использованы alpine-образы для минимального размера
- Порты 5432 и 6379 открыты на хост для удобства разработки

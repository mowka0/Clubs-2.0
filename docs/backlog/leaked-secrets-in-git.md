# SECURITY: committed secrets in public repo

**Статус:** ✅ RESOLVED (2026-05-16, ветка `devops/leaked-secrets-cleanup`) · **Создано:** 2026-05-15 · **Origin:** обнаружено при подготовке `devops/telegram-mcp-integration`
**Severity:** High — Critical

## Что было

Репозиторий `github.com/mowka0/Clubs-2.0` — публичный. В нём были committed реальные значения секретов (значения отредактированы — см. git history для аудита):

| Файл | Переменная | Severity |
|---|---|---|
| `.mcp.json` | `BRAVE_API_KEY` | High — Brave Search API ключ |
| `.env.example` | `POSTGRES_PASSWORD` | Critical если совпадал с prod |
| `.env.example` | `MINIO_ROOT_PASSWORD` | Critical если совпадал с prod |
| `.env.example` | `JWT_SECRET` | Critical — позволял выписать JWT на любого юзера, bypass всей авторизации |

Утечка живёт в git history минимум с коммита `108a8fc` (`.env.example`, `.mcp.json` появились впервые), значение `JWT_SECRET` затрагивалось также в `2a14c86`.

## Резолюция (2026-05-16)

- Пользователь ротировал `POSTGRES_PASSWORD`, `MINIO_ROOT_PASSWORD`, `JWT_SECRET` в Coolify на стейдже и проде (разные значения per environment).
  - Postgres: `ALTER USER clubs PASSWORD '...'` внутри контейнера (env var один раз только при init, дальше pg_authid — источник правды).
  - MinIO: смена `MINIO_ROOT_PASSWORD` + рестарт контейнера, MinIO ротирует root creds сам.
- `BRAVE_API_KEY` отозван без выписывания нового — Brave MCP в текущем workflow не используется (`.mcp.json` позже удалён целиком — 2026-06-21, чистка мёртвого конфига).
- `.env.example`: реальные значения заменены на placeholder'ы (`<set-strong-password>`, `<generate: openssl rand -hex 32>`).
- `.mcp.json`: `BRAVE_API_KEY` → `${BRAVE_API_KEY}` env-indirection.
- Pre-commit hook `.githooks/pre-commit` + CI workflow `.github/workflows/secret-scan.yml` (gitleaks-action, pinned к SHA) ловят будущие commit'ы со секретами.
- Правило про placeholders-only и env-indirection задокументировано в `.claude/rules/security.md` § Secrets.
- Git history **не чистили** — старые значения dead (ротированы), новые в проде. Чистка истории через `git filter-repo` ломает PR-ссылки и blame; trade-off не стоит того.

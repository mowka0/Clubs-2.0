# Флак vitest: разовый SyntaxError «does not provide an export named 'getConfig'»

**Наблюдается:** 2026-07-22, участился (3+ раза за день). При полном прогоне `npm test`
случайный test-suite падает на этапе СБОРКИ модулей с
`SyntaxError: The requested module '@testing-library/dom' does not provide an export named 'getConfig'`.
Каждый раз другой файл (CreateClubModal, OnboardingGate, MyClubsRenewal, EventPageAttendance);
соло-прогон того же файла и повторный полный прогон — зелёные.

Похоже на гонку кэша трансформации vite/vitest (v1.6.1) вокруг CJS/ESM-интеропа
@testing-library. Если начнёт мешать CI (когда появится фронт-тест workflow) — лечить:
обновить vitest/testing-library, либо `test.pool: 'forks'`, либо чистка `node_modules/.vite`.

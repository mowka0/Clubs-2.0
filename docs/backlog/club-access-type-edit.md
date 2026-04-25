# Editable access type в SettingsTab клуба

Запрошено пользователем 2026-04-25 при тесте `feature/pre-design-haptic` на staging.

## Что хотим

В `OrganizerClubManage.tsx` SettingsTab → секция «Нельзя изменить» сейчас содержит:
- Категория (read-only)
- Тип доступа (read-only) — текст «Смена категории или типа доступа не поддерживается.»

Дать организатору переключать **тип доступа**: `open ↔ closed`. Категорию по-прежнему оставляем read-only (это меняет позиционирование клуба и затрагивает Discovery-фильтры — отдельный разговор).

## Текущее состояние

- `frontend/src/api/clubs.ts:53-63` — `UpdateClubBody` **не содержит** `accessType`. Бэк не принимает его на `PUT /api/clubs/{id}`.
- `OrganizerClubManage.tsx:886-892` — `applicationQuestion` Input показывается **только** при `accessType === 'closed'`. Эта зависимость ломается, если тип станет редактируемым: пользователь меняет open → closed, но поле для вопроса в форме недоступно (либо нужно тут же показывать).
- `pages/ClubPage.tsx` — `handleJoin` для open-клуба (`POST /memberships`) и `handleApply` для closed (`POST /applications`) — два разных endpoint'а. Смена типа меняет поведение страницы для будущих посетителей.

## Что не «просто поменять значение»

1. **Pending applications при `closed → open`:** что делать с заявками в статусе `pending`?
   - Авто-approve всех? (массовая операция, может быть нежелательна)
   - Отклонить с пояснением «клуб стал открытым, вступайте напрямую»?
   - Оставить и заблокировать новые? Запутает юзеров.
   - Решение нужно — это бизнес-логика, не frontend-полиш.

2. **`open → closed` без `applicationQuestion`:** PRD требует наличия вопроса для closed клубов? Если да — форма должна заставить его заполнить, иначе backend отвергнет (или сохранит null и применит fallback). Проверить spec / Bean Validation.

3. **Авторизация:** только owner (или admin?) может менять access type. Сейчас `updateClub` уже требует ownership — переиспользуем то же. Подтвердить тестом.

4. **UX подтверждение:** смена типа = резкое изменение поведения. Логично native `showConfirm` («Сделать клуб закрытым? Новые участники смогут вступать только по заявке») перед сохранением, как у `handleDelete`. Не блокирующая, но ожидаемая.

5. **Аудит:** access type — security-relevant. Желательно лог `INFO` уровня в backend «Club X access type changed by user Y from open to closed» для трассировки.

6. **Уведомление участников?** Когда клуб стал закрытым (или наоборот) — стоит ли отправлять системное сообщение через бот существующим участникам? Не критично, но обсуждаемо.

## Технический объём

- **Backend (~1-2 часа):** добавить `accessType` в `UpdateClubRequest` + Service-метод. Решить #1 (pending apps) — отдельная transactional операция.
- **Frontend (~1-2 часа):** `Select` (telegram-ui) с двумя опциями в SettingsTab, добавить в `dirty`/`payload`, показать `applicationQuestion` Input реактивно по выбранному типу (не по `club.accessType`), `showConfirm` перед сохранением.
- **Тесты:** unit на Service (open→closed, closed→open, с pending apps), integration на endpoint, e2e сценарий.

## Когда фиксить

Не блокирует pre-design / design-итерацию. Стоит включить **до** публичного релиза, потому что иначе организатор, сделав ошибку при создании клуба, сможет её исправить только удалив клуб и создав заново (теряя участников). Кандидат в `feature/club-settings-access-type` после design-фазы или раньше, если организаторы попросят.

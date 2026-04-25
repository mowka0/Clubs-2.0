# CreateClubModal — миграция на react-hook-form

## Цель

`CreateClubModal` (5-шаговый wizard в `OrganizerPage`) держит 10 полей формы в одном `useState<FormData>`-объекте и валидирует через ручной `validateStep(step, form)` из `utils/validators.ts`. Это анти-паттерн (см. `frontend.md` § «Формы»):

- ререндер всего модала на каждое нажатие клавиши
- валидация императивная, дублирует то что RHF делает декларативно
- ошибки лежат в локальном `error` state, нужно вручную сбрасывать

Цель миграции — перевести форму на `react-hook-form` (установлен в PR #22), убрать ручной валидатор, оставить остальное поведение неизменным.

## Scope

### Входит
- `frontend/src/pages/OrganizerPage.tsx` — `CreateClubModal` (строки ~54-207): `useState<FormData>` → `useForm`, `validateStep` → RHF rules + `trigger()` для шаговой валидации, локальный `error` для validation → `formState.errors`.
- `frontend/src/utils/validators.ts` — удалить (больше нигде не используется, см. pre-flight ниже).
- `frontend/src/test/validators/validateStep.test.ts` — удалить вместе с `validators.ts` (тестирует удаляемый код).
- `frontend/src/test/pages/CreateClubModal.test.tsx` — адаптировать assertion'ы про error states под RHF (`errors.fieldName.message` вместо локального `error`-state). Сценарии не меняются.

### НЕ входит
- `SettingsTab` в `OrganizerClubManage` (тоже форма на `useState`) — отдельный PR.
- Прочие `useState`-формы по проекту — отдельные PR.
- Изменения UX/структуры wizard'а — мигрируем как есть.
- Подключение `zod` resolver — опционально на усмотрение Developer'а; правила здесь простые (string min/max + integer min/max), без zod проходит.

## Решение `register` vs `Controller`

Telegram-UI компоненты (`Input`, `Textarea`, `Select`) — controlled (`value` + `onChange`). Forward ref может работать чисто, может нет.

**Порядок действий Developer'а:**
1. Попробовать `{...register('field', rules)}` — простейший путь, минимум кода.
2. Если type-check падает или `onChange`-event не доходит до RHF — fallback на `<Controller name="field" control={control} rules={...} render={({ field, fieldState }) => <Input {...field} status={fieldState.error ? 'error' : undefined} />} />`.
3. Зафиксировать выбранный путь в alignment-отчёте Analyst'а (одна строка: «used register» / «used Controller because ...»).

`avatarUrl` остаётся в `useState` — это асинхронная upload-операция через `AvatarUpload`-компонент, не вписывается в RHF-цикл.

## Шаговая валидация

Pattern: перед `setStep(s + 1)` вызвать `await trigger([...fields_of_current_step])`.
- Если `true` — сделать haptic `impact('light')` и перейти на следующий шаг.
- Если `false` — RHF сам отрисует `errors.fieldName.message` под полями, ничего дополнительно делать не нужно. Локальный `error`-state для validation удаляется.

Локальный `error`-state **остаётся** для surface mutation `onError` (см. AC ниже) — это серверная ошибка, не form validation.

## Маппинг полей → step + rules

| Step | Поля | Rules |
|---|---|---|
| 0 | `name`, `city`, `district` | `name`: `required`, `minLength: 3`, `maxLength: 60` (с trim). `city`: `required`. `district`: optional. |
| 1 | `category`, `accessType` | `category`: `required`, `defaultValue: 'other'` (нейтральный дефолт — пользователь явно выбирает категорию на шаге 1, не получая случайный «Спорт»). `accessType`: `required`, `defaultValue: 'open'`. |
| 2 | `memberLimit`, `subscriptionPrice` | `memberLimit`: `required`, `min: 10`, `max: 80`, integer (validate) — синхронизировано с backend Bean Validation `@Min(10) @Max(80)` в `CreateClubRequest.kt`. `subscriptionPrice`: `required`, `min: 0`, integer (validate). |
| 3 | `description`, `rules` | `description`: `required`, `minLength: 10`, `maxLength: 500` (с trim). `rules`: optional. |
| 4 | `applicationQuestion` | optional; поле и шаг рендерятся только при `accessType === 'closed'`. |

Сообщения об ошибках (русский, UX не меняется): «Название: минимум 3 символа», «Название: максимум 60 символов», «Укажите город», «Лимит участников: 10–80», «Укажите корректную цену», «Цена должна быть целым числом», «Описание: минимум 10 символов», «Описание: максимум 500 символов».

## Submit

`handleSubmit(onSubmit)` где `onSubmit(data)` собирает body (включая `avatarUrl` из useState) и вызывает `useCreateClubMutation` (без изменений). Поведение `onSuccess` / `onError` / haptic — без изменений.

## Pre-flight

`grep -rn 'validateStep\|ClubFormData\|utils/validators' frontend/src` показал:
- `frontend/src/pages/OrganizerPage.tsx` — единственный потребитель.
- `frontend/src/utils/validators.ts` — сам файл.
- `frontend/src/test/validators/validateStep.test.ts` — unit-тест на удаляемую логику.

→ `validators.ts` + его тест **удаляются**.

## Acceptance Criteria

### AC-1: build & test green
GIVEN миграция завершена
WHEN `npm run build && npm test`
THEN exit 0; нет TypeScript-ошибок; CreateClubModal-тесты проходят.

### AC-2: невалидный шаг не пускает дальше
GIVEN wizard на любом шаге с required-полями (0, 2, 3)
WHEN пользователь жмёт «Далее» с невалидными данными
THEN переход не происходит; под полем виден текст ошибки (тот же что сейчас); haptic light не срабатывает.

### AC-3: payload идентичен
GIVEN валидный wizard заполнен до конца
WHEN submit
THEN `body` запроса содержит ровно те же 10 полей (name, city, district, category, accessType, memberLimit, subscriptionPrice, description, rules, applicationQuestion) + avatarUrl что и до миграции. Имена и типы не меняются.

### AC-4: haptic preserved + validation-fail
GIVEN миграция завершена
THEN haptic срабатывает в тех же точках: `impact('light')` на каждом успешном next/back, `impact('heavy')` на финальный submit, `notify('success')` на onSuccess мутации, `notify('error')` на onError.
AND **дополнительно** `notify('error')` срабатывает на любом RHF validation fail:
- В `handleNext` если `await trigger(STEP_FIELDS[step])` вернул `false` (невалидный шаг)
- В `handleSubmit(onValid, onInvalid)` через `onInvalid` callback — RHF сам зовёт его при провале валидации перед mutation
Покрытие и Правила — см. `haptic.md` § Side-effect требования (Правило 1.2).

### AC-5: server error UI preserved
GIVEN submit вернул ошибку (mutation `onError`)
THEN ошибка отображается в том же UI-месте что сейчас (через локальный `error` state, **не** через RHF errors).

### AC-6: validators.ts удалён
GIVEN миграция завершена
THEN `frontend/src/utils/validators.ts` и `frontend/src/test/validators/validateStep.test.ts` удалены; `grep -rn 'validateStep\|ClubFormData\|utils/validators' frontend/src` возвращает 0 результатов.

## Риски

### R-1: double-click на кнопке «Далее» во время `await trigger()`

**Реальность:** `handleNext` асинхронный (`await trigger(fields)`). Если пользователь дважды быстро нажмёт «Далее», вторая итерация прорезолвится тоже валидной (поля не изменились) и `setStep(s => s + 1)` сработает дважды → перескок через шаг.

**Митигация — отложена.** Поднималось Reviewer'ом ([Suggestion]). Первая попытка фикса (`disabled={isValidating}` через `formState.isValidating`) сломала 4 теста: при `mode: 'onTouched'` RHF держит `isValidating=true` мерцающе во время keystroke-валидации, кнопка флапает между enabled/disabled и тесты ловят race. Откатили.

**Если воспроизведётся в production** — фикс через локальный `useState`/`useRef` guard вокруг самого `handleNext` (`if (advancingRef.current) return; advancingRef.current = true; ...; advancingRef.current = false` после `setStep`). **Не использовать `formState.isValidating`** — несовместимо с `onTouched` mode.

**Импакт сейчас:** низкий. На реальном телефоне touch-target обработка обычно не успевает зарегистрировать два tap'а за время одного `await trigger()` (синхронные правила резолвятся за миллисекунды). На staging пока не воспроизведено.

## Связанные

- PR #22 — `react-hook-form` установлен (`--legacy-peer-deps` из-за telegram-ui + React 19).
- `.claude/rules/frontend.md` § «Формы» — RHF стандарт, `useState` для форм запрещён.
- `docs/modules/haptic.md` — wizard transition mappings.
- Out of scope follow-up: миграция `SettingsTab` в `OrganizerClubManage` на RHF — отдельный PR.

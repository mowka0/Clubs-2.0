# Bug: RSVP/confirm/decline кнопки на странице события не работают

Обнаружено пользователем 2026-04-25 при тесте `feature/unified-club-page` на staging (сценарий #8 — тап на событие из ClubEventsTab открывает /events/:id, на котором кнопки участия не реагируют).

> **✅ RESOLVED** в `bugfix/event-rsvp-broken` 2026-04-27. Root cause состоял из 2 проблем:
> 1. **UI catastrophe (главное):** `EventPage.tsx` объявлял `const error = actionError ?? eventQuery.error?.message`, и при ЛЮБОМ срабатывании `actionError` (mutation onError) ВСЯ страница заменялась на `<Placeholder header="Ошибка">`. Из-за этого пользователь нажимал «Иду» → backend rejection → весь экран флипался на error-плейсхолдер → ощущение «ничего не меняет / приложение сломалось». Фикс: разделили `loadError` (от eventQuery — основание для page-level placeholder) и `actionError` (от мутаций — рендерится inline в нужной секции, страница не перерисовывается)
> 2. **Status mismatch:** Frontend `showVoting = status === 'upcoming' || status === 'stage_1'`, backend `VoteService.castVote:40` принимает голос ТОЛЬКО при `status === 'upcoming'`. Для events в `stage_1` кнопки показывались, но backend возвращал 4xx → катастрофа из п.1. Фикс: убрал `stage_1` из showVoting (frontend строго совпадает с backend rules)
>
> Smoke-test: голосование на upcoming-event теперь проходит, mutation update'ит статистику и myVote badge. Если backend rejected (например voting period не открыт >5 дней до события) — inline error в воuting секции, страница продолжает работать.

## Что не так

`pages/EventPage.tsx` отрисовывает кнопки RSVP («Иду» / «Может быть» / «Не иду») и confirm/decline участия. На staging нажатие на эти кнопки **не меняет состояние UI** — нет visual feedback что vote/confirm зарегистрировался.

## Возможные причины (не диагностировано)

1. **Mutations не fire'ятся** — handler не привязан, или disabled state блокирует click. Маловероятно (PR #24 покрыл все мутации, тесты прошли)
2. **Mutation fire'ится но UI не обновляется** — `onSuccess` не invalidate'ит правильный query key, кеш стейл. Самая вероятная причина — `useCastVoteMutation` / `useConfirmParticipationMutation` invalidate'ит `events.detail(id)` + `events.myVote(id)`, но если событие приходит из `events.byClub(...)` query (как часть списка в ClubEventsTab), и user рендерит EventPage напрямую — возможно prefetch/cache проблемы
3. **Backend ошибка** — endpoint возвращает 4xx, error swallow'ится в `onError` без UI surface. Проверить Network tab + console
4. **Event-state логика на фронте** — `myVote` не отображается обновлённой потому что компонент использует local state вместо derived из query

## Диагностика (нужна)

1. DevTools Network tab при тапе на «Иду»: какой POST/PUT идёт, какой response
2. DevTools Console: есть ли errors / warns
3. React Query Devtools (доступны в dev): меняется ли query data после mutation, инвалидируется ли key
4. Spot-check `pages/EventPage.tsx` handlers: `handleVote`, `handleConfirm`, `handleDecline` — посмотреть что внутри, как обрабатывается success
5. Проверить `useMyVoteQuery` — fire'ится ли при mount, корректный ли queryKey

## Влияние

**Блокирующий** для основного user-flow клуба. Если member не может зарегистрировать участие в событии — теряется core-loop приложения (events это `raison d'être` платформы согласно PRD).

## Когда фиксить

**Срочно — до или сразу после следующего design iteration**. Без рабочих RSVP пользователи не могут пользоваться основной функциональностью. Также влияет на тестирование любых будущих UX дизайнов событий.

Кандидат в `bugfix/event-rsvp-broken`. ~1-2ч диагностики + фикс. Возможно потребуется backend coordination если endpoint возвращает unexpected response.

## Связанные файлы

- `frontend/src/pages/EventPage.tsx` — page consumer
- `frontend/src/queries/events.ts` — `useCastVoteMutation`, `useConfirmParticipationMutation`, `useDeclineParticipationMutation`, `useMyVoteQuery`, `useEventQuery`
- `backend/src/main/kotlin/com/clubs/event/EventController.kt` — vote/confirm/decline endpoints
- `backend/src/main/kotlin/com/clubs/event/VoteService.kt` — vote business logic

## Related backlog

- `docs/backlog/club-events-membership-check.md` — backend `EventService.getClubEvents` без membership check (similar event subsystem area)

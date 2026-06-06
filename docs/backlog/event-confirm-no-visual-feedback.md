# Подтверждение участия (Stage 2) — нет внятного визуального фидбэка

**Статус:** open · **Создано:** 2026-06-06 · **Origin:** ручной staging-тест `bugfix/event-dm-notification`
**Severity:** Medium (UX — пользователь не понимает, сработало ли действие, может жать повторно)

## Симптом

На странице приближающегося события в фазе подтверждения (`status = stage_2`) пользователь жмёт «Подтвердить участие» — **визуально ничего внятного не происходит**, хотя голос, судя по всему, реально подтверждается на бэкенде.

## Анализ

Хендлер `handleConfirm` (`frontend/src/pages/EventPage.tsx:92-104`):
- `haptic.impact('medium')` на клик, `confirmMutation.mutate(id, { onSuccess: () => haptic.notify('success'), onError: … haptic.notify('error') })`.
- Кнопка показывает `<Spinner>` пока `voting` (`confirmMutation.isPending`), затем — обычный текст.

`useConfirmParticipationMutation` (`frontend/src/queries/events.ts:77-87`) на успехе инвалидирует `events.detail` + `events.myVote` + `events.myFeed`. То есть бейдж «Подтверждён» (`EventPage.tsx:278`) и скрытие кнопки происходят **только после повторного refetch** detail/myVote.

Две причины «ничего не происходит внятно»:
1. **Нет Toast / явного success-сообщения.** Единственный мгновенный фидбэк — `haptic.notify('success')`, который на десктопе и части устройств не ощущается. Нарушает правило `docs/modules/haptic.md` § Side-effect требования: **тихий success без navigate ⇒ Toast**.
2. **Обновление UI отложено на round-trip.** Между завершением мутации (спиннер пропал) и приходом свежих `detail`/`myVote` есть окно, где кнопка снова выглядит как «Подтвердить участие» и ничего не изменилось — выглядит как «клик впустую».

Тот же паттерн вероятно затрагивает «Отказаться» (`handleDecline`, `EventPage.tsx:107`) и каст голоса Stage 1 (`handleVote`, `:78`) — проверить заодно.

## Воспроизведение

1. Событие в `stage_2`, пользователь голосовал `going`/`maybe` на Stage 1 (есть блок подтверждения).
2. Открыть страницу события, нажать «Подтвердить участие».
3. Наблюдать: спиннер на кнопке → исчезает → визимо без изменений ~полсекунды → бейдж «Подтверждён» появляется. Toast отсутствует.

## Предлагаемое решение

- Добавить `<Toast>` на success подтверждения/отказа (например «Участие подтверждено» / «Вы отказались») — по образцу из `SettingsTab` (PR #23/#26) и правила `haptic.md`.
- Рассмотреть optimistic update (`onMutate` ставит `myVote = 'confirmed'`) либо `setData` из ответа мутации (`confirmParticipation` возвращает `{ status, confirmedCount, participantLimit }`) — чтобы бейдж и счётчик обновлялись мгновенно, без ожидания refetch.
- Сверить единообразие фидбэка для Stage 1 vote, confirm и decline.

## Ссылки

- `frontend/src/pages/EventPage.tsx` — `handleConfirm` / `handleDecline` / `handleVote`, Stage 2 блок (`:273-297`)
- `frontend/src/queries/events.ts:77-99` — confirm/decline мутации (инвалидация)
- `frontend/src/api/events.ts:54-58` — `confirmParticipation` / `declineParticipation` (возвращают статус/счётчики — пригодны для optimistic/setData)
- `docs/modules/haptic.md` § Side-effect требования (правило: тихий success без navigate ⇒ Toast)
- Прецедент: `docs/backlog/event-rsvp-buttons-broken.md` (✅ RESOLVED, PR #30 — там был split error-state; здесь — недостаток success-фидбэка)

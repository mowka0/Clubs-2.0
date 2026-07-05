# Хэндофф: feature/stage2-open-to-all (двухэтапка — открытие Этапа 2 + лист ожидания + accountability)

> **Точка входа для новой сессии.** Контекст предыдущей сессии кончился. Ветка НЕ смёржена, на staging,
> PO нашёл баги (список — у PO, ниже раздел «⚠️ Что делать первым»). Дата хэндоффа: 2026-07-05.

## Статус в одну строку
Ветка `feature/stage2-open-to-all` (3 коммита над master, на staging, **НЕ смёржена**). Всё зелёное
(backend полный прогон + фронт 200 тестов). **PO тестировал на staging и нашёл баги — их править
в новой сессии.** Плюс висит одно неразрешённое продуктовое решение (жёсткий порог 4ч vs «клапан»).

## ⚠️ Что делать первым (новая сессия)
1. **Спросить у PO список багов**, которые он нашёл на staging (в этой сессии он их не детализировал —
   просто сказал «есть баги, править в новой сессии»). Staging сейчас крутит эту ветку.
2. **Разрешить открытое продуктовое решение** (см. ниже «Порог 4ч vs клапан») — PO слегка склонялся к
   «клапану» (−150), но не финализировал.
3. Работать по гейту: правки на ЭТОЙ ветке (`feature/stage2-open-to-all`) → staging → PO-тест →
   «готово, запушь» → PR (staging→master). Прямой merge в master запрещён (branch protection).

## Что построено (3 коммита)

**`cfaa1b1` — Этап 2 открыт всем участникам клуба**
- `Stage2Service.confirmParticipation`: снят гард «нужен голос going/maybe». `not_going` подтверждается;
  не голосовавший → `createLateStage2Entry` создаёт строку. `isMember` (доступ) остаётся.
  > ЗАМЕЩЕНО (2026-07-05): `createLateStage2Entry` больше НЕ ставит `stage_1_timestamp=now` — очередь
  > теперь по `stage_2_timestamp` (см. раздел «Модель мест по Этапу 2» ниже).
- DM о старте Этапа 2 → `findStage2InviteTelegramIds`: участники с доступом, у кого `stage_1_vote IS
  DISTINCT FROM 'not_going'` (going/maybe/не ответившие). Строится от memberships. `not_going` DM не шлём.
- Фронт `EventPage`: «Подтвердить участие» показывается всем, кроме терминальных Этапа-2; «Отказаться» —
  пока только going/maybe (доработано в 3-м коммите).

**`457dd18` — DM об отмене всем + лист ожидания на странице**
- `sendEventCancelled` → `findMemberTelegramIds` (ВСЕ участники клуба с доступом), не только going/maybe.
- `findRespondersWithUsers`: фильтр `(stage_1_vote IS NOT NULL OR final_status IS NOT NULL)` (не терять
  поздних участников) + `ORDER BY stage_1_timestamp ASC`.
  > ЗАМЕЩЕНО (2026-07-05): сортировка теперь `stage_2_timestamp ASC NULLS LAST, stage_1_timestamp ASC`
  > (ключ продвижения очереди = Этап 2). См. «Модель мест по Этапу 2» ниже.
- Фронт: секция «Лист ожидания» под «Кто идёт» — нумерованный список в порядке приоритета (`.rd-wl-*`).

**Сессия 2026-07-05 — модель мест по Этапу 2 + DM повышения + UI-тексты (эта ветка, поверх 3 коммитов)**
- **Гонка за места:** `triggerStage2` больше НЕ предраспределяет waitlist по голосам Этапа 1 — при старте
  Этапа 2 все `going`/`maybe` остаются pending, места разыгрываются подтверждениями. Этап 1 = только
  предварительный визуал (и фильтр `not_going` из DM). Очередь и её продвижение (`findFirstWaitlisted`,
  `findRespondersWithUsers`) — по `stage_2_timestamp`. Удалены мёртвые `findGoing/findMaybeByEventOrderByTimestamp`.
- **DM повышения:** `WaitlistPromotedEvent` (eventId + userId) → AFTER_COMMIT `WaitlistPromotedListener` →
  `NotificationService.sendWaitlistPromoted` (кнопка на `/events/{id}`). Публикуется в ОБОИХ местах
  авто-повышения: `Stage2Service.declineParticipation` и `MembershipService` (выход из клуба;
  `promoteFirstWaitlisted` теперь возвращает `UUID?` повышённого). Best-effort @Async, зеркалит `sendStage2Started`.
- **UI:** заголовок откликов на Этапе 1 → «Предварительные голоса» (Этап 2+ остаётся «Кто идёт»);
  инлайн-подтверждение отказа без замены явно называет штраф «спишется 100 очков».
- Миграция НЕ понадобилась — колонка `stage_2_timestamp` уже была в схеме (V6) и проставлялась `updateStage2Vote`.

**`7de1e55` — отказ подтверждённого + модель accountability** (самый крупный, трогает репутацию)
- `declineParticipation`: подтверждённый может отказаться → освобождает слот. Есть замена → первый из
  очереди авто-поднимается (штрафа 0); замены нет → штраф `abandoned_slot` −100 в той же транзакции.
- **Порог 4ч** (`events.stage2-decline-cutoff-minutes`, дефолт 240, env `STAGE2_DECLINE_CUTOFF_MINUTES`):
  подтверждённый не может отказаться в последние 4ч до старта. Waitlisted порогом не гейтится.
- **No-show теперь по факту подтверждения:** `ReputationPolicy.attendanceKind` даёт +100 пришёл / −200
  не пришёл для ЛЮБОГО confirmed (не только going/maybe). Закрыло дыру «not_going/no-vote подтвердился и
  не пришёл → 0 штрафа», которую внесло открытие Этапа 2.
- **Новый `reputation_kind = abandoned_slot`** (−100): миграция **V45** + jOOQ-enum дописан ВРУЧНУЮ
  (`backend/src/generated/jooq/.../ReputationKind.kt`; локальная codegen-БД не гонялась — `generateJooq`
  против свежей БД воспроизведёт). Trust-класс BROKE, XP 0. Метод `ReputationService.penalizeAbandonedSlot`
  (по образцу `penalizeExit`), вызывается из `Stage2Service`.
- Фронт: подтверждённый видит «Отказаться» (пока `now < confirmedDeclineDeadline`, инлайн-подтверждение с
  предупреждением про штраф, если замены нет); waitlisted видит «Отказаться» (выйти из очереди).
  **Порог фронт больше не хранит** — бэкенд отдаёт готовый дедлайн `confirmedDeclineDeadline` в
  `EventDetailDto` (см. ниже «хвосты» — долг закрыт).

## ⚠️ ОТКРЫТОЕ ПРОДУКТОВОЕ РЕШЕНИЕ: порог 4ч — жёстко vs «клапан»
Сейчас: подтверждённый за <4ч до старта **не может отказаться в принципе** → приходит или неявка −200.
PO спросил, не слишком ли жёстко (форс-мажор за 2ч → молча становится неявкой, орг узнаёт на месте).
Две философии:
- **Жёстко (как сейчас):** после 4ч ты обязался, точка.
- **«Клапан» (PO слегка склонялся):** после 4ч отказаться МОЖНО, но дороже — например **−150** (между
  ранним отказом −100 и молчаливой неявкой −200). Честность выгоднее молчания, орг получает сигнал.
- НЕ реализовано. Если выбрать «клапан»: убрать фронт-гейт `confirmedCanDecline` по 4ч (или заменить на
  «показывать всегда, но диалог предупреждает про −150 после порога»), убрать бэк-throw для confirmed <4ч
  → вместо него ветка штрафа с бóльшим значением. Возможно новый `reputation_kind` (`abandoned_slot_late`
  −150) ИЛИ переменные очки. Продумать с PO.

## Модель целиком (для контекста)
| Ситуация подтверждённого | Итог |
|---|---|
| Отказ ≥4ч, есть замена | №1 из очереди (по `stage_2_timestamp`) → confirmed + DM «место освободилось»; отказавшийся 0 |
| Отказ ≥4ч, замены нет | слот открыт; отказавшийся −100 (`abandoned_slot`) |
| Отказ <4ч | ❌ нельзя (сейчас); приходит или неявка −200 ← ОТКРЫТЫЙ ВОПРОС |
| Подтвердился, не пришёл | −200 (`no_show`/`spectator`), для ЛЮБОГО голоса Этапа 1 |
| Waitlisted «Отказаться» | выход из очереди в любой момент до старта, без штрафа |

## Тестирование на staging
- Для листа ожидания: событие с маленьким `participant_limit` (1–2), несколько аккаунтов.
- Для штрафа `abandoned_slot`: событие дальше порога до старта. Кнопку «Отказаться» у confirmed фронт
  прячет по дедлайну с бэка, поэтому **можно свободно ужимать `STAGE2_DECLINE_CUTOFF_MINUTES` в Coolify** —
  фронт подхватит новый дедлайн сразу после рестарта бэка (пересборка фронта не нужна, рассинхрона нет).
- Двухэтапка вообще: `STAGE2_TRIGGER_MINUTES_BEFORE` мал (событие >порога), см. events.md § staging.

## Известные хвосты / долги
- ~~**Фронт-порог захардкожен (4ч)** — расходится с бэк-конфигом, если его ужать на staging.~~
  **✅ ЗАКРЫТО (2026-07-05, ветка `feature/stage2-open-to-all`):** бэкенд отдаёт готовый дедлайн
  `confirmedDeclineDeadline` (= `eventDatetime − stage2-decline-cutoff-minutes`) в `EventDetailDto`
  (`EventMapper` инжектит тот же yaml-ключ, что `Stage2Service`). Фронт удалил константу
  `CONFIRMED_DECLINE_CUTOFF_HOURS` и прячет кнопку по `now < confirmedDeclineDeadline`. Реализовано
  как timestamp, а не `confirmedDeclineOpen: boolean` — дедлайн не пер-юзер, кэш-совместим, и клиент не
  повторяет логику сравнения. Бэк-гард `declineParticipation` не менялся (остаётся источником истины).
- **jOOQ-enum V45 дописан вручную** — при следующем `generateJooq` против свежей БД воспроизведётся.
- **Anti-farm для owner:** `penalizeAbandonedSlot` НЕ пропускает владельца клуба (в отличие от
  `processFinalizedEvent`, который фильтрует `userId != ownerId`). Edge: владелец подтвердил+отказался от
  своего события → получит −100. Если PO смущает — добавить owner-skip (нужен ownerId в Stage2Service).
- **Миграции:** V45 = `abandoned_slot`. Следующая свободная = **V46**. Отложенный S3 roles → V46.

## Ключевые файлы
- Бэк: `Stage2Service.kt`, `ReputationPolicy.kt`, `ReputationService.kt`, `TrustPolicy.kt`,
  `JooqEventResponseRepository.kt`, `NotificationService.kt`, `V45__reputation_kind_abandoned_slot.sql`,
  `application.yml` (2 новых конфига: `stage2-decline-cutoff-minutes`, ранее `stage2-poll-ms`).
- Фронт: `EventPage.tsx`, `redesign.css` (`.rd-wl-*`), `EventPageStage2.test.tsx`.
- Доки: `docs/modules/events.md` (§ Логика confirm/decline, конфиг-таблица), `reputation-v2.md`
  (attendanceKind + abandoned_slot), `telegram-bot.md` (аудитории DM).

## Что смёржено в ПРОД за прошлую сессию (не в этой ветке)
- PR #88 de-Stars (b549814), #89 stage2-confirm-window-fix (cc2dbf3), #90 русские комментарии+V44
  (62faedd), #91 staging-gate + branch protection (4f5726f).
- **Гейт деплоя теперь жёсткий:** ничто не идёт в master минуя staging; все ветки кроме master →
  staging; прямой push/merge в master заблокирован (PR обязателен, чек `Gitleaks`, admin-обход).

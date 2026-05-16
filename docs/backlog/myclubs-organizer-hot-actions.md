# MyClubs — индикатор «требуется внимание» для клубов где user organizer

**Статус:** open · **Создано:** 2026-05-16 · **Origin:** brainstorm на этапе club-page-redesign

## Проблема

Organizer открывает MyClubsPage и видит свои клубы как обычный список. Но у одного из них может быть **3 заявки в ожидании** или **событие сегодня**, а у второго ничего. Сейчас узнать что срочно — только зайти в каждый /manage. Это лишние тапы и риск пропустить важное.

## Идея

В каждой клуб-карточке на MyClubsPage (только для organizer-роли), если есть pending applications или upcoming events эта неделя — небольшой индикатор:

```
[ClubCard]
Книжный клуб «Полночь»
🟡 3 заявки ждут · Событие 18 мая
```

- Если pending applications > 0 — pill brass «N заявок ждут»
- Если есть событие сегодня — pill live «Сегодня в 19:00»
- Если есть событие в ближайшие 7 дней — pill ink-2 «Событие 18 мая»
- Если ничего срочного — pill не показывается

Тап на pill ведёт в `/clubs/:id/manage` в соответствующий таб (заявки или события).

## Зачем

Organizer не пропускает заявки (sustained engagement → revenue) и не забывает про подготовку к ближайшему событию. Не нужно открывать каждый клуб ради проверки.

## Что нужно сделать

### Backend
Расширить `useMyClubsQuery` ответ для клубов где user — organizer:
```kotlin
data class MyClubItemDto(
    // existing fields
    val pendingApplicationCount: Int?, // null если user не organizer этого клуба
    val nextEventDate: Instant?,       // null если нет upcoming events
)
```

Источник:
- `pendingApplicationCount`: `SELECT COUNT(*) FROM applications WHERE club_id = ? AND status = 'pending'`
- `nextEventDate`: `SELECT MIN(event_datetime) FROM events WHERE club_id = ? AND status IN ('upcoming', 'stage_1', 'stage_2')`

**Performance:** обе агрегации можно делать одним запросом с GROUP BY clubs.id для всех клубов organizer'а. N+1 не нужен.

### Frontend
- В `frontend/src/components/MyClubCard.tsx` (или прямо в MyClubsPage если нет отдельного компонента) — добавить условный render hot-action pill
- Pill стилизация — pill-варианты brass / live / ink-2 (как `.cp-chip.role` / `.club-card .meta .signal`)
- Тап на pill — `e.stopPropagation()` чтобы не открыть ClubPage, и `navigate('/clubs/:id/manage')` с query-param `?tab=applications` или `?tab=events`

## Acceptance Criteria

- AC-1: member-карточка клуба (где user не organizer) — без изменений, никаких pill.
- AC-2: organizer-карточка с >0 pending applications — brass pill «N заявок ждут», тап ведёт на /manage с активной applications tab.
- AC-3: organizer-карточка с событием сегодня — live pill «Сегодня в HH:MM», тап ведёт на /manage с активной events tab.
- AC-4: organizer-карточка с событием в ближайшие 7 дней (но не сегодня) — ink-2 pill «Событие DD месяц».
- AC-5: если оба условия (заявки + событие) — обе pill, заявки первой.
- AC-6: pending count считается с `applications.status = 'pending'` (не approved, не rejected).

## Risks / Open Questions

- **R-1:** Что если organizer = owner двух клубов и у одного 50 заявок? Pill всё равно «50 заявок ждут» — это сигнал, не запрос дать ответ. Без cap.
- **R-2:** Должны ли member видеть подобный сигнал, например «Событие 18 мая»? Сомнительно — он откроет страницу клуба чтобы записаться, ему этого достаточно. Не делаем для V1.
- **R-3:** Производительность query при N = 50+ клубов у одного organizer? Нужен EXPLAIN ANALYZE. Возможно нужен COUNT-индекс на applications(club_id, status).
- **R-4:** Tab-param в URL: `/clubs/:id/manage?tab=applications`. Сейчас OrganizerClubManage не поддерживает query-param для активного таба — придётся добавить.

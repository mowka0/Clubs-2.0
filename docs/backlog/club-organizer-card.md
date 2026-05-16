# «Кто ведёт» — карточка организатора на ClubPage для visitor

**Статус:** open · **Создано:** 2026-05-16 · **Origin:** brainstorm на этапе club-page-redesign

## Проблема

Visitor открывает страницу клуба и не видит, кто его ведёт. В платных закрытых сообществах — это сильный сигнал доверия / отсутствия доверия. Сейчас он узнаёт владельца только после вступления (через members tab).

## Идея

Маленькая навейная карточка между about/rules и lock-card в visitor-view:

```
КТО ВЕДЁТ
[Avatar 40]  Анна К.
             ● 95 надёжность · клуб с августа 2025
```

- Avatar 40px (placeholder с инициалами если нет загруженного)
- Имя организатора (без фамилии)
- Reliability dot + index (sage/brass/ink-3 по той же шкале как members-tab)
- Дата создания клуба
- Без линка на профиль организатора (пока такой страницы нет)

## Зачем

Конверсия visitor → join. Социальное доказательство. Сейчас единственный сигнал «живого клуба» — это member count, но он не персонифицирован.

## Что нужно сделать

### Backend
Опция A — расширить `ClubDto`:
```kotlin
data class OrganizerPreviewDto(
    val firstName: String,
    val avatarUrl: String?,
    val reliabilityIndex: Int,
)
// добавить ClubDto.organizer: OrganizerPreviewDto
```
Источник: уже в БД через `clubs.owner_id` → join к `users` + `user_reputation`.

Опция B — отдельный endpoint `GET /api/clubs/:id/organizer-preview` (если не хочется раздувать ClubDto).

**Privacy:** показывать только публично-видимое (имя, аватар, reliability). Никаких email/телефонов/фамилии (фамилия — личное дело юзера).

### Frontend
- `frontend/src/components/club/ClubOrganizerCard.tsx` — новый компонент, читает данные из ClubDto (или из отдельного query)
- Рендерится только для visitor-view (после `cp-card` rules, до `cp-locked`)
- Стилизация: navy-card + brass dot reliability + ink-2/3 для текста

## Acceptance Criteria

- AC-1: visitor видит карточку с именем + аватаром + reliability + founded date.
- AC-2: member/organizer **не видят** эту карточку — она дублирует то что они и так знают.
- AC-3: данные приходят за тот же запрос что и сам клуб (один сетевой round-trip), не отдельный лоадер.
- AC-4: если у организатора нет аватарки — fallback на инициалы (как в `ClubMembersTab`).

## Risks / Open Questions

- **R-1:** Имя или имя+фамилия? Имя дешевле privacy-wise. Можно опционально добавить фамилию через user-preference «показывать фамилию в публичных карточках» — но это отдельная feature, пока имя.
- **R-2:** Reliability index публично — ок? Это уже видно member'ам. Visitor — это step before member, не качественно «более чужой». PRD §4.4 говорит индекс — публичный метрик в клубах. Думаю да.
- **R-3:** Что если клуб создан давно, но недавно сменился владелец? `clubs.owner_id` = текущий, но "клуб с августа 2025" — дата создания клуба, не дата смены. ОК для V1.

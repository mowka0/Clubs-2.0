# «Связь и группа» — блок социальных каналов клуба

**Статус:** open · **Создано:** 2026-05-16 · **Origin:** brainstorm на этапе club-page-redesign

## Проблема

> **Update 2026-07-07 (club-chat-link):** `clubs.telegram_group_id` дропнута (V47) — привязка чата теперь в `club_chat_links` с полноценным флоу (deep link, верификация владельца, кнопка «Чат клуба» участникам). Часть этого backlog'а закрыта; остальное (соцсети-ссылки) — актуально.

У клуба может быть привязанная Telegram-группа (было `clubs.telegram_group_id` из PR #17, теперь `club_chat_links` V47). Сейчас этот функционал доступен только organizer'у на /manage, member её не видит из ClubPage. Чтобы зайти в групповой чат — нужно отдельно искать в Telegram.

Дополнительно: со временем клубам логично иметь и другие каналы — Instagram, сайт, Discord, YouTube для образовательных, etc. Если добавлять по одному без планирования — будет «прибитыми гвоздями» добавлять кнопку, потом ещё кнопку.

## Идея

Зарезервировать на ClubPage **отдельный блок** для всех внешних ссылок клуба (member-only видимость):

```
СВЯЗЬ
[💬 Группа в Telegram     →]
[📷 Instagram             →]
[🌐 Сайт                  →]
```

Блок появляется только если есть хотя бы один линк. Каждый — навейный row с brass-2 иконкой / labelом + chevron справа. Тап открывает Telegram deep-link или внешний URL.

Один блок, growth-ready — добавление новой соцсети = одна строка в DTO + один row в render.

## Зачем

- **Member-value:** убрать трение «найти группу клуба» и «найти их Instagram». Сейчас обе боли есть.
- **Organizer-marketing:** клубы с активным IG / сайтом могут это показывать → больше доверия для visitor (см. backlog `club-organizer-card.md` — те же выгоды для visitor могут расшириться сюда).
- **Design discipline:** один блок > пять разбросанных кнопок.

## Что нужно сделать

### Backend
Расширить `ClubDto`:
```kotlin
data class ClubLinksDto(
    val telegramGroupUrl: String?,   // t.me/+invitehash or t.me/groupname
    val instagram: String?,          // https://instagram.com/handle
    val website: String?,            // https://...
    val youtube: String?,
    val discord: String?,
    // grow as needed — flat key-value structure, не enum/map
)
// ClubDto.links: ClubLinksDto
```

Privacy: блок виден только member'ам. Visitor видит только название клуба, не подключённые каналы (иначе можно случайно слить контент через Instagram-постинги до вступления).

### Database
Миграция: одна `clubs.links_json` JSONB колонка, или per-platform столбцы. JSONB гибче для добавления новых платформ без миграции.

Уже существующая колонка `clubs.telegram_group_id` (PR #17) — мигрировать значения в новую structure (формат: full URL, не только chat_id). Старая колонка может остаться для invite-flow.

### Settings UI
В `/clubs/:id/manage` settings tab — секция «Связи» с inputs для каждой платформы. Validation:
- Telegram URL: `^https?://t\.me/` или `t.me/`
- Instagram: `instagram.com/handle` без полного URL — auto-prefix
- Website: any valid URL with `http(s)://`

### Frontend
- `frontend/src/components/club/ClubLinksBlock.tsx` — новый компонент, рендерит non-null entries из `club.links`
- Платформа → иконка маппинг (используем emoji иконки или small SVG)
- Tap action:
  - Telegram → `tg://resolve?domain=...` deep-link с fallback на https URL
  - Остальные → `window.open(url, '_blank')`
- Haptic `impact('light')` (как любой navigation)

## Acceptance Criteria

- AC-1: club с одним только telegramGroupUrl — блок «СВЯЗЬ» с одной строкой.
- AC-2: club с тремя ссылками — блок с тремя rows в порядке: TG → Instagram → Website → YouTube → Discord (фиксированный порядок).
- AC-3: club без ссылок — блок не рендерится вовсе.
- AC-4: visitor — блок никогда не рендерится (privacy).
- AC-5: organizer в settings может добавить/убрать/изменить любую ссылку. Невалидный URL — error в input (RHF rule).
- AC-6: TG-ссылка открывается в Telegram desktop/mobile (deep-link, не браузерная вкладка).

## Risks / Open Questions

- **R-1:** Что если клуб «продаёт» через Instagram — это конкуренция с платформой? Платформа = инфра, она ничего не теряет если клуб ведёт коммуникацию в TG-группе. Не блокируем.
- **R-2:** Какие ещё платформы? VK (RU-аудитория), Discord (для геймерских клубов), TikTok? Решим когда появится продуктовый спрос. JSONB-структура позволит добавлять без миграции.
- **R-3:** Открывать ссылки в Telegram WebApp vs новой вкладке? Внешние URL — `window.open` ок. Telegram deep-link — нативный handler в TG.
- **R-4:** Должен ли organizer-card (`club-organizer-card.md`) тоже включать его личные соцсети? Нет — это про клуб, не про человека. Личные ссылки — на странице профиля юзера, когда она появится.

## Связанное

- `club-organizer-card.md` — другой member-only блок на ClubPage (карточка организатора)
- `frontend.md` § «Component reuse» — если этих блоков будет много (links + organizer + что-то ещё), стоит выработать общий `<ClubInfoBlock>` примитив, не повторять навейные карточки 5 раз

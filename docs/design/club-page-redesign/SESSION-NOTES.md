# Club page redesign — session handoff

**Branch:** `feature/club-page-redesign`
**Status:** mockups V1 (drafted from Discovery brand language)
**Last updated:** 2026-05-16

## Контекст

Discovery редизайн (PR #33) уже задал brand-язык: dark navy + brass, Inter, ambient mesh, frosted cards, capacity bars, brand-tabbar с brass-чёрточкой. ClubPage сейчас — старый telegram-ui дизайн (var(--tgui--*) переменные), визуальный разрыв при переходе с Discovery.

Подход: положить ClubPage в тот же визуальный регистр без переработки поведения. Все queries / mutations / haptic / role-aware visibility — preserved. Меняется только presentation layer.

## Mockups V1

- [`mockups/01-club-member.html`](mockups/01-club-member.html) — основной кейс (compact header), featured nearest event
- [`mockups/02-club-organizer.html`](mockups/02-club-organizer.html) — то же + tab «Управление», featured nearest event
- [`mockups/03-club-visitor.html`](mockups/03-club-visitor.html) — closed-club + lock-card + CTA
- [`mockups/04-club-member-hero.html`](mockups/04-club-member-hero.html) — alt header variant: avatar 120px + name 28px stacked centered

Open all in browser:
```bash
open docs/design/club-page-redesign/mockups/*.html
```

Shared токены и классы: [`mockups/_shared.css`](mockups/_shared.css).

## Convention: screenshots после каждого изменения

При любой правке мокапа — автоматически перерендерить соответствующий PNG в [`images/`](images/) через headless Chrome. Пользователь смотрит превью через PNG, не открывая браузер.

```bash
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
"$CHROME" --headless=new --disable-gpu --hide-scrollbars --no-sandbox \
  --window-size=390,1450 --force-device-scale-factor=2 \
  --screenshot="docs/design/club-page-redesign/images/01-club-member.png" \
  "file://$(pwd)/docs/design/club-page-redesign/mockups/01-club-member.html"
```

Высоты per mockup:
- `01-club-member`: 1450 (полный events tab с past)
- `02-club-organizer`: 1450 (то же + 4-й tab)
- `03-club-visitor`: 1100 (короче — нет tabs/event content, только lock + CTA)

## V1 → V2 диалог с пользователем (2026-05-16)

- ✅ Featured-tint на ближайшее событие — applied к мокапам 01/02/04. Стиль subtle: brass-border 22%, тонкий brass-top gradient 4.5%, без тяжёлой brass-glow shadow (попросил «не сильно ярко»).
- 📋 «Кто ведёт» — карточка организатора (visitor-focused) → backlog [`docs/backlog/club-organizer-card.md`](../../backlog/club-organizer-card.md). Не в этом PR (нужен backend).
- 📋 Organizer hot-actions индикатор — перенесён на MyClubsPage (логичнее показывать в списке клубов, а не на странице конкретного) → backlog [`docs/backlog/myclubs-organizer-hot-actions.md`](../../backlog/myclubs-organizer-hot-actions.md).
- 📋 «Связь и группа» блок — TG-группа + extensible под будущие платформы → backlog [`docs/backlog/club-social-links-block.md`](../../backlog/club-social-links-block.md).
- ⏳ Hero-variant хедера — mockup 04 готов, ждёт сравнения с 01 чтобы определиться compact vs hero.

## Решения V1

### Header / Cover
- Avatar 88×88, category-gradient (как в `.club-card .avt` из Discovery, но крупнее)
- Member/organizer аватар получает `.featured` ободок (brass-glow) — чувство «ты внутри»
- Visitor аватар без `.featured` — мягкий сигнал «не свой»
- Name 23px weight 700 letter-spacing -0.018em
- Chips row: category + access-type + role-badge (member/organizer only)
- Role-chip с brass-soft заливкой + brass-dot

### Stats row (заменяет 3 Cell-строки)
- 3 stat-колонки в одной карточке: Участники (с mini capacity-bar), Подписка (`1 200 ₽/мес`), Где (Москва / Чистые пруды как stacked)
- Каждая колонка: маленький uppercase brass-2 лейбл + крупное значение
- Vertical divider между ними (hairline)
- Заменяет current `<Cell subtitle="...">` блок

### About / Rules cards
- Navy-card-2 surface + hairline border + blur(8px)
- Маленький uppercase brass-2 label («О клубе» / «Правила»)
- Rules — text цветом ink-3 (muted), чтобы differentiate от описания

### Visitor: lock card + CTA
- Brass-soft круглая иконка с замком, brass-acid headline, объяснение действия
- CTA — brand-кнопка (brass-fill, navy-text, 52px, brass-glow тень)
- Под кнопкой — hint о ходе ("Организатор задаст один вопрос")

### Tabs
- Заменяет telegram-ui `TabsList` на pill-row (как discovery-chips)
- Active: brass-fill + navy-text + weight 600
- Manage tab: outline-стиль с brass-deep + стрелкой `→` справа (signal of navigation, not toggle) — поддерживает уже принятое решение из спеки ([R-2] в `docs/modules/club-page-unified.md`)

### Events tab
- Event-row карточки в navy-card стиле
- Слева: date pill 48px — крупный день (brass-deep) + uppercase short month (brass-2)
- Справа: title + meta (время · место) + going pill
- Going pill: navy-soft по умолчанию, brass-soft когда almost-full (>= 80%)
- Past events: opacity 0.85 + date в ink-3 (без brass)

### Members tab
- Member-row в навейной карточке (`rgba(26,33,56,0.55)` — субтиле)
- Avatar 40, имя + organizer-badge inline
- Reliability — цветной dot (sage если >85, brass deep если 70-85, ink-4 если <70) + число brass-2

### Profile tab
- Profile-head: navy-card с avatar 60 + имя + @username
- Под ним grid 3 stats — большие brass-deep числа + uppercase ink-3 labels

## Открытые вопросы V1 (что хочется услышать от пользователя)

1. **Header — достаточно ли крупный?** Avatar 88px + name 23px против hero-style avatar (например 120px + name 28px). Стартовая ставка — функционально достаточно, не выглядит «отдельной landing-страницей».
2. **Stats card vs три отдельных карточки/блока.** Stats card — компактнее и стилистически похоже на capacity bar из discovery. Альтернатива — три отдельных stat-card (как Profile tab).
3. **Top-bar** — оставлен минимальный crumb «Клуб · Из &laquo;Поиск&raquo;» для ориентации. Можно убрать совсем (Telegram native back уже даёт контекст).
4. **Manage tab** — pill с стрелкой `→`. Альтернатива — отдельная Section-кнопка внизу страницы.
5. **Locked card для visitor** — формулировка / визуальный вес. Не слишком ли «closed-shop»?
6. **Event-row date pill** — formal или можно сделать day-of-week instead? «Сб · 18» vs «18 / Май».

## Workflow (после согласования)

1. User iterates mockups → final pick
2. Update SESSION-NOTES с решениями
3. Port в TSX: ClubPage.tsx + ClubEventsTab/ClubMembersTab/ClubProfileTab
4. brand-theme.css — добавить ClubPage классы (cp-*)
5. Build/test, push staging, user test, merge

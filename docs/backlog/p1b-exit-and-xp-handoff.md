# Handoff — P1b продолжение: выход-с-обязательствами (PR-b) + XP (PR-c)

> Подготовлено 2026-06-13 после мержа **P1b core (PR-0+PR-a) в прод — PR #62 (3fd4def)**.
> Цель — чтобы следующая сессия стартовала без раскопок. Дизайн уже залочен; это импл-задачи.
>
> Первоисточники (читать как контракт): `docs/modules/reputation-v2.md` **§ P1b** (H5 — выход,
> H3 — XP), дизайн-рационал `docs/backlog/reputation-v2-redesign.md` **§7.1** (выход) + §6/§9.1.
> Память: [[project_reputation_v2_design]], [[project_work_queue]].

---

## 1. Где мы (P1b core ✅ в проде #62)

Показывается **Trust 0–100** (байес-доля сдержанных слов по `kind`, recency-decay, новичок ~85);
сырой `reliability_index` (Σ очков) стал **internal**. Глобал — «надёжность + опыт в N клубах»
**по всей истории вкл. покинутые** (дыра A закрыта); `/me/reputation` → `MyReputationDto
{global, activeClubs, historyClubs}`. Member-list/профиль на Trust (организатор первым). «История»
покинутых клубов — во вкладке «Клубы». Миграция **V25** (kept/broke/neutral_count). Следующая = **V26**.

Чистый код-фундамент: `TrustPolicy` (чистая формула, тесты `TrustPolicyTest` локируют контракт-числа),
`TrustService` (on-read из ledger: `computeForUser`, `trustForClubMembers`, `trustForUserInClub`),
`findTrustOutcomesByUser` / `findClubMemberOutcomes` в `ReputationRepository`.

---

## 2. PR-b — ВЫХОД-С-ОБЯЗАТЕЛЬСТВАМИ (приоритет: закрывает дыру B)

### Что за дыра B
`MembershipService.leaveFreeClub` делает **хард-DELETE** `confirmed` `event_responses`
(`eventResponseRepository.deleteByUserAndClubAndActiveEvents`) и skladchina-участий
(`skladchinaRepository.deleteParticipantFromActiveSkladchinasInClub`) **ДО** `cancel` → штраф −200
за брошенную подтверждённую бронь **никогда не приземляется**. Выход с обязательств бесплатен.
(Подтверждено грунт-агентом G3 в дизайн-сессии.)

### Что строим (дизайн залочен, H5)
Перечислить открытые реп-обязательства **ДО каскада**, записать штрафы, потом каскад — **в одной
`@Transactional`**:
- **type-1:** `confirmed` `event_responses` на **НЕ-finalized** событиях → **−200**, kind `no_show`,
  `occurred_at = events.event_datetime`. **+ промоут листа ожидания** (слот не пропадёт).
- **type-2:** `pending` `skladchina_participants` на сборах с `affects_reputation=true` и **не прошедшим
  дедлайном** → **−40**, kind `skladchina_expired`, `occurred_at = deadline`.
- Штрафы залочены (−200 / −40). `sum = events×200 + skladchinas×40`.
- **Переиспользуем существующие kinds** `no_show`/`skladchina_expired` — **новой миграции не нужно**.
- **Идемпотентность:** `UNIQUE(user_id, source_type, source_id)` + `ON CONFLICT DO NOTHING`. Если событие
  потом закроется штатно и попытается записать natural `no_show` для того же `(user, event)` — коллизия,
  выигрывает первый (exit-штраф). Двойной/повторный выход не задваивает.
- **Owner не выходит** (`leaveClub` режет owner) — доп. фильтр не нужен, но `appendAndRecompute` всё равно
  не пишет owner-строк (anti-farm rule 1).
- **Paid-клубы:** `leavePaidClub` **не каскадит** → штрафа нет (доступ до конца подписки, обязательства
  гасятся естественно). Штраф-на-выходе — только free / «жёсткий» выход.

### UX (2-call, H5)
- **GET preview-эндпоинт:** те же 2 SQL → возвращает `{count, ...}` для диалога («Вы бросаете N
  обязательств, потеряете {sum} надёжности»). **Penalty-математика server-side (internal)**; наружу — count
  + факт «сломаете N обязательств» (self-visible).
- **Confirm** → собственно выход со штрафом.

### Хук (где править)
`MembershipService` (membership): inject `ReputationService` (или `TrustService`-сосед — уже есть
`reputationRepository`). В `leaveFreeClub`: **enumerate → `appendAndRecompute(entries)` → существующий
каскад → cancel → decrementMemberCount**, всё в одной txn. Готовый enumeration-SQL — в facts-report
сессии (memory [[project_reputation_v2_design]]) / повторить через G3-паттерн.

### Ключевые файлы
- `backend/.../membership/MembershipService.kt` (`leaveClub`/`leaveFreeClub`/`leavePaidClub`)
- `backend/.../event/JooqEventResponseRepository.kt` (`deleteByUserAndClubAndActiveEvents` — дыра B)
- `backend/.../skladchina/...` (`deleteParticipantFromActiveSkladchinasInClub`)
- `backend/.../reputation/{ReputationService.appendAndRecompute, LedgerEntry}.kt`
- Фронт: поток выхода из клуба (где зовётся `leaveClub`) + новая confirm-модалка с preview.
- Схема: `event_responses` (V6), `events.attendance_finalized` (V5), `skladchinas`/`skladchina_participants`
  (`status`, `affects_reputation`, `deadline` — V14+).

### Питфоллы
- **Enumerate ДО каскада** (иначе source-строки уже удалены).
- `occurred_at` = `event_datetime` (type-1) / `deadline` (type-2) — время поведения, не now (decay читает это).
- Waitlist-промоут на освободившийся confirmed-слот.
- Не трогать paid-путь (там штрафа быть не должно).

---

## 3. PR-c — XP / УРОВНИ / БЕЙДЖИ (без стрика) ✅ реализовано

Дизайн залочен (H3). **Greenfield** (G5: ни XP/level/badge/streak нигде нет). Отдельный от Trust канал
(копится, не падает; broken promise = 0 XP, не минус). Derive **on-read из ledger** (как Trust):
`XpPolicy` (чистая формула) + `XpService` (один `findTrustOutcomesByUser`) + `GET /api/users/me/gamification`.
- **XP-веса:** `ironclad` +10, `spontaneous` +8, `skladchina_paid` +3, негативы/neutral 0; diversity +20
  за первый kept в новом клубе.
- ⚠️ **Organizer-XP ВЫКИНУТ** (решено 2026-06-14, PO): `+15` за событие ≥3 / `+8` за складчину ≥2 убраны —
  «организаторский результат» = сигнал о МЕСТЕ, живёт в клуб-треке (ось «Надёжность организатора»),
  не в личной геймификации (иначе тройной счёт явки). XP = участие-only. Анти-фарм наследуется
  бесплатно (owner без ledger-строк в своём клубе). См. `reputation-v2.md` § H3 + [[project_club_quality_track]].
- **10 уровней** `XP(n)=round(50·n^2)` (откалибровано 2026-06-14, было `40·n^1.85`): Гость/Свой/Участник/
  Завсегдатай/Активист/Энтузиаст/Душа компании/Столп сообщества/Легенда/Амбассадор.
- **Бейджи** ~11 (trust/diversity/organizer-семьи) — показывают пройденный порог, не сырой score.
  **Стрик — отложен** (добавить позже, если зайдёт).
- **Visibility:** `self` = точное XP + прогресс-бар до уровня; `others` = только название уровня.
- Новый эндпоинт `/me/gamification` (или расширить профиль). Анти-фарм наследуется (owner копит только
  organizer-XP в своём клубе; diversity + пороги ≥3/≥2).

---

## 4. Что НЕ переоткрывать (залочено)

- Trust-формула + константы (prior .85/K3/ASYM2/HL90), глобал-агрегат, V25-счётчики — в проде.
- Штрафы выхода −200/−40; переиспользование kinds; paid не штрафует сразу.
- Гейт PR-2 (перенос владения) всё ещё открыт: anti-farm rule 1 на текущем `owner_id` → RED-тест
  `ReputationLedgerIntegrationTest` (AC-7). PR-b его не трогает (выход не меняет owner_id).
- «N из M» = internal-агрегат (показ = балл+тир+опыт). Не выносить «N из M» в UI.

---

## 5. Флоу (как обычно)

Ветка `feature/reputation-p1b-exit` (или `.../p1b-xp`). Кодить → гейты **Reviewer → Security → Analyst
(docs alignment)** → коммит → пуш → staging (`https://staging.77-42-23-177.sslip.io`) → тест → «готово,
запушь» → `gh pr merge --squash` (без `--delete-branch`) → master авто-деплой в prod.

> 🧠 Рекомендуемый уровень рассуждений для PR-b: **`high`** (нетривиальная txn-логика выхода, enumerate
> до каскада, идемпотентность, preview + модалка; цена ошибки высокая — пишем штрафы в ledger).
> Для PR-c: **`high`** на дизайн весов/эндпоинта, **`medium`** на фронт-плумбинг.

---

## 6. После PR-b/PR-c — клуб-трек

Качество клуба (place, не person): факты + геймификация + ранжирование. Дизайн готов:
`docs/backlog/club-quality-gamification.md`, память [[project_club_quality_track]]. Отдельный top-level
модуль `com.clubs.clubquality`. Фундамент-кирпич — `membership_history` (грин-лайт PO).

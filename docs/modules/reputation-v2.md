# Reputation v2 — P1a (Ledger Foundation) — спецификация

**Статус:** spec locked, готова к коду · **Ветка:** `feature/reputation-ledger` · **Создано:** 2026-06-07
**Origin:** `docs/backlog/reputation-v2-redesign.md` (дизайн-решения) + адверсариальная хардовка дизайна
(7 экспертных линз, 8 блокеров закрыты до кода).

> Этот документ — **имплементируемый контракт P1a**. Рационал и продуктовые «зачем» — в
> `docs/backlog/reputation-v2-redesign.md`. После мержа P1a и post-flight alignment содержимое
> сворачивается в `docs/modules/reputation.md` (текущая спека описывает **старую** аддитивную модель).
>
> **⚠️ Терминология P1b (см. § P1b ниже).** В разделе P1a отображаемое пользователю число
> репутации называется `reliabilityIndex` (= сырой `reliability_index`). **В P1b оно переименовано
> в `trust` (Trust 0-100) во всех DTO**, а сырой `reliability_index` стал internal-сырьём. Где ниже
> в P1a-тексте встречается `reliabilityIndex` как DTO-поле / показ — читать как `trust`. Актуальный
> API-контракт — § P1b «API-контракты (PR-a)».

---

## Цель

Структурно починить расчёт репутации: заменить аддитивную модель (которая инфлирует — баг B)
на **append-only ledger** как источник истины, из которого `user_club_reputation` пересчитывается
как **кэш**. Плюс анти-фарм правило 1 (владелец не копит репутацию в своём клубе) и честное
отображение новичка. Итог P1a: **«репутация снова считается и не врёт»**, существующие видимые
метрики сохраняются (теперь корректные и идемпотентные).

## Scope

### Входит (P1a — этот PR)
- Таблица `reputation_ledger` (append-only) + enum-типы. Миграция **V17** (DDL), **V18** (бэкфилл).
- Идемпотентность бага B — структурно (ON CONFLICT + атомарный клейм `events.reputation_processed`).
- Ось «явка» на ledger: 5 kind'ов, точный паритет с текущим `ReputationService.computeDeltas`.
- Ось «финансы» (Складчина) — **перемаршрутизирована** через ledger; в P1a значения и механика
  **не менялись** (paid +10 / declined −5 / expired −25). Удаляем прямые записи в `reliability_index`.
  *(Update 2026-06-12: веса финансов пересмотрены пост-P1a — см. § «Ось „финансы"» ниже.)*
- `user_club_reputation` → кэш, единственный писатель = recompute-from-ledger (атомарный upsert).
- Событийная связь: `AttendanceFinalizedEvent` (low-latency) + poll (durable backstop).
- Анти-фарм правило 1: владелец не получает ledger-строк в своём клубе (live + бэкфилл).
- Новичок → отсутствие строки = «нет данных»; UI показывает «Новичок» (владельцу в своём клубе —
  организаторскую рамку).
- Удаление мёртвого аддитивного кода: `computeDeltas`, `applyDeltas`, `addReliabilityDelta`.

### НЕ входит (следующие PR)
- **P1b:** Trust 0–100 (per-club + глобальный, recency-decay), XP/уровень, полировка visibility-тиров
  §9.1 (грубый бейдж вместо сырого числа другим). **В P1a сырое корректное число показывается как есть.**
- **P2:** механика «организатор сверил оплату», изменение финансовых значений, анти-фарм правила 2–4.
- **P-org:** вычисляемый организаторский блок/статистика.
- **Перенос владения** (club-leave PR-2): **заблокирован** до фриза атрибуции (см. § Риски).

---

## Доменная модель

### Таблица `reputation_ledger` (V17)

```
CREATE TYPE reputation_axis   AS ENUM ('attendance', 'finance');
CREATE TYPE reputation_kind   AS ENUM (
  'ironclad', 'no_show', 'spontaneous', 'spectator', 'confirmed_unresolved',
  'skladchina_paid', 'skladchina_declined', 'skladchina_expired');
CREATE TYPE reputation_source AS ENUM ('event', 'skladchina');

CREATE TABLE reputation_ledger (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      UUID NOT NULL REFERENCES users(id),
  club_id      UUID NOT NULL REFERENCES clubs(id),
  axis         reputation_axis   NOT NULL,
  kind         reputation_kind   NOT NULL,
  points       INT               NOT NULL,
  occurred_at  TIMESTAMPTZ       NOT NULL,
  source_type  reputation_source NOT NULL,
  source_id    UUID              NOT NULL,
  created_at   TIMESTAMPTZ       NOT NULL DEFAULT NOW(),
  UNIQUE (user_id, source_type, source_id)        -- НЕ включает kind (см. ниже)
);
CREATE INDEX idx_reputation_ledger_user_club ON reputation_ledger (user_id, club_id);
CREATE INDEX idx_reputation_ledger_source    ON reputation_ledger (source_type, source_id);

ALTER TABLE events ADD COLUMN reputation_processed BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_events_reputation_pending ON events (id)
  WHERE attendance_finalized AND attendance_marked AND NOT reputation_processed;

-- Порог показа (см. § Новичок): сколько у юзера репутационных исходов в клубе.
ALTER TABLE user_club_reputation ADD COLUMN outcome_count INT NOT NULL DEFAULT 0;
```

**Почему UNIQUE без `kind`** (блокер #5): одна строка на (юзер, источник). Если бы `kind` входил в
ключ, спор по явке, разрешённый в другой kind после уже записанного `confirmed_unresolved`, дал бы
**вторую** строку → двойной счёт `total_confirmations`. `(user_id, source_type, source_id)` структурно
исключает это: `ON CONFLICT DO NOTHING` оставляет первую запись. Для обеих осей у юзера ровно один
исход на источник → ключ корректен. `kind` остаётся колонкой (нужен для деривации счётчиков).

`occurred_at` (блокер #7) — **инвариант, одинаковый в live и backfill**:
- attendance → `events.event_datetime` (стабильно, воспроизводимо, есть в обоих путях; верный якорь
  будущего decay «время с момента поступка»). **Запрещено** `now()`/finalize-time.
- finance → `skladchinas.closed_at` (момент решения; всегда выставлен на не-active сборах).

### Деривация кэша `user_club_reputation` из ledger (recompute)

`reliability_index` остаётся NOT NULL в БД и в домене `Reputation`. «Нет данных» = **отсутствие строки**
в `user_club_reputation`, не NULL-колонка (блокер #8).

recompute(userId, clubId) — единственный писатель кэша, атомарный upsert:

```
INSERT INTO user_club_reputation (user_id, club_id, reliability_index, promise_fulfillment_pct,
                                  total_confirmations, total_attendances, spontaneity_count, updated_at)
SELECT :userId, :clubId,
  COALESCE(SUM(points), 0),                                                    -- reliability: ВСЕ оси
  -- promise_fulfillment_pct считается в Kotlin (BigDecimal HALF_UP scale 2), см. ниже
  COUNT(*) FILTER (WHERE axis='attendance'),                                   -- total_confirmations
  COUNT(*) FILTER (WHERE axis='attendance' AND kind IN ('ironclad','spontaneous')), -- total_attendances
  COUNT(*) FILTER (WHERE kind='spontaneous'),                                  -- spontaneity_count
  COUNT(*),                                                                    -- outcome_count (все оси)
  MAX(occurred_at)
FROM reputation_ledger WHERE user_id=:userId AND club_id=:clubId
ON CONFLICT (user_id, club_id) DO UPDATE SET
  reliability_index=EXCLUDED.reliability_index, ... , outcome_count=EXCLUDED.outcome_count,
  updated_at=EXCLUDED.updated_at;
```

`outcome_count` = число ledger-строк (любая ось) = «сколько у юзера репутационных исходов в клубе».
Используется порогом показа (см. § Новичок). `reliability_index`/счётчики в кэше остаются **истинными**;
порог — чисто презентационный.

- `promise_fulfillment_pct` = conf>0 ? `round(att*100/conf, 2 HALF_UP)` : 0. В live-пути считаем в
  Kotlin (`BigDecimal`, `RoundingMode.HALF_UP`, scale 2) — точный паритет с текущим
  `ReputationService.kt:113`. В V18 (чистый SQL) — `ROUND(att*100.0::numeric / conf, 2)` (numeric, не
  double — Postgres ROUND на double = half-to-even).
- recompute читает ledger **в той же транзакции** что upsert (консистентный снапшот, коммутативность
  под конкуренцией — блокер #2). **Сериализация per (user, club):** в начале recompute берётся
  `pg_advisory_xact_lock(hashtext("$userId:$clubId"))` (освобождается на commit). Без него два
  конкурентных recompute из **разных источников** (явка + сбор) для одной пары под READ COMMITTED
  могли бы не увидеть несзакоммиченную строку друг друга и затереть кэш (lost update). С advisory-локом
  последний recompute видит обе закоммиченные строки → итог корректен. Это критично для scale-out
  (несколько реплик / async listener executor), где claimEvent уже не делает обработку эксклюзивной.
- В live ledger append-only → у юзера никогда не «исчезают» строки → recompute всегда вызывается, когда
  есть ≥1 строка. Удаление кэш-строки нужно только в бэкфилле (rebuild, см. V18).

### Ось «явка»: маппинг ответа → kind

Строка ledger пишется **только** для `event_responses.final_status = 'confirmed'`. Не-confirmed
(declined / waitlisted / null) → строки нет (вклад 0 во всё, как и в текущем коде). Инвариант
`confirmed ⟹ stage_1_vote ∈ {going, maybe}` подтверждён в коде (`Stage2Service.confirmParticipation`
отвергает прочие до выставления `confirmed`) → ветка `else` ниже достижима только через
disputed/null attendance.

> **Решение 2026-06-11 — очки только от этапа-2 × явки.** Голос этапа-1 — опрос для планирования,
> репутацию не формирует: после подтверждения брони награда/штраф одинаковы для going и maybe
> (+100 пришёл / −200 не пришёл). `kind` при этом по-прежнему различается по stage-1 голосу —
> он питает **отображаемую черту** `spontaneity_count`, не очки.
> **Штраф асимметричен намеренно** (−200 = два посещения; break-even явки 67%): прогул
> подтверждённой брони сжигает слот и план организатора. Ledger-строки, записанные до решений
> (spontaneous +30 / spectator −20 / прогулы −50), не реконсилировались (намеренно: только
> staging-объёмы; points в append-only строках остаются как записаны).

| stage_1_vote | attendance | kind | points | conf | att | spont |
|---|---|---|---|---|---|---|
| going | attended | `ironclad` | +100 | 1 | 1 | 0 |
| going | absent | `no_show` | −200 | 1 | 0 | 0 |
| maybe | attended | `spontaneous` | +100 | 1 | 1 | 1 |
| maybe | absent | `spectator` | −200 | 1 | 0 | 0 |
| (любой) | disputed **OR** null | `confirmed_unresolved` | 0 | 1 | 0 | 0 |

> **Отметка явки (решение 2026-06-11, рев. 2):** в форме отметки все confirmed по умолчанию
> «пришёл», организатор снимает галочку с отсутствующих; UI шлёт явное значение для каждого
> видимого участника (снятая галочка = absent → −200 + DM «оспорьте»). `confirmed + attendance
> IS NULL → confirmed_unresolved (0)` остаётся достижимым только через гонку «подтвердился после
> загрузки формы» (defensive). EXP-2 (форма не сохранялась вовсе) нейтрален. При споре
> организатору уходит DM (`AttendanceDisputedEvent` → `sendAttendanceDisputed`) — игнор спора
> осознанный, а не случайный.

`confirmed_unresolved` — **терминальный** kind, не плейсхолдер: после финализации attendance
заморожен (`resolveDispute` требует `!finalized`; повторная отметка невозможна), поэтому переход в
другой kind после обработки не происходит. Точный паритет: текущий `computeDeltas` для confirmed+null
и confirmed+disputed даёт reliability 0, conf+1, att+0 — идентично.

> **Блок 1 (живой путь, 2026-06-07) — взаимодействие с пайплайном** (детали и AC в
> `docs/modules/events.md` § «Репутация — Блок 1»):
> - **ATT-2:** `AttendanceService.finalizeAttendance` конвертирует остаточные `disputed → absent` **до**
>   публикации `AttendanceFinalizedEvent` (в той же транзакции; листенер читает ростер AFTER_COMMIT).
>   Поэтому `disputed` до пайплайна на финализированном событии не доходит → `(going, absent) = no_show`.
>   Ветка `disputed → confirmed_unresolved` в `attendanceKind` остаётся **defensive**-страховкой; `null`
>   по-прежнему достижим (confirmed-участник, которого орг не включил в отметку) → `confirmed_unresolved`.
> - **EXP-2:** нейтральная авто-финализация ставит `attendance_finalized=true` при `attendance_marked=false`.
>   Клейм (`claimEvent` / `findPendingFinalizedEventIds`) требует `attendance_marked=true`, поэтому такие
>   события **никогда не входят в пайплайн** — ledger-строк ноль без отдельного флага. `AttendanceFinalizedEvent`
>   для них не публикуется.

### Ось «финансы»: Складчина → ledger

> **Решение 2026-06-12 — веса пересмотрены** (редизайн репутации складчины,
> `docs/backlog/skladchina-reputation-redesign.md`; имплементация — пакет 3 реестра багов):

| `skladchina_participant_status` | kind | points |
|---|---|---|
| paid | `skladchina_paid` | **+10** |
| declined | — **строки нет** (kind `skladchina_declined` больше не эмитится: `financeKind(declined) → null`) | 0 |
| expired_no_response | `skladchina_expired` | **−40** (было −25) |
| released *(новый статус: сбор закрыт досрочно, `closed_at < deadline`)* | — **строки нет** | 0 |
| pending | — (строки нет) |

- Отказ без строки, а не 0-строкой — иначе инфлируется `outcome_count`
  (три отказа = выход из «Новичка» без обязательств). Kind `skladchina_declined`
  остаётся в enum ради исторических −5-строк (не реконсилируются — staging-объёмы).
- Обоснования величин (+10 = 1/10 ironclad; −40 = 1/5 no_show) и гейты тумблера —
  `docs/modules/skladchina.md` § Reputation deltas и `skladchina-reputation-redesign.md`.

Только если `skladchina.affects_reputation = true`. Финансовые строки дают вклад в `reliability_index`
(SUM points), но **не** в счётчики явки (`COUNT FILTER axis='attendance'`). На момент P1a был паритет
с прежним `addReliabilityDelta` (трогал только `reliability_index`); значения P1a (+10/−5/−25)
действовали до решения 2026-06-12.

---

## Анти-фарм правило 1 (владелец ≠ выгодоприобретатель)

При построении ledger-строк (live и backfill) строка **не пишется**, если `user_id = club.owner_id`.
Закрывает оба соло-сценария (создал событие/сбор → сам «исход» → +репутация), т.к. создатель
события/сбора всегда = владелец (`EventService`, `SkladchinaService` это форсят), и отметку явки делает
только владелец. Со-организаторы — вне scope (single-owner модель).

**Ретроактивно:** бэкфилл не пишет owner-строк → rebuild обнуляет ранее самоначисленную владельцем
репутацию в своём клубе. Это **намеренно**. Аудит: V18 логирует (RAISE NOTICE) число подавленных
(owner, club) пар и суммарно снятые очки.

**Отображение владельцу** (продуктовое решение): в своём клубе показываем не «Новичок» и не число, а
**организаторскую рамку** — «Здесь репутация начисляется за организаторские качества» (задел под P-org).
Применяется в «Моя репутация» (профиль, карточка своего клуба) и в блоке «Участники» страницы клуба
(строка владельца). Технически: строка с `role = owner` И `reliabilityIndex = null` → рамка; обычный
`null` без owner → «Новичок».

---

## Идемпотентность и обработка (бага B — структурно)

Единый метод `processFinalizedEvent(eventId)` (вызывается и listener'ом, и poll'ом),
`@Transactional(propagation = REQUIRES_NEW)`:

1. **Клейм** (атомарный, взаимоисключает listener и poll — блокер #3):
   `UPDATE events SET reputation_processed = true WHERE id = :eventId AND NOT reputation_processed`
   через `.returning(EVENTS.ID)`. Если строка не вернулась — событие уже обработано, **no-op**.
2. Если заклеймлено: прочитать `event_responses` где `final_status = confirmed`; построить ledger-строки
   (skip owner); `INSERT ... ON CONFLICT (user_id, source_type, source_id) DO NOTHING`.
3. recompute для каждого затронутого `user_id` (upsert).

- `ON CONFLICT DO NOTHING` — backstop поверх клейма (блокер #1: единственный писатель кэша = recompute;
  старые аддитивные пути удалены, двух писателей нет).
- Изменение точек для уже записанного kind redeploy'ем **не** пересчитывает историю (points не в ключе).
  Смена формулы потребует отдельной reconciliation-миграции. Задокументировано как append-only-свойство.

### Событийная связь

- `AttendanceFinalizedEvent(eventId: UUID)` — Spring `ApplicationEvent`.
- `AttendanceService.finalizeAttendance`: `finalizeAttendanceBefore(cutoff)` меняется на возврат
  `List<UUID>` финализированных id (`UPDATE ... RETURNING id` — без select-then-update гонки); публикует
  по одному `AttendanceFinalizedEvent` на id внутри `@Transactional`.
- Listener: `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` →
  `processFinalizedEvent(eventId)`. Контракт: **listener = low-latency best-effort, poll = гарантия**.
  Если listener-транзакция упала — флаг не выставлен (клейм откатился) → poll добьёт (≤1ч).
- Poll `processReputationForFinalizedEvents` (`@Scheduled`, остаётся): выбирает события
  `attendance_finalized AND attendance_marked AND NOT reputation_processed`, на каждый —
  `processFinalizedEvent`.

### Складчина

`SkladchinaService.applyReputationDeltas` переписывается: вместо `reputationService.addReliabilityDelta`
строит finance ledger-строки (skip owner) и вызывает общий `appendAndRecompute(entries)`. Per-participant
флаг `reputation_applied` остаётся как skladchina-side guard; ledger UNIQUE — backstop. `addReliabilityDelta`
удаляется (единственный вызыватель — этот метод).

---

## Бэкфилл / миграция V18 (чистый SQL, выполняется один раз)

Порядок (всё в одной Flyway-транзакции; подтвердить `flyway.mixed` выключен):
1. **Снапшот** (необратимость — блокер #6): `CREATE TABLE user_club_reputation_pre_v18 AS SELECT * FROM
   user_club_reputation;` — **только форензика**. Пре-образ сам инфлирован багом B → «восстанавливать» в
   него нельзя. Комментарий в миграции это фиксирует.
2. **Attendance ledger** из истории: `event_responses er JOIN events e JOIN clubs c`,
   `WHERE er.final_status='confirmed' AND e.attendance_finalized AND e.attendance_marked
   AND er.user_id <> c.owner_id`; `kind`/`points` через `CASE (er.attendance, er.stage_1_vote)` с явной
   веткой `ELSE 'confirmed_unresolved'` (для null/disputed); `occurred_at = e.event_datetime`;
   `source_type='event'`, `source_id=e.id`; `ON CONFLICT (user_id, source_type, source_id) DO NOTHING`.
   Все каст-литералы явно типизированы (`'ironclad'::reputation_kind`, `'event'::reputation_source`,
   `'attendance'::reputation_axis`).
3. **Finance ledger** из истории (одноразовая миграция — выполнена с весами P1a +10/−5/−25,
   ре-эмиссии под веса 2026-06-12 нет): `skladchina_participants p JOIN skladchinas s JOIN clubs c`,
   `WHERE s.affects_reputation AND s.status <> 'active' AND p.status IN
   ('paid','declined','expired_no_response') AND p.user_id <> c.owner_id`; `occurred_at = s.closed_at`;
   `source_type='skladchina'`, `source_id=s.id`. **Фильтр `p.reputation_applied` НЕ используется** —
   он выставляется безусловно даже при сбое начисления (`markReputationApplied` вне try/if), т.е. не
   доказывает «дельта применена». Ключ — `status`; ledger UNIQUE гарантирует идемпотентность. Бэкфилл
   = «как должно было быть» (net-positive self-heal), может отличаться от текущего инфлированного прода.
4. `UPDATE events SET reputation_processed = true WHERE attendance_finalized AND attendance_marked;`
5. **Rebuild кэша**: `DELETE FROM user_club_reputation;` затем `INSERT ... SELECT` агрегата из ledger
   (FILTER-форма выше), `updated_at = MAX(occurred_at)` per (user, club). (Исторически от этого
   зависел `ClubsBot.findLatestByUserId`; команда `/мой_рейтинг` и сам метод удалены 2026-06-12,
   но `updated_at = MAX(occurred_at)` сохранено — на нём строится свежесть кэша.)
6. `RAISE NOTICE` с числом подавленных owner-пар и снятых очков (аудит).

**Оракул валидации** (не в миграции — в интеграционном тесте): на засеянной истории независимо
пересчитать ожидаемые агрегаты и сравнить с rebuild. QA-оракул = **пересчёт из `event_responses`**, НЕ
текущий (инфлированный) `user_club_reputation`. Пост-V18 числа для уже финализированных событий
**ожидаемо ниже** текущего прода — это не баг.

---

## Новичок: «право на ошибку» + nullable propagation

**Продуктовое решение (право на ошибку):** не судим, пока мало данных. Юзер показывается как
**«Новичок» (без числа) всем**, пока у него `outcome_count < N` в этом клубе, где
`N = MIN_OUTCOMES_FOR_REPUTATION_DISPLAY = 3` (internal-константа, tunable). Одна ранняя оплошность
(пустозвон = −200) не клеймит новичка — число не показывается, пока нет ≥3 исходов. Статистически
честно (1 точка данных — не репутация). **Факты при этом видны** в peer-сигнале («посетил 0 из 1») —
организатор не слепой, но ярлыка «−200» нет. Полноценный «буфер прощения» — в P1b: **Trust 0–100 с
оптимистичным приором** (новичок стартует с кредитом доверия ~85; одна ошибка слегка просаживает,
**паттерн** ошибок роняет, поведение восстанавливает: `Trust = (kept + K·prior)/(total + K)`,
`prior≈0.85`, `K≈3` — internal).

**Условие новичка (P1a):** `строка user_club_reputation отсутствует` **ИЛИ** `outcome_count < N`.
В обоих случаях `reliabilityIndex = null` и блок репутации подавляется. Порог применяется в read-проекциях
единообразно (хелпер `displayedReliability(reliabilityIndex, outcomeCount): Int?`), кэш хранит истину.

«Нет данных» = отсутствие строки `user_club_reputation` **или** sub-threshold. Домен/кэш-колонки — NOT NULL.
Nullable только на DTO-границе. Для новичка **подавляется весь блок** репутации (один чип «Новичок»), а не
«Новичок · обещания 0% · 0 подтв.» (противоречиво).

Backend (сделать `Int` → `Int?` на DTO-границе, убрать `?: 100` / `COALESCE(...,100)`):
- `UserClubReputationDto`, `MemberProfileDto`, `MemberListDto`, `ClubMemberInfo`, `UserClubReputationInfo`
  — `reliabilityIndex: Int?` (+ siblings null для новичка / блок подавлен).
- Read-проекции несут `outcome_count` и применяют порог через хелпер
  `displayedReliability(reliabilityIndex, outcomeCount): Int?` (null если строки нет ИЛИ
  `outcomeCount < 3`). Сиблинги (`promiseFulfillmentPct`/счётчики) тоже подавляются для новичка.
- `MemberService.getMemberProfile`: `displayedReliability(...)` (без `?: 100`).
- `JooqMembershipRepository`: убрать `COALESCE(RELIABILITY_INDEX, 100)` (2 места) и `?: 100` (2 места),
  селектить `outcome_count`, применять порог;
  **`ORDER BY reliability_index DESC NULLS LAST`** (иначе NULLS FIRST выкидывает новичков наверх —
  блокер #8). Сортировку строить так, чтобы coalesced-значение не попадало в проекцию.
- `ClubsBot.kt:203`: `null` → «Новичок» (owner-only юзер → уже существующая ветка «репутация ещё не
  сформирована»).
- Нести `role` (owner/admin/member) в DTO там, где его нет, для организаторской рамки.

Frontend (`reliabilityIndex: number | null`):
- `ProfilePage`: средняя надёжность **фильтрует null** перед усреднением (`null` исключается, не = 0 →
  иначе NaN травит всё среднее); `reliabilityTier(score: number | null)` с веткой `'new'`; карточка
  своего клуба (role owner) → организаторская рамка.
- `DiscoveryPage`: то же усреднение с фильтром null (**не было** в исходном списке — добавить).
- `ClubMembersTab` / `MemberProfileModal`: `null` → «Новичок»; владелец → организаторская рамка.
- `api.ts`, `queries/members.ts` — типы.

`aggregateByUserIds` / peer-signal: семантика `memberClubCount` = **«клубы с track-record»** (строки
в `user_club_reputation`). Новички/владельцы своих клубов честно не входят → applicant-новичок
показывается как «Новый пользователь». Это намеренно и зафиксировано (не молчаливый дрейф).

---

## API контракты

Изменений эндпоинтов нет; меняются только формы DTO (поля → nullable). Затронуты:
`GET /api/users/me/reputation` (`UserClubReputationDto`), `GET /api/clubs/:id/members/:userId`
(`MemberProfileDto`), `GET /api/clubs/:id/members` (`MemberListDto`),
`GET /api/users/me/applications-pending` (peer-signal — семантика сохранена).

---

## Acceptance Criteria

**AC-1 (идемпотентность):** GIVEN финализированное событие с confirmed-ответами. WHEN
`processFinalizedEvent` вызван дважды (или listener+poll конкурентно). THEN в `reputation_ledger` ровно
по одной строке на (user, event); `reliability_index`/счётчики не меняются между прогонами.

**AC-2 (паритет):** GIVEN засеянная история без владельцев-выгодоприобретателей. WHEN бэкфилл V18 + recompute.
THEN для каждой (user, club) `reliability_index`, `total_confirmations`, `total_attendances`,
`spontaneity_count`, `promise_fulfillment_pct` равны независимому пересчёту из `event_responses`
по таблице явки + Складчине.

**AC-3 (правило 1):** GIVEN владелец отметил себя attended на своём событии / сам оплатил свой сбор.
WHEN обработка/бэкфилл. THEN ledger-строк для владельца в своём клубе нет; его `user_club_reputation`
строки для своего клуба нет; UI показывает организаторскую рамку.

**AC-4 (новичок):** GIVEN юзер без ledger-строк в клубе. WHEN `GET /api/clubs/:id/members`. THEN
`reliabilityIndex = null`; в списке участников сортируется внизу (NULLS LAST); фронт рендерит «Новичок»;
средняя надёжность в профиле не включает его (не NaN, не 0).

**AC-4b (право на ошибку, порог N):** GIVEN юзер с одним исходом (пустозвон −200, `outcome_count = 1`).
WHEN любой read репутации. THEN `reliabilityIndex = null` (показывается «Новичок», не «−200»), т.к.
`outcome_count < 3`. GIVEN юзер с `outcome_count = 3`. WHEN read. THEN показывается реальное число.
Кэш при этом всегда хранит истинный `reliability_index` (порог — презентационный).

**AC-5 (disputed/null):** GIVEN confirmed-ответ с `attendance = null` или `disputed` на финализированном
событии. WHEN обработка. THEN одна строка `confirmed_unresolved` (0 очков), `total_confirmations += 1`,
`total_attendances += 0`. Повторная обработка не создаёт второй строки.

**AC-6 (Складчина через ledger):** GIVEN закрытый сбор с `affects_reputation=true`, участники
paid/declined/expired/released. WHEN `closeInternal`. THEN finance ledger-строки только для
paid (+10) и expired (−40), skip owner; declined/released — строк нет (веса 2026-06-12);
`reliability_index` пересчитан из ledger; прямых записей через `addReliabilityDelta` нет (метод удалён).

**AC-7 (transfer landmine, RED):** GIVEN ledger-строки начислены, затем `club.owner_id` сменился.
WHEN recompute. THEN тест **фиксирует** переатрибуцию (новый владелец теряет прошлую заработанную
репутацию; старый — перестаёт подавляться). Тест помечен как гейт для PR-2 (перенос владения).

## Non-functional

- recompute/append — в одной транзакции; upsert `ON CONFLICT (user_id, club_id) DO UPDATE`.
- Listener — `AFTER_COMMIT` + `REQUIRES_NEW`; ошибка одного события не роняет финализацию и логируется.
- Партиал-индекс на poll-предикат; индексы ledger на (user_id, club_id) и (source_type, source_id).
- Sensitive data не логируется; RAISE NOTICE в V18 — только агрегатные счётчики, без PII.
- Существующий `ReputationServiceTest` реимплементирует логику инлайн (тестирует копию) → заменить
  тестами реального сервиса/ledger.

## Интеграции

- **event** (`AttendanceService`, `EventRepository`): публикация `AttendanceFinalizedEvent`,
  `finalizeAttendanceBefore → List<UUID>`, чтение confirmed-ответов.
- **skladchina** (`SkladchinaService`): finance-строки через `appendAndRecompute`, удаление
  `addReliabilityDelta`-зависимости.
- **membership** (`JooqMembershipRepository`, `MemberService`): nullable reliability, NULLS LAST, role.
- **bot** (`ClubsBot`): null → «Новичок».
- **application** (peer-signal): семантика track-record сохранена/задокументирована.

## Риски и открытые вопросы

- **[ГЕЙТ PR-2] Перенос владения.** Правило 1 живёт на текущем `club.owner_id`; ledger историчен,
  recompute переатрибутирует историю после смены владельца. PR-2 (**перенос** владения, а также
  любая будущая операция, меняющая `owner_id` или запускающая recompute) **не может
  мержиться**, пока атрибуция владельца не заморожена на момент записи (снапшот `owner_id` в контекст
  события/сбора, либо иной фриз). RED-тест AC-7 — landmine для PR-2.
  > **Не путать с `bugfix/club-delete-cascade` (2026-06-13).** Каскад при soft-delete клуба
  > **репутационно-нейтрален**: он только переводит дочерние события/складчины в `cancelled`,
  > **не пишет ledger-строк, не запускает recompute и не меняет `owner_id`**. Поэтому этот гейт
  > его не блокирует — он не трогает атрибуцию владельца. См. `docs/modules/clubs.md` § DELETE.
- **Необратимость V18.** Снапшот `_pre_v18` + обязательный бэкап БД перед деплоем + оракул-тест.
- **append-only immutability.** Смена точек существующего kind требует reconciliation-миграции, не
  redeploy.
- **occurred_at заморожен** на `event_datetime`/`closed_at` — P1b decay читает эти значения; менять
  семантику после записи нельзя.

---

# P1b — Trust 0–100 + Global + выход-с-обязательствами + XP

**Статус:** дизайн залочен 2026-06-13 (ultracode-сессия, 3 workflow + AskUserQuestion) · **Ветка:**
`feature/reputation-p1b-trust` · core-first. Рационал — `docs/backlog/p1b-trust-handoff.md` +
`reputation-v2-redesign.md`. Память: [[project_reputation_v2_design]].

> P1a показывает сырой `reliability_index = Σpoints`. P1b заменяет его на **Trust 0–100** — байесовскую
> recency-взвешенную долю сдержанных слов, выводимую ИЗ ledger **по kind** (не по points: V18 забэкфиллил
> stale-магнитуды, points через границу V18 ненадёжны). `reliability_index` остаётся internal-сырьём.

## Фазы (порядок зависимостей)

- **PR-0** (фундамент): миграция **V25** — `kept_count/broke_count/neutral_count` в `user_club_reputation`;
  recompute заполняет их COUNT-FILTER по kind. Backfill данных НЕ нужен (ledger полон) — один recompute-прогон.
- **PR-a** (корень): per-club Trust + Global «N из M» по ВСЕЙ истории + UI «История клубов». Замена
  active-only `avgReputation` (ProfilePage/DiscoveryPage — клиентский дубль) на server-side агрегат.
- **PR-b** (независим) ✅: выход-с-обязательствами (закрывает дыру B). См. § H5 + `docs/modules/club-leave.md`.
- **PR-c**: XP/уровни/бейджи (стрик отложен). **PR-e**: финализация visibility-тиров.

## H1 — per-club Trust (approach B)

kept/broke/neutral по **kind** (магнитудо-независимо, корректно через границу V18):

| kept | broke | neutral (вне знаменателя) |
|---|---|---|
| `ironclad`, `spontaneous`, `skladchina_paid` | `no_show`, `spectator`, `skladchina_expired` | `confirmed_unresolved`, `skladchina_declined` (историч.) |

```
decay_i = 0.5 ^ (age_days(occurred_at) / 90)
w_i     = 1 (kept) | 2 (broke)        # ASYM=2
Trust   = round( 100 · ( Σ_kept decay_i + K·prior ) / ( Σ_kept decay_i + 2·Σ_broke decay_i + K ) )
          prior = 0.85, K = 3
```
Decay читает `reputation_ledger.occurred_at` (время поведения), считается **on-read** (меняется со
временем → НЕ кэшируется). Кэш-счётчики (V25) дают дешёвый non-decay знаменатель для read-проекций.
Контрольные числа (залочены в `TrustPolicyTest`): новичок=85, 1 промах=52, 3 явки=92, 5:1=76,
3 прогула=30, восстановление 3 события=89.

## H2 — Global «надёжен в N из M клубов»

По **ВСЕЙ истории** (вкл. покинутые клубы — источник `reputation_ledger`/`user_club_reputation` по
`user_id`, БЕЗ active-only фильтра).
- `M` = клубы с `outcome_count ≥ 3` (есть track record); `N` = из них с per-club `Trust ≥ 70`.
- Число (вторичное) = diversity/recency-взвешенное среднее per-club Trust, вес клуба
  `w_c = (outcome/(outcome+3)) · 0.5^(мес_с_посл/12)`, нормированный вес клипуется на **0.5** (water-filling).
- Главный показ — «Надёжен в N из M клубов»; число — вспомогательное `{Global}/100`.

## H6 — Новичок / нет данных (UI)

- per-club `outcome_count < 3` → «Новичок» (без числа), как P1a.
- Global `M = 0` → «Пока недостаточно истории», число скрыто. **Никогда** «0 из 0» / сырой 85 из <3-клуба.
- Global `N = 0, M ≥ 1` → «Надёжен в 0 из M клубов», число показывается (честный сигнал).
- Владелец в своём клубе → «Организатор» рамка (анти-фарм rule 1).

## API-контракты (PR-a — реализовано)

Эндпоинт не меняет URL, меняет **форму ответа**. `reliabilityIndex` (P1a) → `trust` во всех
per-club DTO; `GET /api/users/me/reputation` теперь возвращает **обёртку** `MyReputationDto`
(global + два списка) вместо плоского active-only `List<UserClubReputationDto>`.

### `GET /api/users/me/reputation` → `MyReputationDto`

```kotlin
data class MyReputationDto(
    val global: GlobalTrustDto,
    val activeClubs: List<UserClubReputationDto>,   // member имеет доступ И клуб активен
    val historyClubs: List<UserClubReputationDto>   // покинутые клубы с track record («История»)
)

data class GlobalTrustDto(
    val reliableClubs: Int,        // N — клубы с per-club Trust >= 70
    val trackRecordClubs: Int,     // M — клубы с outcome_count >= 3 (вся история)
    val score: Int?                // вторичное 0-100; null при trackRecordClubs == 0
)
```

- Список клубов теперь включает покинутые (`active-only` фильтр снят — закрыта дыра A); `active`
  вычисляется в SQL (`status active|grace_period` ИЛИ ещё не истёкшая подписка, И `club.is_active`),
  membership-сторона делит на `activeClubs`/`historyClubs`.
- `per-club Trust` и global считаются **on-read** из ledger (`TrustService`), НЕ из кэша
  (decay меняется со временем). Кэш-счётчики (`kept/broke/neutral_count`, V25) — дешёвый non-decay
  знаменатель, не сам показ.

### Per-club DTO (`UserClubReputationDto`, `MemberProfileDto`, `MemberListItemDto`)

Поле `reliabilityIndex: Int?` → **`trust: Int?`** (P1b Trust 0-100). `null` = «Новичок»/подавлено
(`outcome_count < 3`, или владелец своего клуба). Сиблинги (`promiseFulfillmentPct`/счётчики)
подавляются вместе с ним. Сортировка списка участников (`GET /api/clubs/:id/members`) —
по **отображаемому Trust** DESC в `MemberService` (новички/null уходят вниз), а не SQL `ORDER BY`.

## H5 — выход-с-обязательствами (PR-b) ✅ реализовано

Дыра B: `MembershipService.leaveFreeClub` хард-DELETE'ил `confirmed` `event_responses` ДО отмены →
−200 за брошенную бронь не приземлялся. Фикс: **enumerate ДО каскада**, в одной `@Transactional`.

**Несущий инвариант:** каскад + штраф вместе покрывают каждую удаляемую строку-обязательство ровно один раз.
- **type-1** = `confirmed` ответы на активных **НЕ-finalized** событиях → **−200** (kind `no_show`,
  `occurred_at = event_datetime`) + промоут waitlist на освободившийся слот (под per-event advisory-lock).
  **Каскад тоже исключает finalized** (`deleteByUserAndClubAndActiveEvents` фильтрует `NOT attendance_finalized`):
  событие бывает finalized, пока статус ещё `stage_2` (finalize ставит флаг, статус → `completed` ставит
  отдельный sweep). Реальный исход finalized-события пишет reputation-пайплайн — выход его не трогает и не стирает.
- **type-2** = `pending` участия в активных `affects_reputation` складчинах → **−40** (kind `skladchina_expired`,
  `occurred_at = deadline`). **Дедлайн НЕ фильтруется** (уточнение к исходному дизайну «не прошедшим дедлайном»):
  каскад удаляет pending-участие независимо от дедлайна, поэтому участие с уже прошедшим дедлайном иначе
  ускользнуло бы и от выхода, и от естественного expiry. Натуральный исход тот же (−40), при коллизии выигрывает первая строка.
- Переиспользуем существующие kinds (без новой миграции); идемпотентность через
  `UNIQUE(user_id, source_type, source_id)` (ON CONFLICT DO NOTHING — natural-строка при закрытии события/складчины
  не задвоит). Owner не leaving (`leaveClub` режет owner). **Paid-клуб не штрафует сразу** (доступ до конца подписки).
- API: `ReputationService.penalizeExit(userId, clubId, eventNoShows, skladchinaExpiries)` — kind/points/axis/source
  внутри reputation-модуля; membership передаёт только `ExitObligation(sourceId, occurredAt)`.
- UX 2-call: `GET /api/clubs/{id}/leave-preview` → `LeavePreviewDto{eventObligations, skladchinaObligations,
  totalObligations}` (только счётчики; penalty-математика internal) → confirm. Спека выхода — `docs/modules/club-leave.md`.

## H3 — XP / уровни / бейджи (PR-c; стрик отложен)

Отдельный от Trust канал: **глобальная геймификация аккаунта** (человек), копится, **не падает**
(broken promise = 0 XP, не минус). Derive из ledger on-read — чистое зеркало Trust, тот же источник
(`findTrustOutcomesByUser`), без новых таблиц/запросов.

**XP = ТОЛЬКО участие** (решено 2026-06-14, PO). Organizer-XP (`+15` за событие ≥3, `+8` за складчину ≥2)
из исходного дизайна **выкинут**: «организаторский результат» — это сигнал о МЕСТЕ, он живёт в
**клуб-треке** (ось «Надёжность организатора» в качестве клуба), не в личной геймификации. Иначе один
сигнал явки считался бы трижды (Trust участника + organizer-XP + качество клуба) и вернул бы рамку
«оцениваем организатора-человека», от которой ушли. См. [[project_club_quality_track]].

- XP-веса (kept-kinds): `ironclad +10`, `spontaneous +8`, `skladchina_paid +3`; негативы и neutral = 0.
- **diversity +20** за первый kept-исход в каждом НОВОМ клубе (по distinct club_id с ≥1 kept).
- 10 уровней `XP(n)=round(50·n^2)` (порог уровня k = `round(50·(k-1)^2)`, k=1..10, уровень 1 = 0 XP):
  пороги [0, 50, 200, 450, 800, 1250, 1800, 2450, 3200, 4050]. Кривая откалибрована 2026-06-14
  (`40·n^1.85` → `50·n^2`, верхние уровни реже). Имена:
  Гость / Свой / Участник / Завсегдатай / Активист / Энтузиаст / Душа компании / Столп сообщества / Легенда / Амбассадор.
- **Анти-фарм** наследуется бесплатно: owner не имеет ledger-строк в своём клубе (rule 1) → в своём клубе
  XP не копит, только участием в чужих клубах. Никаких organizer-гейтов больше не нужно.
- **Бейджи** — семьи trust / diversity / участие (organizer-бейджи ушли в клуб-майлстоны). Показывают
  пройденный порог, не сырой score. Набор калибруется (старт — `XpPolicy.BADGES`).
- Эндпоинт `GET /api/users/me/gamification` → `GamificationDto`.

## H8 — visibility-тиры

- `self`: точное XP + прогресс-бар, бейджи, разбивка «почему», «сломаете N обязательств» при выходе.
- `others`: название уровня, публичные бейджи, «надёжен в N из M», вторичное Global-число.
- `internal`: diversity/Sybil-confidence, **все константы** (K/prior/ASYM/decay/веса/cap), сырой
  `reliability_index`/минус, fraud-флаги, ranking/match-score, провизорные начисления.

**Реализация others-tier — уровень на карточках участников (PR-e):** в списке участников клуба
(`MemberListItemDto.levelName`) и в профиле участника (`MemberProfileDto.levelName`) другим показывается
ТОЛЬКО **название глобального уровня** аккаунта (напр. «Активист»). Точное XP, бейджи, веса, пороги и сырьё
наружу не отдаются. Уровень **глобальный** (по всему ledger, не per-club) — поэтому новичок в этом клубе
может нести уровень, заработанный в других, и `levelName` НЕ гейтится «Новичком»-порогом этого клуба
(в отличие от per-club Trust). Гейт проекции: показывается только **выше пола** (индекс ≥ 1, т.е. от «Свой»);
уровень 0 «Гость» / отсутствие истории → `null` (чип не рисуется — та же логика «нечего показывать», что и
скрытие self-панели при 0 XP). Батч-вычисление одним запросом (`ReputationRepository.findOutcomesByUsers` →
`XpService.publicLevelNames`), без N+1 по участникам.

## Acceptance Criteria (P1b)

**AC-P1b-1 (счётчики V25):** после recompute `kept_count/broke_count/neutral_count` равны
COUNT-FILTER по kind; `kept+broke+neutral = outcome_count`. Backfill данных не выполняется.

**AC-P1b-2 (Trust новичок):** `outcome_count < 3` → Trust не показывается («Новичок»). При 0 broke и
0 kept формула даёт 85 (prior).

**AC-P1b-3 (decay):** старый `no_show` (age ≫ 90д) вносит экспоненциально меньший вклад, чем свежий —
восстановление поведением поднимает Trust.

**AC-P1b-4 (Global all-history):** покинутый клуб с track record входит в `M` и в Global-число (НЕ
отфильтрован active-only); один плохой клуб не обнуляет Global (cap 0.5 + decay).

**AC-P1b-5 (выход-с-обязательствами):** выход из free-клуба с `confirmed` бронью на не-finalized событии
пишет −200 ДО каскада; pending участие в `active` `affects_reputation` складчине пишет −40 (даже при
прошедшем дедлайне — каскад иначе стёр бы его без штрафа); повторный/двойной выход не задваивает (UNIQUE);
finalized-but-`stage_2` событие выход не штрафует и его бронь не удаляет (исход пишет пайплайн); waitlisted
промоутится на освободившийся слот. Paid-клуб не штрафует немедленно. `leave-preview` отдаёт только счётчики.

**AC-P1b-6 (XP не падает):** негативный исход даёт 0 XP (не минус); уровень не понижается.

**AC-P1b-7 (уровень другим, others-tier — PR-e):** в списке участников и профиле участника `levelName` =
название ГЛОБАЛЬНОГО уровня (не per-club), считается тем же XP, что и self-эндпоинт. Уровень 0 «Гость» / нет
истории → `null` (чип скрыт). Участник-новичок в этом клубе (per-club Trust = «Новичок»/`null`) всё равно
показывает глобальный `levelName`, если он ≥ «Свой». Точное XP/бейджи/константы наружу не отдаются. Батч —
один запрос на список участников (без N+1).

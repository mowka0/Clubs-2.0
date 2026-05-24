# Отметка посещаемости события — потерян UI

**Статус:** open · **Создано:** 2026-05-24 · **Origin:** обнаружено при round-3/4 unified-activity-creation

## Проблема

Отметка посещаемости события (`markAttendance`) сейчас **недоступна из UI**. API-слой и хук существуют (`api/events.ts`, `queries/events.ts` — `useMarkAttendanceMutation`, `types/api.ts`), но ни один компонент их не вызывает.

Раньше UI жил в `EventDetailModal` внутри `EventsTab` на странице управления клубом. В ходе унификации создания активностей (PR feature/unified-activity-creation, round 1) `EventsTab` + `EventDetailModal` были удалены как dead code при переходе на единую ленту «Активности». Функционал отметки посещаемости **не был перенесён** в новое место — то есть выпал.

Подтверждение: `grep -rn "markAttendance" frontend/src --include=*.tsx --include=*.ts` (без тестов) даёт только `api/events.ts`, `queries/events.ts`, `types/api.ts` — ни одного вызова из компонента.

## Влияние

Организатор не может отметить, кто пришёл на событие → не работает:
- начисление/списание репутации за посещение (PRD §4.4.3 — attendance-driven reputation)
- финализация посещаемости (`AttendanceService.finalizeAttendance` ждёт `attendance_marked=true`, который теперь никто не выставляет)

Это регресс относительно состояния до унификации.

## Что нужно

Вернуть UI отметки посещаемости в новом месте. Кандидаты:
- На детальной странице события (`EventPage`) — блок «Отметить посещаемость» виден только организатору клуба, для прошедших/идущих событий. Самый логичный дом (контекст события уже есть).
- Список участников события (кто проголосовал going/confirmed) с чекбоксами «пришёл», submit → `markAttendance`.
- Учесть dispute-окно (48h) и статусы из `docs/modules/events.md` / `attendance` спеки.

## Ссылки
- `docs/modules/events.md` — lifecycle событий, attendance-флоу
- PRD-Clubs.md §4.4.3 — attendance push (12h), dispute window (48h), reputation recalc
- `backend/.../event/AttendanceService.kt` — серверная логика (работает, ждёт UI-триггера)

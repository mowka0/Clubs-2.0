/**
 * L2 «уровни качества» — щедрые ДИСКРЕТНЫЕ ступени (0..4 закрашенных сектора кольца) для каждой оси.
 * Это КОСМЕТИКА (соц-пруф), не точный балл и не скрытый L3-ранг: пороги намеренно щедрые и легко
 * настраиваются. Центр кольца всё равно показывает distinct-абсолют (анти-фарм §3) — уровень лишь
 * красит сектора. Дизайн-контракт: docs/backlog/club-quality-gamification.md §1–2, §11.
 */

// Максимум уровня любой оси: 4 = все сектора кольца закрашены.
export const MAX_LEVEL = 4;

/** Сплочённость: размер ядра (distinct ≥3 явки). «формируется» → «есть ядро» → «сильное ядро» 20+. */
export function cohesionLevel(coreSize: number): number {
  if (coreSize <= 0) return 0;
  if (coreSize < 4) return 1;
  if (coreSize < 8) return 2;
  if (coreSize < 20) return 3;
  return MAX_LEVEL;
}

/** Активность: встреч в месяц, насыщающаяся кривая (Зерно→Росток→Регулярный→Постоянный). */
export function activityLevel(meetingsPerMonth: number): number {
  if (meetingsPerMonth <= 0) return 0;
  if (meetingsPerMonth < 2) return 1;
  if (meetingsPerMonth < 4) return 2;
  if (meetingsPerMonth < 8) return 3;
  return MAX_LEVEL;
}

/** Приходит: доля среднего прихода от активных участников (avgAttendance / memberCount). */
export function attendanceLevel(avgAttendance: number, memberCount: number): number {
  if (avgAttendance <= 0 || memberCount <= 0) return 0;
  const ratio = avgAttendance / memberCount;
  if (ratio < 0.2) return 1;
  if (ratio < 0.4) return 2;
  if (ratio < 0.6) return 3;
  return MAX_LEVEL;
}

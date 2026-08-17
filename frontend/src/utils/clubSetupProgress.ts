/**
 * Прогресс мастера наполнения клуба (`ClubSetupWizard`).
 *
 * Живёт в localStorage, а не в URL: человек уходит из приложения посреди заполнения (выбрать
 * фото, спросить у своих про город) и возвращается по-новому — URL при этом пустой. Знание
 * общее у двух экранов: мастер продолжает с сохранённого шага, страница клуба по нему решает,
 * писать «Заполнить клуб» или «Продолжить заполнение».
 *
 * Отдельный модуль, а не экспорт из самого мастера: `ClubSetupWizard` грузится лениво, и импорт
 * из него утянул бы весь мастер в чанк страницы клуба.
 */

/** Сколько шагов в мастере: название → город → описание с темами → обложка. */
export const CLUB_SETUP_TOTAL_STEPS = 4;

function progressKey(clubId: string): string {
  return `club-setup-step:${clubId}`;
}

/**
 * Шаг, на котором человек остановился. 1 — мастер ещё не открывали.
 *
 * Недоступный localStorage (приватный режим, вебвью без storage) не должен ронять экраны,
 * которые всего лишь хотят подписать кнопку: без памяти мастер просто начинается сначала.
 */
export function readClubSetupStep(clubId: string): number {
  try {
    const raw = Number(localStorage.getItem(progressKey(clubId)));
    return Number.isInteger(raw) && raw >= 1 && raw <= CLUB_SETUP_TOTAL_STEPS ? raw : 1;
  } catch (_e) {
    return 1;
  }
}

export function saveClubSetupStep(clubId: string, step: number): void {
  try {
    localStorage.setItem(progressKey(clubId), String(step));
  } catch (_e) {
    // Хранилище недоступно — прогресс не переживёт перезапуск, но заполнение работает.
  }
}

/** Мастер пройден — прогресс больше не нужен, иначе кнопка вечно зовёт «продолжить». */
export function clearClubSetupProgress(clubId: string): void {
  try {
    localStorage.removeItem(progressKey(clubId));
  } catch (_e) {
    // См. saveClubSetupStep: нечего стирать — нечему и ломаться.
  }
}

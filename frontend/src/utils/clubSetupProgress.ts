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

/** Шаги мастера: аватар с названием и размером → город → описание с темами → обложка → права бота. */
export const CLUB_SETUP_TOTAL_STEPS = 5;

/**
 * Столько шагов, когда права боту уже выданы: Telegram отдаёт их прямо при добавлении по
 * ссылке `?startgroup=…&admin=…`, и пятый шаг тогда просил бы выданное (правка PO 2026-08-18).
 */
export const CLUB_SETUP_STEPS_WITHOUT_RIGHTS = 4;

function progressKey(clubId: string): string {
  return `club-setup-step:${clubId}`;
}

/**
 * Где человек остановился: шаг и сколько всего шагов было в его мастере.
 *
 * Общее число хранится вместе с шагом, потому что оно непостоянно: последний шаг (права бота)
 * появляется, только если прав не хватает. Знает об этом мастер, а рисует шкалу баннер на
 * странице клуба — он бы врал на один сегмент (баг PO 2026-08-19).
 */
export interface ClubSetupProgress {
  step: number;
  totalSteps: number;
}

/**
 * Прогресс мастера. `step = 1` — мастер ещё не открывали.
 *
 * Недоступный localStorage (приватный режим, вебвью без storage) не должен ронять экраны,
 * которые всего лишь хотят подписать кнопку: без памяти мастер просто начинается сначала.
 */
export function readClubSetupProgress(clubId: string): ClubSetupProgress {
  const fallback: ClubSetupProgress = { step: 1, totalSteps: CLUB_SETUP_TOTAL_STEPS };
  try {
    // Формат «шаг/всего»; голое число — запись прежней версии, у неё шагов было пять.
    const raw = localStorage.getItem(progressKey(clubId));
    if (!raw) return fallback;
    const [rawStep, rawTotal] = raw.split('/');
    const total = Number(rawTotal);
    const totalSteps = Number.isInteger(total) && total >= 1 && total <= CLUB_SETUP_TOTAL_STEPS
      ? total
      : CLUB_SETUP_TOTAL_STEPS;
    const step = Number(rawStep);
    if (!Number.isInteger(step) || step < 1 || step > totalSteps) return fallback;
    return { step, totalSteps };
  } catch (_e) {
    return fallback;
  }
}

/** Шаг, на котором человек остановился, — когда общее число шагов вызывающему не нужно. */
export function readClubSetupStep(clubId: string): number {
  return readClubSetupProgress(clubId).step;
}

export function saveClubSetupStep(clubId: string, step: number, totalSteps: number): void {
  try {
    localStorage.setItem(progressKey(clubId), `${step}/${totalSteps}`);
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

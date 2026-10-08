import foxWelcomeArt from '../../assets/mascot/fox-onb-1.png';
import foxChatArt from '../../assets/mascot/fox-chat.png';
// WebP, а не PNG: дирижёр очищен в полном цвете (дыры от снятия кромки залиты, зерно палитры
// сглажено — docs/design/empty-states/fill-holes.py, denoise.py), и в PNG весил бы 477 КБ.
import foxConductorArt from '../../assets/mascot/fox-onb-3.webp';

/**
 * Тексты интро. Утверждены PO построчно (docs/modules/onboarding.md § «Интро») —
 * менять только вместе со спекой.
 *
 * Формула экрана жёсткая: арт + ОДИН заголовок + ОДНА микро-строка. Списков преимуществ
 * здесь больше нет — они переехали в туры по экранам, где каждая фраза стоит рядом
 * с элементом, про который она. Прежняя версия вываливала на первом же слайде четыре
 * абзаца, и человек упирался в стену текста, ничего ещё не потрогав.
 */

/**
 * Кусок заголовка. Заголовок собран из сегментов, а не из «текст + хвост», потому что
 * акцентная часть стоит не всегда в конце.
 */
export interface TitleSegment {
  text: string;
  /** Акцентная (оранжевая) часть заголовка. */
  accent?: boolean;
}

export interface OnboardingSlideData {
  /** Арт слайда — сцена с лисом-маскотом (import из assets/mascot). */
  artSrc: string;
  title: TitleSegment[];
  /** Одна строка под заголовком. Больше на экран не кладём. */
  micro: string;
}

export const ONBOARDING_SLIDES: readonly OnboardingSlideData[] = [
  {
    // Лис приветственно машет со скамейки — «присаживайся, тут свои».
    artSrc: foxWelcomeArt,
    title: [{ text: 'Преврати чат\nв ' }, { text: 'полноценный клуб', accent: true }, { text: '!' }],
    micro: 'Встречи, поездки, игры — всё, ради чего вы в одном чате',
  },
  {
    // Лис за ноутбуком: клуб и чат — одно рабочее место.
    artSrc: foxChatArt,
    title: [{ text: 'Клуб и чат — ' }, { text: 'одно целое', accent: true }],
    micro: 'В чате болтаем, в клубе — организуем',
  },
  {
    // Лис-дирижёр за пультом — бот держит ритм клуба вместо организатора.
    artSrc: foxConductorArt,
    title: [{ text: 'Бот возьмёт\n' }, { text: 'рутину', accent: true }, { text: ' на себя' }],
    micro: 'Организация встреч, автоматические напоминания, сборы, репутация и т.д.',
  },
];

/**
 * Первый арт интро запрашивается сразу при старте приложения, параллельно с авторизацией: интро
 * рисуется только после неё, и на медленной сети картинка иначе начинала качаться последней — вместе
 * с двумя другими лисами (PO 2026-10-08, «еле-еле грузится»). Повторным запускам её отдаёт кэш.
 */
export function preloadFirstSlideArt(): void {
  const link = document.createElement('link');
  link.rel = 'preload';
  link.as = 'image';
  link.href = foxWelcomeArt;
  link.fetchPriority = 'high';
  document.head.appendChild(link);
}

/** Подпись кнопки на последнем слайде: она же завершает интро и ведёт в профиль. */
export const ONBOARDING_FINAL_CTA = 'Погнали!';

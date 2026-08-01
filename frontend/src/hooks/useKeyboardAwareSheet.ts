import { RefObject, useEffect } from 'react';

/**
 * Запас между шторкой и верхней кромкой видимой области (px). Нужен, чтобы над шторкой
 * оставалась полоска «тапни, чтобы закрыть», даже когда клавиатура съела пол-экрана.
 */
const TOP_GAP_PX = 12;
/**
 * Пауза перед доводкой поля в видимую зону (мс). Клавиатура выезжает анимацией, и замер,
 * сделанный в момент фокуса, отстаёт от реальной раскладки.
 */
const SCROLL_DELAY_MS = 260;

/**
 * Шторка с полями ввода: держит её высоту по ВИДИМОЙ области, а не по высоте окна, и
 * доводит поле в фокусе до видимой зоны.
 *
 * Зачем: на iOS клавиатура не меняет размер окна — она перекрывает его снизу, поэтому
 * `max-height: 88vh` считался от полной высоты экрана, и форма оказывалась подрезанной
 * (баг PO 2026-08-01: «нажимаю на поле — форма обрезается по высоте»). `visualViewport`
 * знает реальную видимую высоту; ей и меряем.
 *
 * Инлайновая высота ставится только на переданный элемент — общий `.rd-sheet` не трогаем,
 * чтобы поведение остальных шторок не поехало заодно.
 */
export function useKeyboardAwareSheet(sheetRef: RefObject<HTMLElement | null>): void {
  useEffect(() => {
    const viewport = window.visualViewport;
    const sheet = sheetRef.current;
    if (viewport === null || viewport === undefined || sheet === null) return;

    const applyHeight = () => {
      sheet.style.maxHeight = `${viewport.height - TOP_GAP_PX}px`;
      // Шторка прибита к низу ОКНА, а видимая область при открытой клавиатуре кончается выше:
      // без подъёма её низ с кнопками «Отмена / Сохранить» уезжает под клавиатуру.
      // Поднимаем через `bottom`, а не `transform`: transform на этой же шторке занят
      // протяжкой-закрытием (useSheetDrag), и два хозяина одного свойства подрались бы.
      // `!important` обязателен: глобальное правило `[role="dialog"] { bottom: 0 !important }`
      // (brand-theme.css, прибивка диалогов к низу) иначе перебьёт инлайновое значение.
      const hiddenBelow = window.innerHeight - viewport.height - viewport.offsetTop;
      sheet.style.setProperty('bottom', hiddenBelow > 0 ? `${hiddenBelow}px` : '0px', 'important');
    };

    applyHeight();
    viewport.addEventListener('resize', applyHeight);
    viewport.addEventListener('scroll', applyHeight);
    return () => {
      viewport.removeEventListener('resize', applyHeight);
      viewport.removeEventListener('scroll', applyHeight);
      sheet.style.maxHeight = '';
      sheet.style.removeProperty('bottom');
    };
  }, [sheetRef]);

  // Поле, в которое человек тыкнул, должно быть видно целиком: клавиатура занимает низ,
  // и без доводки поле остаётся наполовину за её кромкой.
  useEffect(() => {
    const sheet = sheetRef.current;
    if (sheet === null) return;
    const onFocusIn = (e: FocusEvent) => {
      const target = e.target;
      if (!(target instanceof HTMLElement)) return;
      window.setTimeout(() => target.scrollIntoView({ block: 'center', behavior: 'smooth' }), SCROLL_DELAY_MS);
    };
    sheet.addEventListener('focusin', onFocusIn);
    return () => sheet.removeEventListener('focusin', onFocusIn);
  }, [sheetRef]);
}

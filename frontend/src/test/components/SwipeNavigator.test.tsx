import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

import { SwipeNavigator } from '../../components/SwipeNavigator';

const VIEWPORT_WIDTH = 400;

/**
 * Свой конструктор touch-событий: fireEvent не даёт задать timeStamp, а от него
 * зависит расчёт скорости флика — без контроля времени тест ловил бы «мгновенные»
 * жесты с бесконечной скоростью и срабатывал бы там, где не должен.
 */
function dispatchTouch(
  target: Element,
  type: 'touchstart' | 'touchmove' | 'touchend',
  points: Array<{ x: number; y: number }>,
  timeStamp: number,
) {
  const event = new Event(type, { bubbles: true, cancelable: true });
  const touches = points.map((point, index) => ({
    identifier: index,
    clientX: point.x,
    clientY: point.y,
  }));
  Object.defineProperty(event, 'touches', { value: touches });
  Object.defineProperty(event, 'timeStamp', { value: timeStamp });
  target.dispatchEvent(event);
}

/** Медленный жест: скорость заведомо ниже порога флика, решает только дистанция. */
function swipe(target: Element, fromX: number, toX: number, y = 300) {
  dispatchTouch(target, 'touchstart', [{ x: fromX, y }], 0);
  const steps = 4;
  for (let step = 1; step <= steps; step += 1) {
    const x = fromX + ((toX - fromX) * step) / steps;
    dispatchTouch(target, 'touchmove', [{ x, y }], step * 100);
  }
  dispatchTouch(target, 'touchend', [], steps * 100);
}

function renderNavigator(children?: React.ReactNode) {
  return render(
    <MemoryRouter initialEntries={['/first', '/second']} initialIndex={1}>
      <SwipeNavigator>
        <Routes>
          <Route path="/first" element={<div>ПЕРВЫЙ ЭКРАН</div>} />
          <Route path="/second" element={<div>ВТОРОЙ ЭКРАН{children}</div>} />
        </Routes>
      </SwipeNavigator>
    </MemoryRouter>,
  );
}

const host = () => document.querySelector('.rd-swipe-host') as HTMLElement;

/**
 * Позиция в истории, от которой хук решает, есть ли куда идти. happy-dom не хранит
 * state у `replaceState` (всегда null), поэтому подменяем геттер напрямую.
 */
function setHistoryIndex(index: number) {
  Object.defineProperty(window.history, 'state', {
    configurable: true,
    get: () => ({ idx: index }),
  });
}

/** Ждём дольше, чем доигрывание жеста (190 мс), — чтобы «ничего не произошло» было честным. */
const settle = () => new Promise((resolve) => setTimeout(resolve, 320));

beforeEach(() => {
  // Тестовая среда не считает лэйаут: без ширины экрана пороги жеста вырождаются в ноль.
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({
    width: VIEWPORT_WIDTH,
    height: 800,
    top: 0,
    left: 0,
    right: VIEWPORT_WIDTH,
    bottom: 800,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  } as DOMRect);
  setHistoryIndex(1);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('SwipeNavigator', () => {
  it('свайп от левой кромки возвращает на предыдущий экран', async () => {
    renderNavigator();
    expect(screen.getByText('ВТОРОЙ ЭКРАН')).toBeInTheDocument();

    swipe(host(), 8, 260);

    await waitFor(() => expect(screen.getByText('ПЕРВЫЙ ЭКРАН')).toBeInTheDocument());
  });

  it('свайп из середины экрана не считается навигацией', async () => {
    renderNavigator();

    swipe(host(), 200, 380);

    await settle();
    expect(screen.getByText('ВТОРОЙ ЭКРАН')).toBeInTheDocument();
  });

  it('короткий свайп не добирает порог и откатывается', async () => {
    renderNavigator();

    swipe(host(), 8, 70);

    await settle();
    expect(screen.getByText('ВТОРОЙ ЭКРАН')).toBeInTheDocument();
  });

  it('вертикальная прокрутка от кромки не превращается в переход', async () => {
    renderNavigator();

    dispatchTouch(host(), 'touchstart', [{ x: 8, y: 300 }], 0);
    dispatchTouch(host(), 'touchmove', [{ x: 20, y: 200 }], 100);
    dispatchTouch(host(), 'touchmove', [{ x: 30, y: 80 }], 200);
    dispatchTouch(host(), 'touchend', [], 300);

    await settle();
    expect(screen.getByText('ВТОРОЙ ЭКРАН')).toBeInTheDocument();
  });

  it('поверх открытой модалки жест не работает', async () => {
    renderNavigator(
      <div role="dialog" data-testid="lightbox">
        картинка
      </div>,
    );

    swipe(screen.getByTestId('lightbox'), 8, 260);

    await settle();
    expect(screen.getByText(/ВТОРОЙ ЭКРАН/)).toBeInTheDocument();
  });

  it('на первой записи истории свайп назад ничего не делает', async () => {
    setHistoryIndex(0);
    renderNavigator();

    swipe(host(), 8, 260);

    await settle();
    expect(screen.getByText('ВТОРОЙ ЭКРАН')).toBeInTheDocument();
  });
});

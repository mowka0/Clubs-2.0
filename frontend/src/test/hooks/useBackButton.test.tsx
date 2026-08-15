import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

/**
 * Кнопка «назад» на первом экране сеанса.
 *
 * Приложение, открытое кнопкой из чата клуба, Telegram показывает ПОВЕРХ этого чата, а
 * DeepLinkHandler заходит на страницу через `replace` — истории позади нет. `navigate(-1)`
 * там ничего не делает: кнопка выглядит сломанной, хотя человек всего лишь хочет обратно
 * в чат. Вместо холостого хода показываем подсказку «сверните приложение» (та же, что у
 * пилюли «В чат»), потому что свернуть Mini App кодом нельзя.
 *
 * Во всех остальных случаях «назад» обязано остаться обычным переходом по истории.
 */

const state: { startParam: string | undefined; platform: string } = {
  startParam: undefined,
  platform: 'ios',
};

const { navigateMock, backButtonHandlers, showBackButtonMock, hideBackButtonMock } = vi.hoisted(
  () => ({
    navigateMock: vi.fn(),
    backButtonHandlers: [] as Array<() => void>,
    showBackButtonMock: vi.fn(),
    hideBackButtonMock: vi.fn(),
  }),
);

vi.mock('@telegram-apps/sdk-react', () => {
  const available = <T,>(fn: T) => Object.assign(fn as object, { isAvailable: () => true });
  return {
    retrieveLaunchParams: () => ({
      tgWebAppPlatform: state.platform,
      tgWebAppStartParam: state.startParam,
    }),
    mountBackButton: available(vi.fn()),
    unmountBackButton: available(vi.fn()),
    showBackButton: available(showBackButtonMock),
    hideBackButton: available(hideBackButtonMock),
    onBackButtonClick: available((handler: () => void) => {
      backButtonHandlers.push(handler);
      return () => {
        const at = backButtonHandlers.indexOf(handler);
        if (at >= 0) backButtonHandlers.splice(at, 1);
      };
    }),
    hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
    hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
    hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
  };
});

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

import { useBackButton } from '../../hooks/useBackButton';
import { rememberDeepLinkLanding, resetChatOriginForTests } from '../../telegram/chatOrigin';

/** Страница, на которую увела кнопка из чата: закреп встречи ведёт на событие. */
const LANDING = '/events/11111111-2222-3333-4444-555555555555';

/**
 * Индекс записи в истории — единственный источник правды для `canGoBack`.
 * happy-dom не хранит state у `replaceState`, поэтому подменяем геттер (как в
 * useHistoryPosition.test): 0 — первая запись сеанса, 1 — вглубь уже заходили.
 */
function setHistoryIndex(index: number) {
  Object.defineProperty(window.history, 'state', {
    configurable: true,
    get: () => ({ idx: index }),
  });
}

const Probe = ({ visible = true, onExitToChat }: { visible?: boolean; onExitToChat?: () => void }) => {
  useBackButton(visible, onExitToChat);
  return null;
};

function renderProbe(onExitToChat?: () => void, options?: { visible?: boolean; path?: string }) {
  return render(
    <MemoryRouter initialEntries={[options?.path ?? '/']}>
      <Probe visible={options?.visible ?? true} onExitToChat={onExitToChat} />
    </MemoryRouter>,
  );
}

/** Нажатие нативной кнопки Telegram = вызов зарегистрированного обработчика. */
function pressBack() {
  backButtonHandlers.forEach((handler) => handler());
}

beforeEach(() => {
  state.startParam = undefined;
  state.platform = 'ios';
  backButtonHandlers.length = 0;
  setHistoryIndex(0);
  resetChatOriginForTests();
  vi.clearAllMocks();
});

/** Приложение открыто кнопкой из чата клуба: payload `startapp` + страница, куда он привёл. */
function launchedFromChat(startParam = `event_11111111-2222-3333-4444-555555555555`) {
  state.startParam = startParam;
  rememberDeepLinkLanding(LANDING);
}

describe('useBackButton — «назад» упирается в чат клуба', () => {
  it('открыто кнопкой из чата, позади пусто → подсказка вместо холостого перехода', () => {
    launchedFromChat();
    const onExitToChat = vi.fn();
    renderProbe(onExitToChat);

    pressBack();

    expect(onExitToChat).toHaveBeenCalledTimes(1);
    expect(navigateMock).not.toHaveBeenCalled();
  });

  it.each(['club', 'skladchina'])('кнопка %s_… из чата клуба тоже распознаётся', (prefix) => {
    launchedFromChat(`${prefix}_11111111-2222-3333-4444-555555555555`);
    const onExitToChat = vi.fn();
    renderProbe(onExitToChat);

    pressBack();

    expect(onExitToChat).toHaveBeenCalledTimes(1);
    expect(navigateMock).not.toHaveBeenCalled();
  });

  /**
   * Deep link приводит на детальные страницы, а там показан док — обычно кнопку «назад» мы на
   * них прячем (`visible = false`). Спрятанную нажимают мимо приложения: подсказку показать
   * будет нечем, поэтому на странице-источнике кнопку забираем себе.
   */
  it('на странице из чата кнопка показывается, хотя док её обычно прячет', () => {
    launchedFromChat();
    const onExitToChat = vi.fn();
    renderProbe(onExitToChat, { visible: false, path: LANDING });

    expect(showBackButtonMock).toHaveBeenCalled();
    pressBack();

    expect(onExitToChat).toHaveBeenCalledTimes(1);
    expect(navigateMock).not.toHaveBeenCalled();
  });

  it('на других страницах с доком кнопка остаётся спрятанной', () => {
    launchedFromChat();
    const onExitToChat = vi.fn();
    renderProbe(onExitToChat, { visible: false, path: '/profile' });

    expect(showBackButtonMock).not.toHaveBeenCalled();
    expect(hideBackButtonMock).toHaveBeenCalled();
    expect(backButtonHandlers).toHaveLength(0);
  });

  it('человек ушёл вглубь приложения → обычный переход назад по истории', () => {
    launchedFromChat();
    setHistoryIndex(1);
    const onExitToChat = vi.fn();
    renderProbe(onExitToChat);

    pressBack();

    expect(navigateMock).toHaveBeenCalledWith(-1);
    expect(onExitToChat).not.toHaveBeenCalled();
  });

  it('открыто из лички с ботом (startapp нет) → обычный переход назад', () => {
    const onExitToChat = vi.fn();
    renderProbe(onExitToChat);

    pressBack();

    expect(navigateMock).toHaveBeenCalledWith(-1);
    expect(onExitToChat).not.toHaveBeenCalled();
  });

  it('личное приглашение invite_… — это личка, а не чат клуба', () => {
    launchedFromChat('invite_a1b2c3d4e5f60718');
    const onExitToChat = vi.fn();
    renderProbe(onExitToChat);

    pressBack();

    expect(navigateMock).toHaveBeenCalledWith(-1);
    expect(onExitToChat).not.toHaveBeenCalled();
  });

  it('на компьютере подсказки нет: Mini App там отдельное окно рядом с чатом', () => {
    launchedFromChat();
    state.platform = 'tdesktop';
    const onExitToChat = vi.fn();
    renderProbe(onExitToChat);

    pressBack();

    expect(navigateMock).toHaveBeenCalledWith(-1);
    expect(onExitToChat).not.toHaveBeenCalled();
  });

  it('без колбэка хук ведёт себя как раньше — переход назад', () => {
    launchedFromChat();
    renderProbe();

    pressBack();

    expect(navigateMock).toHaveBeenCalledWith(-1);
  });

  it('подписка на нативную кнопку не пересоздаётся на каждый рендер', () => {
    launchedFromChat();
    const onExitToChat = vi.fn();
    const { rerender } = renderProbe(onExitToChat);

    // Колбэк приходит новой стрелкой — если он попадёт в зависимости эффекта,
    // обработчиков станет больше одного и «назад» сработает дважды.
    rerender(
      <MemoryRouter>
        <Probe onExitToChat={() => onExitToChat()} />
      </MemoryRouter>,
    );
    pressBack();

    expect(backButtonHandlers).toHaveLength(1);
    expect(onExitToChat).toHaveBeenCalledTimes(1);
  });
});

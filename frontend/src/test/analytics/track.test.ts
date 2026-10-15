import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const { postTrackMock, tokenRef } = vi.hoisted(() => ({
  postTrackMock: vi.fn(),
  tokenRef: { token: 'jwt' as string | null },
}));

vi.mock('../../api/track', () => ({ postTrack: postTrackMock }));
vi.mock('../../api/apiClient', () => ({
  apiClient: { getToken: () => tokenRef.token },
  ApiError: class ApiError extends Error {
    status: number;
    constructor(status: number, message: string) {
      super(message);
      this.status = status;
    }
  },
}));

import { track, resetTrackingForTests, queuedForTests } from '../../analytics/track';
import { ApiError } from '../../api/apiClient';

/** Очередь и отправка пачек (product-analytics.md § 4, AC-8). */
describe('track — очередь и отправка пачек', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubEnv('VITE_ANALYTICS_DISABLED', 'false');
    resetTrackingForTests();
    postTrackMock.mockReset();
    postTrackMock.mockResolvedValue(undefined);
    tokenRef.token = 'jwt';
  });

  afterEach(() => {
    vi.useRealTimers();
    // Глобальная настройка тестов — журнал выключен (src/test/setup.ts).
    vi.stubEnv('VITE_ANALYTICS_DISABLED', 'true');
  });

  const sentEvents = (call: number) => (postTrackMock.mock.calls[call]?.[0] as { kind: string }[]).map((e) => e.kind);

  it('три события уходят одной пачкой через 3 секунды', async () => {
    track('screen_opened', { screen: 'home' });
    track('screen_opened', { screen: 'club' }, 'club-1');
    track('onboarding_completed');

    await vi.advanceTimersByTimeAsync(2999);
    expect(postTrackMock).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1);
    expect(postTrackMock).toHaveBeenCalledTimes(1);
    expect(postTrackMock).toHaveBeenCalledWith(
      [
        { kind: 'screen_opened', clubId: undefined, props: { screen: 'home' } },
        { kind: 'screen_opened', clubId: 'club-1', props: { screen: 'club' } },
        { kind: 'onboarding_completed', clubId: undefined, props: undefined },
      ],
      false,
    );
  });

  it('двадцать событий уходят сразу, не дожидаясь таймера', async () => {
    for (let i = 0; i < 20; i += 1) track('onboarding_step', { step: i });

    await vi.advanceTimersByTimeAsync(0);
    expect(postTrackMock).toHaveBeenCalledTimes(1);
    expect(sentEvents(0)).toHaveLength(20);
  });

  it('сворачивание приложения отправляет накопленное немедленно и с keepalive', async () => {
    track('screen_opened', { screen: 'profile' });
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));

    await vi.advanceTimersByTimeAsync(0);
    expect(postTrackMock).toHaveBeenCalledTimes(1);
    expect(postTrackMock.mock.calls[0]?.[1]).toBe(true);
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
  });

  it('после сбоя сети пачка уходит ещё раз один раз, второй сбой её выбрасывает; свежие пачки идут раньше повтора', async () => {
    // Пачка с app_opened падает дважды, остальные проходят.
    let failures = 2;
    postTrackMock.mockImplementation((events: { kind: string }[]) =>
      events[0]?.kind === 'app_opened' && failures-- > 0 ? Promise.reject(new TypeError('network')) : Promise.resolve(),
    );
    track('app_opened', { entry: 'none', platform: 'ios' });
    await vi.advanceTimersByTimeAsync(3000);
    expect(postTrackMock).toHaveBeenCalledTimes(1);

    track('screen_opened', { screen: 'home' });
    await vi.advanceTimersByTimeAsync(3000);
    // Новая пачка первой, повтор старой (снова сбой) — за ней.
    expect(postTrackMock).toHaveBeenCalledTimes(3);
    expect(sentEvents(1)).toEqual(['screen_opened']);
    expect(sentEvents(2)).toEqual(['app_opened']);

    track('onboarding_completed');
    await vi.advanceTimersByTimeAsync(3000);
    expect(postTrackMock).toHaveBeenCalledTimes(4);
    expect(sentEvents(3)).toEqual(['onboarding_completed']);
  });

  it('ответ 400 не повторяется и не роняет вызывающего', async () => {
    postTrackMock.mockRejectedValueOnce(new ApiError(400, 'bad'));
    expect(() => track('app_opened', { entry: 'none', platform: 'ios' })).not.toThrow();
    await vi.advanceTimersByTimeAsync(3000);

    track('screen_opened', { screen: 'home' });
    await vi.advanceTimersByTimeAsync(3000);
    expect(postTrackMock).toHaveBeenCalledTimes(2);
    expect(sentEvents(1)).toEqual(['screen_opened']);
  });

  it('без токена ничего не уходит, очередь не длиннее ста событий', async () => {
    tokenRef.token = null;
    for (let i = 0; i < 150; i += 1) track('onboarding_step', { step: i });
    await vi.advanceTimersByTimeAsync(3000);

    expect(postTrackMock).not.toHaveBeenCalled();
    expect(queuedForTests()).toHaveLength(100);
    expect(queuedForTests()[0]?.props).toEqual({ step: 50 });
  });

  it('при VITE_ANALYTICS_DISABLED=true не копит и не шлёт', async () => {
    vi.stubEnv('VITE_ANALYTICS_DISABLED', 'true');
    track('screen_opened', { screen: 'home' });
    await vi.advanceTimersByTimeAsync(3000);

    expect(postTrackMock).not.toHaveBeenCalled();
    expect(queuedForTests()).toHaveLength(0);
  });
});

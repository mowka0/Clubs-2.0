import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));
vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));
vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

import { ClubInterestsPicker } from '../../components/club/ClubInterestsPicker';

const SPORT_CHIPS = ['бег', 'йога', 'футбол'];

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function mockDictionary(chips: string[] = SPORT_CHIPS, suggestions: string[] = []) {
  server.use(
    http.get('*/api/interests/suggest', () => HttpResponse.json(suggestions)),
    // Порядок важен: MSW матчит по первому совпадению, а '/api/interests' поймал бы и suggest.
    http.get('*/api/interests', () => HttpResponse.json(chips)),
  );
}

describe('ClubInterestsPicker — разметка клуба темами', () => {
  it('показывает чипы выбранной полки и добавляет тему по тапу', async () => {
    mockDictionary();
    const onChange = vi.fn();
    renderWithProviders(<ClubInterestsPicker category="sport" value={[]} onChange={onChange} />);

    const chip = await screen.findByRole('button', { name: 'Добавить тему бег' });
    fireEvent.click(chip);

    expect(onChange).toHaveBeenCalledWith(['бег']);
  });

  it('выбранная тема уходит из чипов полки и снимается повторным тапом', async () => {
    mockDictionary();
    const onChange = vi.fn();
    renderWithProviders(<ClubInterestsPicker category="sport" value={['бег']} onChange={onChange} />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Убрать тему бег' })).toBeTruthy());
    // В полке дубля быть не должно — тема уже выбрана и показана отдельной строкой.
    expect(screen.queryByRole('button', { name: 'Добавить тему бег' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Убрать тему бег' }));
    expect(onChange).toHaveBeenCalledWith([]);
  });

  it('на седьмой теме прячет чипы и поиск, оставляя только снятие', async () => {
    mockDictionary();
    const seven = ['бег', 'йога', 'футбол', 'бокс', 'лыжи', 'теннис', 'хоккей'];
    renderWithProviders(<ClubInterestsPicker category="sport" value={seven} onChange={vi.fn()} />);

    expect(await screen.findByText('Максимум 7 тем')).toBeTruthy();
    expect(screen.queryByLabelText('Поиск темы')).toBeNull();
    expect(screen.queryByRole('button', { name: /^Добавить тему/ })).toBeNull();
    // Снять тему по-прежнему можно — иначе из переполненного набора не выбраться.
    expect(screen.getByRole('button', { name: 'Убрать тему бег' })).toBeTruthy();
  });

  it('своя тема добавляется в нормализованном виде', async () => {
    mockDictionary(SPORT_CHIPS, []);
    const onChange = vi.fn();
    renderWithProviders(<ClubInterestsPicker category="sport" value={[]} onChange={onChange} />);

    // Клавиатура телефона капитализирует первое слово — сохраниться должно строчное, с е вместо ё.
    fireEvent.change(screen.getByLabelText('Поиск темы'), { target: { value: 'Скалолёзание' } });

    const addOwn = await screen.findByRole('button', { name: /Добавить «скалолезание»/ });
    fireEvent.click(addOwn);

    expect(onChange).toHaveBeenCalledWith(['скалолезание']);
  });

  it('не предлагает создать свою тему, когда точно такая уже есть в словаре', async () => {
    mockDictionary(SPORT_CHIPS, ['бег']);
    renderWithProviders(<ClubInterestsPicker category="sport" value={[]} onChange={vi.fn()} />);

    fireEvent.change(screen.getByLabelText('Поиск темы'), { target: { value: 'бег' } });

    await waitFor(() => expect(screen.getByRole('button', { name: 'Добавить тему бег' })).toBeTruthy());
    expect(screen.queryByRole('button', { name: /Добавить «бег»/ })).toBeNull();
  });

  it('счётчик показывает, сколько тем выбрано из лимита', async () => {
    mockDictionary();
    renderWithProviders(<ClubInterestsPicker category="sport" value={['бег', 'йога']} onChange={vi.fn()} />);

    expect(await screen.findByText('2/7')).toBeTruthy();
  });
});

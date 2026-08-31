import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { EventTemplateDto } from '../../api/eventTemplates';

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
  mountBackButton: Object.assign(vi.fn(), { isAvailable: () => false }),
  unmountBackButton: vi.fn(),
  showBackButton: Object.assign(vi.fn(), { isAvailable: () => false }),
  hideBackButton: Object.assign(vi.fn(), { isAvailable: () => false }),
  onBackButtonClick: Object.assign(vi.fn(() => vi.fn()), { isAvailable: () => false }),
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));
vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

import { CreateEventPage } from '../../pages/CreateEventPage';

const CLUB_ID = 'club-1';

const TEMPLATE: EventTemplateDto = {
  id: 'tpl-1',
  clubId: CLUB_ID,
  clubName: 'Клуб',
  name: 'Разговорный клуб (вторники)',
  title: 'Разговорный клуб',
  description: 'Говорим по-английски',
  locationText: 'ул. Покровка, 47',
  locationLat: 55.76,
  locationLon: 37.64,
  locationHint: 'Вход со двора',
  participantLimit: 12,
  format: 'max',
  stage2LeadMinutes: 2160,
  photoUrl: null,
  defaultWeekday: 2,
  defaultTime: '19:00:00',
  createdAt: null,
  updatedAt: null,
};

function mockTemplates(templates: EventTemplateDto[]) {
  server.use(
    http.get(`*/api/clubs/${CLUB_ID}/event-templates`, () => HttpResponse.json(templates)),
  );
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderPage(search: string) {
  const user = userEvent.setup();
  const result = renderWithProviders(
    <Routes>
      <Route path="/clubs/:id/events/new" element={<CreateEventPage />} />
      <Route path="/events" element={<div>Активности</div>} />
    </Routes>,
    { routerEntries: [`/clubs/${CLUB_ID}/events/new${search}`] },
  );
  return { ...result, user };
}

/** Форма создания встречи, открытая по шаблону (docs/modules/event-templates.md § 8). */
describe('CreateEventPage — открытие по шаблону', () => {
  it('AC-3 заполняет поля и ничего не блокирует', async () => {
    mockTemplates([TEMPLATE]);
    const { user } = renderPage('?template=tpl-1');

    const title = await screen.findByDisplayValue('Разговорный клуб');
    expect(title).not.toBeDisabled();
    expect(screen.getByDisplayValue('Говорим по-английски')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Вход со двора')).toBeInTheDocument();
    expect(screen.getByText('ул. Покровка, 47')).toBeInTheDocument();
    expect(screen.getByText(/Заполнено по шаблону/)).toBeInTheDocument();

    // Поле правится как обычно — шаблон только подставил значение.
    await user.clear(title);
    await user.type(title, 'Другое название');
    expect(screen.getByDisplayValue('Другое название')).toBeInTheDocument();
  });

  it('AC-4 подставляет ближайший будущий день недели шаблона', async () => {
    mockTemplates([TEMPLATE]);
    renderPage('?template=tpl-1');

    await screen.findByDisplayValue('Разговорный клуб');
    const datetime = document.querySelector<HTMLInputElement>('input[type="datetime-local"]');
    expect(datetime).not.toBeNull();

    // Ровно вторник (ISO 2) и 19:00, строго в будущем — конкретная дата зависит от дня прогона.
    const picked = new Date(datetime!.value);
    expect(((picked.getDay() + 6) % 7) + 1).toBe(2);
    expect(picked.getHours()).toBe(19);
    expect(picked.getMinutes()).toBe(0);
    expect(picked.getTime()).toBeGreaterThan(Date.now());
  });

  it('AC-7 шаблон «сколько придёт» прячет степпер лимита и блок набора', async () => {
    mockTemplates([{ ...TEMPLATE, format: 'any', participantLimit: null, stage2LeadMinutes: null }]);
    renderPage('?template=tpl-1');

    await screen.findByDisplayValue('Разговорный клуб');
    expect(screen.getByRole('heading', { name: 'Сколько придёт' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Сколько всего мест')).toBeNull();
    expect(screen.queryByText('Набор состава')).toBeNull();
  });

  it('AC-7 шаблон «минимума» подписывает лимит и правило по-своему', async () => {
    mockTemplates([{ ...TEMPLATE, format: 'min', participantLimit: 6 }]);
    renderPage('?template=tpl-1');

    await screen.findByDisplayValue('Разговорный клуб');
    expect(screen.getByRole('heading', { name: 'Минимум участников' })).toBeInTheDocument();
    expect(screen.getByText(/Собираемся, если будет минимум 6 человек/)).toBeInTheDocument();
  });

  it('удалённый шаблон не роняет форму, а честно об этом сообщает', async () => {
    mockTemplates([]);
    renderPage('?template=tpl-1');

    expect(await screen.findByText(/Шаблон не найден/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Максимум участников' })).toBeInTheDocument();
  });

  it('без ?template форма открывается пустой и запрос шаблонов не уходит', async () => {
    const requested = vi.fn();
    server.use(
      http.get(`*/api/clubs/${CLUB_ID}/event-templates`, () => {
        requested();
        return HttpResponse.json([]);
      }),
    );
    renderPage('');

    expect(await screen.findByRole('heading', { name: 'Максимум участников' })).toBeInTheDocument();
    expect(screen.queryByText(/Заполнено по шаблону/)).toBeNull();
    await waitFor(() => expect(requested).not.toHaveBeenCalled());
  });

  it('«Сохранить как шаблон» превращается в «Обновить шаблон» при входе по шаблону', async () => {
    mockTemplates([TEMPLATE]);
    renderPage('?template=tpl-1');

    await screen.findByDisplayValue('Разговорный клуб');
    expect(screen.getByText('Обновить шаблон «Разговорный клуб (вторники)»')).toBeInTheDocument();
    expect(screen.queryByText('Сохранить как шаблон')).toBeNull();
  });

  it('галочка «Сохранить как шаблон» раскрывает поле имени', async () => {
    mockTemplates([]);
    const { user } = renderPage('');

    await screen.findByRole('heading', { name: 'Максимум участников' });
    expect(screen.getByText('Сохранить как шаблон')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText(/Разговорный клуб \(вторники\)/)).toBeNull();

    await user.click(screen.getByRole('checkbox'));

    expect(screen.getByPlaceholderText(/Разговорный клуб \(вторники\)/)).toBeInTheDocument();
  });
});

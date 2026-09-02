import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import { toDatetimeLocalValue } from '../../utils/formatters';
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
const HOUR = 3_600_000;

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
  minParticipants: null,
  format: 'normal',
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

/** Перехваченные тела POST: встреча и шаблон. */
function mockCreate() {
  const events: Record<string, unknown>[] = [];
  const templates: Record<string, unknown>[] = [];
  server.use(
    http.post(`*/api/clubs/${CLUB_ID}/events`, async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      events.push(body);
      return HttpResponse.json({ id: 'evt-new', ...body }, { status: 201 });
    }),
    http.post(`*/api/clubs/${CLUB_ID}/event-templates`, async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      templates.push(body);
      return HttpResponse.json({ ...TEMPLATE, id: 'tpl-new', ...body }, { status: 201 });
    }),
  );
  return { events, templates };
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

const minSwitch = () => screen.getByRole('switch', { name: 'Минимум участников' });
const minInput = () => screen.getByRole('textbox', { name: 'Значение минимума' });
const maxInput = () => screen.getByRole('textbox', { name: 'Максимум участников' });

function setDatetime(msFromNow: number) {
  const input = document.querySelector<HTMLInputElement>('input[type="datetime-local"]');
  fireEvent.change(input!, { target: { value: toDatetimeLocalValue(new Date(Date.now() + msFromNow)) } });
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

  it('AC-7 шаблон открытой встречи прячет степперы мест и блок набора', async () => {
    mockTemplates([{ ...TEMPLATE, format: 'open', participantLimit: null, stage2LeadMinutes: null }]);
    renderPage('?template=tpl-1');

    await screen.findByDisplayValue('Разговорный клуб');
    expect(screen.getByRole('heading', { name: 'Открытая встреча' })).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Максимум участников' })).toBeNull();
    expect(screen.queryByRole('switch', { name: 'Минимум участников' })).toBeNull();
    expect(screen.queryByText('Набор состава')).toBeNull();
  });

  it('AC-14 шаблон с минимумом включает переключатель и подставляет число', async () => {
    mockTemplates([{ ...TEMPLATE, participantLimit: 12, minParticipants: 6 }]);
    renderPage('?template=tpl-1');

    await screen.findByDisplayValue('Разговорный клуб');
    expect(screen.getByRole('heading', { name: 'Обычная встреча' })).toBeInTheDocument();
    expect(minSwitch()).toHaveAttribute('aria-checked', 'true');
    expect(minInput()).toHaveValue('6');
    expect(screen.getByText(/Собираемся, если будет минимум 6\./)).toBeInTheDocument();
  });

  it('удалённый шаблон не роняет форму, а честно об этом сообщает', async () => {
    mockTemplates([]);
    renderPage('?template=tpl-1');

    expect(await screen.findByText(/Шаблон не найден/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Обычная встреча' })).toBeInTheDocument();
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

    expect(await screen.findByRole('heading', { name: 'Обычная встреча' })).toBeInTheDocument();
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

    await screen.findByRole('heading', { name: 'Обычная встреча' });
    expect(screen.getByText('Сохранить как шаблон')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText(/Разговорный клуб \(вторники\)/)).toBeNull();

    await user.click(screen.getByRole('checkbox'));

    expect(screen.getByPlaceholderText(/Разговорный клуб \(вторники\)/)).toBeInTheDocument();
  });
});

/** Максимум и минимум обычной встречи (docs/modules/event-formats.md § 9.2). */
describe('CreateEventPage — максимум и минимум участников', () => {
  it('открытая встреча по ?format=open — без мест и без набора', async () => {
    renderPage('?format=open');

    expect(await screen.findByRole('heading', { name: 'Открытая встреча' })).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Максимум участников' })).toBeNull();
    expect(screen.queryByText('Набор состава')).toBeNull();
  });

  it('минимум по умолчанию выключен, максимум со своей подписью', async () => {
    renderPage('');

    await screen.findByRole('heading', { name: 'Обычная встреча' });
    expect(maxInput()).toHaveValue('20');
    expect(screen.getByText('Мест 20. Кто не успел — встанет в очередь на замену')).toBeInTheDocument();
    expect(minSwitch()).toHaveAttribute('aria-checked', 'false');
    expect(minSwitch()).not.toBeDisabled();
    expect(screen.queryByRole('textbox', { name: 'Значение минимума' })).toBeNull();
  });

  it('включение минимума даёт 2 и называет правило', async () => {
    const { user } = renderPage('');

    await screen.findByRole('heading', { name: 'Обычная встреча' });
    await user.click(minSwitch());

    expect(minSwitch()).toHaveAttribute('aria-checked', 'true');
    expect(minInput()).toHaveValue('2');
    expect(screen.getByText(
      'Собираемся, если будет минимум 2. Не наберём к закрытию набора — встреча отменится',
    )).toBeInTheDocument();
  });

  it('снижение максимума ниже минимума подтягивает минимум к максимуму', async () => {
    const { user } = renderPage('');

    await screen.findByRole('heading', { name: 'Обычная встреча' });
    await user.click(minSwitch());
    await user.clear(minInput());
    await user.type(minInput(), '8');
    await user.tab();
    expect(minInput()).toHaveValue('8');

    await user.clear(maxInput());
    await user.type(maxInput(), '5');
    await user.tab();

    expect(maxInput()).toHaveValue('5');
    expect(minInput()).toHaveValue('5');
  });

  it('дата ближе интервала набора гасит минимум, и он не уходит в body (AC-11)', async () => {
    const { events } = mockCreate();
    const { user } = renderPage('');

    await screen.findByRole('heading', { name: 'Обычная встреча' });
    await user.click(minSwitch());
    expect(minSwitch()).toHaveAttribute('aria-checked', 'true');

    // Через 2 часа при интервале 18 ч по умолчанию: набор не успеет закрыться.
    setDatetime(2 * HOUR);

    await waitFor(() => expect(minSwitch()).toHaveAttribute('aria-checked', 'false'));
    expect(minSwitch()).toBeDisabled();
    expect(screen.getByText(
      'До встречи меньше 18 часов — набор не успеет закрыться. Оставьте только максимум',
    )).toBeInTheDocument();

    await user.type(screen.getByPlaceholderText('Например: Йога в парке'), 'Баня');
    await user.type(screen.getByPlaceholderText('Вход со двора, домофон 12'), 'у входа');
    await user.click(screen.getByRole('button', { name: 'Создать событие' }));

    await waitFor(() => expect(events).toHaveLength(1));
    expect(events[0]!.format).toBe('normal');
    expect(events[0]!.participantLimit).toBe(20);
    expect(events[0]!.minParticipants).toBeNull();
  });

  it('включённый минимум уходит и во встречу, и в попутно сохранённый шаблон', async () => {
    const { events, templates } = mockCreate();
    const { user } = renderPage('');

    await screen.findByRole('heading', { name: 'Обычная встреча' });
    setDatetime(3 * 24 * HOUR);
    await user.click(minSwitch());
    await user.clear(minInput());
    await user.type(minInput(), '4');
    await user.tab();
    await user.type(screen.getByPlaceholderText('Например: Йога в парке'), 'Баня');
    await user.type(screen.getByPlaceholderText('Вход со двора, домофон 12'), 'у входа');
    await user.click(screen.getByRole('checkbox'));
    await user.type(screen.getByPlaceholderText(/Разговорный клуб \(вторники\)/), 'Баня по средам');
    await user.click(screen.getByRole('button', { name: 'Создать событие' }));

    await waitFor(() => expect(events).toHaveLength(1));
    expect(events[0]!.minParticipants).toBe(4);
    expect(events[0]!.participantLimit).toBe(20);
    await waitFor(() => expect(templates).toHaveLength(1));
    expect(templates[0]!.minParticipants).toBe(4);
    expect(templates[0]!.format).toBe('normal');
  });
});

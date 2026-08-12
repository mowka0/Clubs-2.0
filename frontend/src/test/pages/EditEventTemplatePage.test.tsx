import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
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

import { EditEventTemplatePage } from '../../pages/EditEventTemplatePage';

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
  isOpenEvent: false,
  isUrgentEvent: false,
  stage2LeadMinutes: 2160,
  photoUrl: null,
  defaultWeekday: 2,
  defaultTime: '19:00:00',
  createdAt: null,
  updatedAt: null,
};

/** Перехваченные PUT-тела — проверяем, что уезжает на сервер. */
const puts: unknown[] = [];

function mockTemplates(templates: EventTemplateDto[]) {
  server.use(
    http.get(`*/api/clubs/${CLUB_ID}/event-templates`, () => HttpResponse.json(templates)),
    http.put(`*/api/clubs/${CLUB_ID}/event-templates/:templateId`, async ({ request }) => {
      const body = await request.json();
      puts.push(body);
      return HttpResponse.json({ ...TEMPLATE, ...(body as object) });
    }),
  );
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => { server.resetHandlers(); puts.length = 0; });
afterAll(() => server.close());

function renderPage() {
  const user = userEvent.setup();
  const result = renderWithProviders(
    <Routes>
      <Route path="/clubs/:id/event-templates/:templateId/edit" element={<EditEventTemplatePage />} />
      <Route path="/events" element={<div>Активности</div>} />
    </Routes>,
    { routerEntries: [`/clubs/${CLUB_ID}/event-templates/tpl-1/edit`] },
  );
  return { ...result, user };
}

/**
 * Полная правка шаблона (docs/modules/event-templates.md § 7.3, AC-20..AC-23): та же форма
 * встречи, но на выходе PUT шаблона, а не создание события.
 */
describe('EditEventTemplatePage', () => {
  it('AC-20 открывается той же формой встречи со всеми полями шаблона', async () => {
    mockTemplates([TEMPLATE]);
    renderPage();

    expect(await screen.findByDisplayValue('Разговорный клуб (вторники)')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Разговорный клуб')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Говорим по-английски')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Вход со двора')).toBeInTheDocument();
    // Место с картой — главное, ради чего правку и заводили.
    expect(screen.getByText('ул. Покровка, 47')).toBeInTheDocument();
    // Лимит: степпер подставлен значением шаблона (подпись поля и ariaLabel степпера
    // совпадают, поэтому проверяем именно введённое значение).
    expect(screen.getByDisplayValue('12')).toBeInTheDocument();
  });

  it('AC-20 редактируются ВСЕ поля встречи, включая фото и интервал Этапа 2', async () => {
    mockTemplates([{ ...TEMPLATE, photoUrl: 'https://cdn.test/poster.webp' }]);
    renderPage();

    await screen.findByDisplayValue('Разговорный клуб');
    // Фото: показано и снимается — форма та же, что у создания встречи.
    expect(document.querySelector('img[src="https://cdn.test/poster.webp"]')).not.toBeNull();
    expect(screen.getByText('Заменить')).toBeInTheDocument();
    // Описание, место и интервал Этапа 2 — тоже на месте.
    expect(screen.getByDisplayValue('Говорим по-английски')).toBeInTheDocument();
    expect(screen.getByText('ул. Покровка, 47')).toBeInTheDocument();
    expect(screen.getByText('Подтверждение мест')).toBeInTheDocument();
    // Интервал шаблона (2160 минут) показан словами и собирается из двух текстовых узлов,
    // поэтому матчим по textContent элемента.
    expect(
      screen.getByText((_, el) => el?.tagName === 'B' && el.textContent === 'за 36 часов'),
    ).toBeInTheDocument();
  });

  it('AC-20 снятое фото уезжает в PUT как null', async () => {
    mockTemplates([{ ...TEMPLATE, photoUrl: 'https://cdn.test/poster.webp' }]);
    const { user } = renderPage();

    await screen.findByDisplayValue('Разговорный клуб');
    // «Убрать» есть и у фото, и у выбранного места — берём кнопку из блока фото.
    const photoField = screen.getByText('Фото (опц.)').closest('.rd-field')!;
    await user.click(within(photoField as HTMLElement).getByText('Убрать'));
    await user.click(screen.getByText('Сохранить шаблон'));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect((puts[0] as Record<string, unknown>).photoUrl).toBeNull();
  });

  it('AC-21 вместо даты — расписание повторов, поля даты нет', async () => {
    mockTemplates([TEMPLATE]);
    renderPage();

    await screen.findByDisplayValue('Разговорный клуб');
    expect(document.querySelector('input[type="datetime-local"]')).toBeNull();
    // Вторник (ISO 2) отмечен, время подставлено.
    expect(screen.getByRole('button', { name: 'Вт' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByLabelText('Время начала')).toHaveValue('19:00');
  });

  it('AC-22 сохранение шлёт PUT со всем содержимым и НЕ создаёт встречу', async () => {
    mockTemplates([TEMPLATE]);
    const { user } = renderPage();

    const title = await screen.findByDisplayValue('Разговорный клуб');
    await user.clear(title);
    await user.type(title, 'Разговорный клуб (новички)');
    await user.click(screen.getByRole('button', { name: 'Чт' }));
    await user.click(screen.getByText('Сохранить шаблон'));

    await waitFor(() => expect(puts).toHaveLength(1));
    const body = puts[0] as Record<string, unknown>;
    expect(body.title).toBe('Разговорный клуб (новички)');
    expect(body.name).toBe('Разговорный клуб (вторники)');
    // PUT — полная замена: содержимое уезжает целиком, а не одним изменённым полем.
    expect(body.locationHint).toBe('Вход со двора');
    expect(body.participantLimit).toBe(12);
    expect(body.stage2LeadMinutes).toBe(2160);
    expect(body.defaultWeekday).toBe(4);
    expect(body.defaultTime).toBe('19:00:00');
  });

  it('AC-23 повторный тап по дню снимает его — дата станет пустой при создании', async () => {
    mockTemplates([TEMPLATE]);
    const { user } = renderPage();

    await screen.findByDisplayValue('Разговорный клуб');
    await user.click(screen.getByRole('button', { name: 'Вт' }));
    expect(screen.getByRole('button', { name: 'Вт' })).toHaveAttribute('aria-pressed', 'false');

    await user.click(screen.getByText('Сохранить шаблон'));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect((puts[0] as Record<string, unknown>).defaultWeekday).toBeNull();
  });

  it('пустое имя шаблона не сохраняется', async () => {
    mockTemplates([TEMPLATE]);
    const { user } = renderPage();

    const name = await screen.findByDisplayValue('Разговорный клуб (вторники)');
    await user.clear(name);
    await user.click(screen.getByText('Сохранить шаблон'));

    expect(screen.getByText('Укажите имя шаблона')).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  it('формат встречи в правке не меняется — переключателя нет', async () => {
    mockTemplates([TEMPLATE]);
    renderPage();

    await screen.findByDisplayValue('Разговорный клуб');
    expect(screen.queryByText('Сделать срочной')).toBeNull();
    expect(screen.getByText(/Формат встречи/)).toBeInTheDocument();
  });

  it('удалённый шаблон не открывает пустую форму', async () => {
    mockTemplates([]);
    renderPage();

    expect(await screen.findByText(/Шаблон не найден/)).toBeInTheDocument();
    expect(screen.queryByText('Сохранить шаблон')).toBeNull();
  });
});

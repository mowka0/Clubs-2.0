import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));

// Флоу подтягивает шаблоны встреч при открытии — мокаем весь модуль API, чтобы шаги
// без шаблонов оставались детерминированными, а не зависели от сетевой ошибки.
vi.mock('../../api/eventTemplates', () => ({
  getMyEventTemplates: vi.fn(),
  getClubEventTemplates: vi.fn(),
  createEventTemplate: vi.fn(),
  updateEventTemplate: vi.fn(),
  deleteEventTemplate: vi.fn(),
}));

import { CreateActivityFlow } from '../../components/manage/CreateActivityFlow';
import type { ClubPickerOption } from '../../components/manage/ClubPickerModal';
import { renderWithProviders } from '../utils/renderWithProviders';
import {
  deleteEventTemplate,
  getMyEventTemplates,
  updateEventTemplate,
} from '../../api/eventTemplates';
import type { EventTemplateDto } from '../../api/eventTemplates';

const ONE_CLUB: ClubPickerOption[] = [
  { id: 'club-1', name: 'Alpha Club', avatarUrl: null, category: 'sport' },
];
const TWO_CLUBS: ClubPickerOption[] = [
  { id: 'club-1', name: 'Alpha Club', avatarUrl: null, category: 'sport' },
  { id: 'club-2', name: 'Beta Club', avatarUrl: null, category: 'food' },
];

const TEMPLATE: EventTemplateDto = {
  id: 'tpl-1',
  clubId: 'club-2',
  clubName: 'Beta Club',
  name: 'Разговорный клуб',
  title: 'Разговорный клуб',
  description: null,
  locationText: 'ул. Покровка, 47',
  locationLat: 55.76,
  locationLon: 37.64,
  locationHint: null,
  participantLimit: 12,
  isOpenEvent: false,
  isUrgentEvent: false,
  stage2LeadMinutes: null,
  photoUrl: null,
  defaultWeekday: 2,
  defaultTime: '19:00:00',
  createdAt: null,
  updatedAt: null,
};

beforeEach(() => {
  vi.mocked(getMyEventTemplates).mockResolvedValue([]);
  vi.mocked(updateEventTemplate).mockReset();
  vi.mocked(deleteEventTemplate).mockReset();
});

const LocationProbe = () => {
  const loc = useLocation();
  return (
    <>
      <div data-testid="location">{loc.pathname}</div>
      <div data-testid="location-search">{loc.search}</div>
    </>
  );
};

function renderFlow(clubs: ClubPickerOption[], presetClubId?: string) {
  const user = userEvent.setup();
  const result = renderWithProviders(
    <Routes>
      <Route
        path="/"
        element={
          <>
            <CreateActivityFlow
              open
              canCreate
              organizerClubs={clubs}
              presetClubId={presetClubId ?? null}
              onClose={vi.fn()}
            />
            <LocationProbe />
          </>
        }
      />
      <Route path="/clubs/:id/events/new" element={<LocationProbe />} />
      <Route path="/clubs/:id/skladchina/new" element={<LocationProbe />} />
      <Route path="/clubs/:id/skladchina/split" element={<LocationProbe />} />
      <Route path="/feedback" element={<LocationProbe />} />
    </Routes>,
    { routerEntries: ['/'] },
  );
  return { ...result, user };
}

describe('CreateActivityFlow', () => {
  it('auto-selects the single organizer club and navigates straight to the create route', async () => {
    const { user } = renderFlow(ONE_CLUB);

    // Step 1: type picker is shown; the club picker is not.
    expect(screen.getByText('Событие')).toBeInTheDocument();
    expect(screen.queryByText('Alpha Club')).toBeNull();

    await user.click(screen.getByText('Событие'));

    // «Событие» разветвляется на шаг формата (с местами / открытая встреча, PO 2026-07-21).
    // Заголовков у шагов больше нет (PO 2026-08-11) — шаг опознаём по его пунктам.
    expect(screen.getByText('Открытая встреча')).toBeInTheDocument();
    await user.click(screen.getByText('С местами'));

    // No club-selection step — straight to the per-club create route.
    expect(screen.getByTestId('location').textContent).toBe('/clubs/club-1/events/new');
  });

  it('Событие → «Открытая встреча» ведёт на форму с ?format=open', async () => {
    const { user } = renderFlow(ONE_CLUB);

    await user.click(screen.getByText('Событие'));
    await user.click(screen.getByText('Открытая встреча'));

    // Маршрут тот же, формат передаётся query-параметром — CreateEventPage прячет степпер лимита.
    expect(screen.getByTestId('location').textContent).toBe('/clubs/club-1/events/new');
    expect(screen.getByTestId('location-search').textContent).toBe('?format=open');
  });

  it('Событие → «Срочная встреча» ведёт на форму с ?format=urgent', async () => {
    const { user } = renderFlow(ONE_CLUB);

    await user.click(screen.getByText('Событие'));
    await user.click(screen.getByText('Срочная встреча'));

    // Тот же маршрут: форма читает ?format=urgent — без интервала Этапа 2, событие сразу в stage_2.
    expect(screen.getByTestId('location').textContent).toBe('/clubs/club-1/events/new');
    expect(screen.getByTestId('location-search').textContent).toBe('?format=urgent');
  });

  it('Сбор → template step → club picker → custom create route', async () => {
    const { user } = renderFlow(TWO_CLUBS);

    await user.click(screen.getByText('Сбор'));

    // New step: pick the skladchina template before the club.
    expect(screen.getByText('Разделить счёт')).toBeInTheDocument();
    await user.click(screen.getByText('Свой сбор'));

    // Then the club picker appears with all organizer clubs.
    expect(screen.getByText('Alpha Club')).toBeInTheDocument();
    await user.click(screen.getByText('Beta Club'));

    expect(screen.getByTestId('location').textContent).toBe('/clubs/club-2/skladchina/new');
  });

  it('Сбор → «Разделить счёт» routes to the split-bill page', async () => {
    const { user } = renderFlow(TWO_CLUBS);

    await user.click(screen.getByText('Сбор'));
    await user.click(screen.getByText('Разделить счёт'));
    await user.click(screen.getByText('Beta Club'));

    expect(screen.getByTestId('location').textContent).toBe('/clubs/club-2/skladchina/split');
  });

  it('«Сообщить о проблеме» ведёт на форму обратной связи, минуя выбор клуба', async () => {
    const { user } = renderFlow(TWO_CLUBS);

    await user.click(screen.getByText('Сообщить о проблеме'));

    expect(screen.getByTestId('location').textContent).toBe('/feedback');
  });

  describe('шаблоны встреч', () => {
    it('AC-1 без шаблонов пункт «Готовые шаблоны» не показывается', async () => {
      const { user } = renderFlow(TWO_CLUBS);

      await user.click(screen.getByText('Событие'));

      expect(screen.getByText('С местами')).toBeInTheDocument();
      expect(screen.queryByText(/Готовые шаблоны/)).toBeNull();
    });

    it('AC-3 шаблон ведёт прямо на форму своего клуба, минуя выбор формата и клуба', async () => {
      vi.mocked(getMyEventTemplates).mockResolvedValue([TEMPLATE]);
      const { user } = renderFlow(TWO_CLUBS);

      await user.click(screen.getByText('Событие'));
      await user.click(await screen.findByText('Готовые шаблоны · 1'));
      await user.click(screen.getByText('Разговорный клуб'));

      // Клуб взят из шаблона (club-2), хотя организатор ведёт два клуба и пикер не показывался.
      expect(screen.getByTestId('location').textContent).toBe('/clubs/club-2/events/new');
      expect(screen.getByTestId('location-search').textContent).toBe('?template=tpl-1');
    });

    it('строка шаблона показывает клуб, формат и расписание словами, без эмодзи', async () => {
      vi.mocked(getMyEventTemplates).mockResolvedValue([TEMPLATE]);
      const { user } = renderFlow(TWO_CLUBS);

      await user.click(screen.getByText('Событие'));
      await user.click(await screen.findByText('Готовые шаблоны · 1'));

      // Цветной 🎟 в приглушённой строке метаданных рисовался платформенным шрифтом и выбивался
      // из строки — формат подписывается словом (правка PO 2026-08-11).
      expect(screen.getByText('Beta Club · 12 мест · вт 19:00')).toBeInTheDocument();
    });

    it('формат открытой и срочной встречи тоже подписан словом', async () => {
      vi.mocked(getMyEventTemplates).mockResolvedValue([
        { ...TEMPLATE, id: 'tpl-open', name: 'Пробежка', isOpenEvent: true, participantLimit: null },
        { ...TEMPLATE, id: 'tpl-urgent', name: 'Забег', isUrgentEvent: true },
      ]);
      const { user } = renderFlow(TWO_CLUBS);

      await user.click(screen.getByText('Событие'));
      await user.click(await screen.findByText('Готовые шаблоны · 2'));

      expect(screen.getByText('Beta Club · без лимита · вт 19:00')).toBeInTheDocument();
      expect(screen.getByText('Beta Club · срочная · вт 19:00')).toBeInTheDocument();
    });

    it('на странице клуба показываются шаблоны только этого клуба', async () => {
      vi.mocked(getMyEventTemplates).mockResolvedValue([
        TEMPLATE,
        { ...TEMPLATE, id: 'tpl-2', clubId: 'club-1', clubName: 'Alpha Club', name: 'Чужой' },
      ]);
      const { user } = renderFlow(TWO_CLUBS, 'club-2');

      await user.click(screen.getByText('Событие'));
      await user.click(await screen.findByText('Готовые шаблоны · 1'));

      expect(screen.getByText('Разговорный клуб')).toBeInTheDocument();
      expect(screen.queryByText('Чужой')).toBeNull();
    });

    it('режим правки переименовывает шаблон через PUT', async () => {
      vi.mocked(getMyEventTemplates).mockResolvedValue([TEMPLATE]);
      vi.mocked(updateEventTemplate).mockResolvedValue({ ...TEMPLATE, name: 'Вторники' });
      const { user } = renderFlow(TWO_CLUBS);

      await user.click(screen.getByText('Событие'));
      await user.click(await screen.findByText('Готовые шаблоны · 1'));
      await user.click(screen.getByText('Изменить'));
      await user.click(screen.getByLabelText('Переименовать Разговорный клуб'));

      const input = screen.getByDisplayValue('Разговорный клуб');
      await user.clear(input);
      await user.type(input, 'Вторники');
      await user.click(screen.getByText('Сохранить'));

      await waitFor(() => expect(updateEventTemplate).toHaveBeenCalledTimes(1));
      const [clubId, templateId, body] = vi.mocked(updateEventTemplate).mock.calls[0]!;
      expect(clubId).toBe('club-2');
      expect(templateId).toBe('tpl-1');
      expect(body.name).toBe('Вторники');
      // Содержимое уезжает целиком — PUT это полная замена, а не частичная правка.
      expect(body.title).toBe('Разговорный клуб');
      expect(body.participantLimit).toBe(12);
    });

    it('удалили последний шаблон — шаг не пустеет молча', async () => {
      vi.mocked(getMyEventTemplates).mockResolvedValue([]);
      const { user } = renderFlow(TWO_CLUBS);

      await user.click(screen.getByText('Событие'));
      // Пункта нет вовсе при нуле шаблонов, поэтому пустое состояние проверяем на самом шаге:
      // до него можно доехать, если шаблоны удалили, не выходя из списка.
      expect(screen.queryByText(/Готовые шаблоны/)).toBeNull();
    });

    it('удаление требует подтверждения прямо в строке', async () => {
      vi.mocked(getMyEventTemplates).mockResolvedValue([TEMPLATE]);
      vi.mocked(deleteEventTemplate).mockResolvedValue(undefined);
      const { user } = renderFlow(TWO_CLUBS);

      await user.click(screen.getByText('Событие'));
      await user.click(await screen.findByText('Готовые шаблоны · 1'));
      await user.click(screen.getByText('Изменить'));
      await user.click(screen.getByLabelText('Удалить Разговорный клуб'));

      // Первый тап только раскрывает подтверждение — запроса ещё нет.
      expect(deleteEventTemplate).not.toHaveBeenCalled();
      expect(screen.getByText('Удалить «Разговорный клуб»?')).toBeInTheDocument();

      await user.click(screen.getByText('Удалить'));

      await waitFor(() => expect(deleteEventTemplate).toHaveBeenCalledWith('club-2', 'tpl-1'));
    });
  });

  describe('навигация назад по шагам', () => {
    it('на первом шаге кнопки «Назад» нет', () => {
      renderFlow(TWO_CLUBS);

      expect(screen.getByText('Событие')).toBeInTheDocument();
      expect(screen.queryByText('Назад')).toBeNull();
    });

    it('с шага формата возвращает к выбору типа', async () => {
      const { user } = renderFlow(TWO_CLUBS);

      await user.click(screen.getByText('Событие'));
      expect(screen.getByText('С местами')).toBeInTheDocument();

      await user.click(screen.getByText('Назад'));

      // Вернулись к выбору типа: снова видны «Сбор» и «Сообщить о проблеме».
      expect(screen.getByText('Сбор')).toBeInTheDocument();
      expect(screen.getByText('Сообщить о проблеме')).toBeInTheDocument();
    });

    it('со списка шаблонов возвращает к формату, а не закрывает шит', async () => {
      vi.mocked(getMyEventTemplates).mockResolvedValue([TEMPLATE]);
      const { user } = renderFlow(TWO_CLUBS);

      await user.click(screen.getByText('Событие'));
      await user.click(await screen.findByText('Готовые шаблоны · 1'));
      expect(screen.getByText('Разговорный клуб')).toBeInTheDocument();

      await user.click(screen.getByText('Назад'));

      expect(screen.getByText('С местами')).toBeInTheDocument();
      // Никуда не ушли — форма создания не открывалась.
      expect(screen.getByTestId('location').textContent).toBe('/');
    });

    it('с переименования возвращает к списку шаблонов', async () => {
      vi.mocked(getMyEventTemplates).mockResolvedValue([TEMPLATE]);
      const { user } = renderFlow(TWO_CLUBS);

      await user.click(screen.getByText('Событие'));
      await user.click(await screen.findByText('Готовые шаблоны · 1'));
      await user.click(screen.getByText('Изменить'));
      await user.click(screen.getByLabelText('Переименовать Разговорный клуб'));
      expect(screen.getByText('Название шаблона')).toBeInTheDocument();
      expect(screen.getByDisplayValue('Разговорный клуб')).toBeInTheDocument();

      await user.click(screen.getByText('Назад'));

      // Снова список шаблонов: видна строка шаблона и переключатель режима правки.
      expect(screen.getByText('Разговорный клуб')).toBeInTheDocument();
      expect(screen.getByText('Изменить')).toBeInTheDocument();
      expect(updateEventTemplate).not.toHaveBeenCalled();
    });

    it('с выбора клуба возвращает на тот шаг, откуда пришли', async () => {
      const { user } = renderFlow(TWO_CLUBS);

      // Через «Сбор» → «Свой сбор» → клуб: назад должно вернуть к типам сбора, а не к формату события.
      await user.click(screen.getByText('Сбор'));
      await user.click(screen.getByText('Свой сбор'));
      expect(screen.getByText('Alpha Club')).toBeInTheDocument();

      await user.click(screen.getByText('Назад'));

      // Вернулись к типам сбора, а не к форматам события.
      expect(screen.getByText('Разделить счёт')).toBeInTheDocument();
      expect(screen.queryByText('С местами')).toBeNull();
    });
  });
});

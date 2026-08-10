import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { GeoPoint, PickerMap, PlaceSuggestion } from '../../utils/yandexMaps';

// Хаптика в jsdom недоступна — мокаем хук целиком.
vi.mock('../../hooks/useHaptic', () => ({
  useHaptic: () => ({ impact: vi.fn(), notify: vi.fn(), select: vi.fn() }),
}));

// Реальная карта не поднимется, а сеть в тестах закрыта: стабим ровно четыре внешние
// функции модуля (создание карты + три гео-запроса). Остальное — URL-билдеры, разбор
// ответов, GeocoderHttpError — остаётся настоящим.
// vi.mock хойстится в начало файла, поэтому мок-функции создаются через vi.hoisted.
const maps = vi.hoisted(() => ({
  createPickerMap: vi.fn(),
  suggestPlaces: vi.fn(),
  resolveSuggestion: vi.fn(),
  reverseGeocode: vi.fn(),
}));

vi.mock('../../utils/yandexMaps', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../utils/yandexMaps')>();
  return { ...actual, ...maps };
});

import { LocationPickerSheet } from '../../components/event/LocationPickerSheet';
import { DEFAULT_CENTER, GeocoderHttpError } from '../../utils/yandexMaps';

// Клуб встречи: по нему бэкенд сужает выдачу подсказок до города клуба.
const CLUB_ID = 'club-42';

// Эталонная пара из спеки: заведение и дом на той же улице — они идут одним списком.
const OLD_FRIENDS: PlaceSuggestion = {
  title: 'Old Friends',
  subtitle: 'Кальян-бар · Иваново, Пограничный переулок, 62',
  address: 'Иваново, Пограничный переулок, 62',
  kind: 'business',
  uri: 'ymapsbm1://org?oid=1024394521',
};
const HOUSE: PlaceSuggestion = {
  title: 'Пограничный переулок, 62',
  subtitle: 'Ивановская область, Россия',
  address: 'Иваново, Пограничный переулок, 62',
  kind: 'house',
  uri: 'ymapsbm1://geo?ll=40.972935,57.012830',
};
// Координаты «Old Friends» (ответ /resolve) и точка, куда пользователь сдвинул пин руками.
const VENUE_POINT: GeoPoint = { lat: 57.01283, lon: 40.972935 };
const MOVED_POINT: GeoPoint = { lat: 57.01301, lon: 40.97155 };

/** location_text, который спека требует для заведения: «<название>, <адрес>». */
const VENUE_TEXT = 'Old Friends, Иваново, Пограничный переулок, 62';

/**
 * Карта-заглушка. panTo двигает центр сразу — реальная приходит в ту же точку после
 * анимации, а именно от равенства центра выбранной точке зависит, сохранится ли на
 * «Готово» название заведения. moveTo имитирует ручной сдвиг карты пальцем.
 */
function fakePickerMap(start: GeoPoint): PickerMap & { moveTo: (point: GeoPoint) => void } {
  let center = start;
  return {
    getCenter: () => center,
    panTo: (point) => { center = point; },
    destroy: () => {},
    moveTo: (point) => { center = point; },
  };
}

/** Шторка подсказок: роли у неё нет, поэтому опознаём по aria-label. */
function suggestionsSheet(): HTMLElement | null {
  return screen.queryByLabelText('Найденные места');
}

/** Прозрачный слой-ловушка тапов мимо шторки: он aria-hidden, ищем по классу. */
function suggestionsBackdrop(): HTMLElement {
  const node = document.querySelector('.rd-geo-sugg-backdrop');
  if (!(node instanceof HTMLElement)) throw new Error('слой-ловушка тапов не отрисован');
  return node;
}

type UserEventApi = ReturnType<typeof userEvent.setup>;

/** Пикер с уже загруженной картой: до её готовности лупа и «Готово» заблокированы. */
async function renderReadyPicker() {
  const map = fakePickerMap(DEFAULT_CENTER);
  maps.createPickerMap.mockResolvedValue(map);
  const onSelect = vi.fn();
  const user = userEvent.setup();
  render(
    <LocationPickerSheet initial={null} clubId={CLUB_ID} onSelect={onSelect} onClose={vi.fn()} />,
  );
  await waitFor(() => expect(screen.getByRole('button', { name: 'Найти адрес' })).toBeEnabled());
  return { user, onSelect, map };
}

/** Ввести запрос и нажать лупу — единственный способ запустить поиск (live-саджеста нет). */
async function searchFor(user: UserEventApi, query: string) {
  await user.type(screen.getByPlaceholderText('Название места или адрес'), query);
  await user.click(screen.getByRole('button', { name: 'Найти адрес' }));
}

beforeEach(() => {
  maps.createPickerMap.mockReset();
  maps.suggestPlaces.mockReset().mockResolvedValue([]);
  maps.resolveSuggestion.mockReset().mockResolvedValue(null);
  maps.reverseGeocode.mockReset().mockResolvedValue(null);
});

describe('LocationPickerSheet — fail-closed при недоступности карт (AC-5)', () => {
  it('CDN не загрузился: сообщение «Карты недоступны», «Готово» и поиск неактивны', async () => {
    maps.createPickerMap.mockRejectedValue(new Error('CDN down'));

    render(<LocationPickerSheet initial={null} onSelect={vi.fn()} onClose={vi.fn()} />);

    expect(await screen.findByText('Карты недоступны, попробуйте позже')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Готово' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Найти адрес' })).toBeDisabled();
    // «Отмена» остаётся доступной — из шита всегда можно выйти
    expect(screen.getByRole('button', { name: 'Отмена' })).toBeEnabled();
  });
});

describe('LocationPickerSheet — поиск места по подсказкам (venue-search, вариант C)', () => {
  it('лупа с результатами: шторка со списком ПОВЕРХ карты, заведения и адреса вперемешку', async () => {
    maps.suggestPlaces.mockResolvedValue([OLD_FRIENDS, HOUSE]);
    const { user } = await renderReadyPicker();

    await searchFor(user, 'Иваново, Old Friends');

    const sheet = await screen.findByLabelText('Найденные места');
    // Оба типа строк в одном списке — без вкладок и переключателей (решение PO).
    expect(within(sheet).getAllByRole('button')).toHaveLength(2);
    expect(within(sheet).getByRole('button', { name: /Old Friends/ })).toBeInTheDocument();
    expect(within(sheet).getByRole('button', { name: /Ивановская область/ })).toBeInTheDocument();
    // Один поиск = один запрос, и клуб уходит вместе с ним.
    expect(maps.suggestPlaces).toHaveBeenCalledTimes(1);
    expect(maps.suggestPlaces).toHaveBeenCalledWith('Иваново, Old Friends', CLUB_ID);
    // Вариант C: список — слой ВНУТРИ карты, а не вместо неё; карта видна снизу.
    expect(sheet.closest('.rd-geo-map')).not.toBeNull();
    expect(document.querySelector('.rd-geo-map-canvas')).not.toBeNull();
  });

  it('тап по строке: шторка закрылась, «Готово» отдаёт НАЗВАНИЕ заведения, а не улицу (AC-4, AC-5)', async () => {
    maps.suggestPlaces.mockResolvedValue([OLD_FRIENDS, HOUSE]);
    maps.resolveSuggestion.mockResolvedValue({
      point: VENUE_POINT,
      // Геокодер по uri организации возвращает адресом её же название — оно бесполезно,
      // location_text собирается из подсказки.
      address: 'Россия, Ивановская область, Иваново, Old Friends',
    });
    const { user, onSelect } = await renderReadyPicker();
    await searchFor(user, 'Иваново, Old Friends');

    const sheet = await screen.findByLabelText('Найденные места');
    await user.click(within(sheet).getByRole('button', { name: /Old Friends/ }));

    await waitFor(() => expect(suggestionsSheet()).not.toBeInTheDocument());
    expect(maps.resolveSuggestion).toHaveBeenCalledWith(OLD_FRIENDS.uri);
    // Над картой — выбранное место с приглашением уточнить точку.
    expect(screen.getByText(new RegExp(VENUE_TEXT))).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Готово' }));

    await waitFor(() => expect(onSelect).toHaveBeenCalledWith(VENUE_POINT, VENUE_TEXT));
    // Ключевой регресс спеки: обратного геокодинга нет — иначе «Old Friends» превратилось
    // бы в «Пограничный переулок, 62», и фича сломала бы сама себя.
    expect(maps.reverseGeocode).not.toHaveBeenCalled();
  });

  it('дом/улица уходят в форму адресом как есть, без склейки с названием', async () => {
    maps.suggestPlaces.mockResolvedValue([HOUSE]);
    maps.resolveSuggestion.mockResolvedValue({ point: VENUE_POINT, address: 'неважно' });
    const { user, onSelect } = await renderReadyPicker();
    await searchFor(user, 'Иваново, Пограничный');

    const sheet = await screen.findByLabelText('Найденные места');
    await user.click(within(sheet).getByRole('button', { name: /Ивановская область/ }));
    await waitFor(() => expect(suggestionsSheet()).not.toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: 'Готово' }));

    await waitFor(() =>
      expect(onSelect).toHaveBeenCalledWith(VENUE_POINT, 'Иваново, Пограничный переулок, 62'),
    );
  });

  it('пустая выдача: шторки нет, есть строка «ничего не нашли», карта на месте (AC-3)', async () => {
    maps.suggestPlaces.mockResolvedValue([]);
    const { user } = await renderReadyPicker();

    await searchFor(user, 'Блаблабла Пыщь');

    expect(await screen.findByText(/Ничего не нашли/)).toBeInTheDocument();
    expect(suggestionsSheet()).not.toBeInTheDocument();
    // Карта и ручной пин остаются рабочими — пустая выдача не блокирует создание события.
    expect(document.querySelector('.rd-geo-map-canvas')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Готово' })).toBeEnabled();
  });

  it('тап мимо шторки закрывает её, повторная лупа открывает тот же список без запроса (AC-4a)', async () => {
    maps.suggestPlaces.mockResolvedValue([OLD_FRIENDS]);
    const { user } = await renderReadyPicker();
    await searchFor(user, 'Иваново, Old Friends');
    await screen.findByLabelText('Найденные места');

    await user.click(suggestionsBackdrop());

    expect(suggestionsSheet()).not.toBeInTheDocument();
    // Карта под шторкой никуда не делась — человек осматривается и открывает список снова.
    expect(document.querySelector('.rd-geo-map-canvas')).not.toBeNull();

    await user.click(screen.getByRole('button', { name: 'Найти адрес' }));

    expect(await screen.findByLabelText('Найденные места')).toBeInTheDocument();
    // Запрос по-прежнему один: результат держим в состоянии, квоту не тратим.
    expect(maps.suggestPlaces).toHaveBeenCalledTimes(1);
  });

  it('саджест недоступен (503): «поиск временно недоступен», точку пином поставить можно (AC-8)', async () => {
    maps.suggestPlaces.mockRejectedValue(new GeocoderHttpError(503));
    const { user } = await renderReadyPicker();

    await searchFor(user, 'Иваново, Old Friends');

    expect(await screen.findByText(/Поиск временно недоступен/)).toBeInTheDocument();
    expect(suggestionsSheet()).not.toBeInTheDocument();
    // Fail-open, как у геокодера: без подсказок событие всё равно создаётся.
    expect(screen.getByRole('button', { name: 'Готово' })).toBeEnabled();
  });

  it('сдвинул пин после выбора — прежнее поведение: адрес из обратного геокодинга (AC-6)', async () => {
    maps.suggestPlaces.mockResolvedValue([OLD_FRIENDS]);
    maps.resolveSuggestion.mockResolvedValue({ point: VENUE_POINT, address: 'неважно' });
    maps.reverseGeocode.mockResolvedValue('Иваново, Пограничный переулок, 60');
    const { user, onSelect, map } = await renderReadyPicker();
    await searchFor(user, 'Иваново, Old Friends');

    const sheet = await screen.findByLabelText('Найденные места');
    await user.click(within(sheet).getByRole('button', { name: /Old Friends/ }));
    await waitFor(() => expect(suggestionsSheet()).not.toBeInTheDocument());

    // Пользователь уточнил место пальцем — центр ушёл с выбранной подсказки.
    map.moveTo(MOVED_POINT);
    await user.click(screen.getByRole('button', { name: 'Готово' }));

    await waitFor(() =>
      expect(onSelect).toHaveBeenCalledWith(MOVED_POINT, 'Иваново, Пограничный переулок, 60'),
    );
    expect(maps.reverseGeocode).toHaveBeenCalledWith(MOVED_POINT);
  });
});

import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { ClubActivityFeed, EventActivityDto } from '../../api/activities';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));

import { ClubActivitiesTab } from '../../components/club/ClubActivitiesTab';

const CLUB_ID = 'club-1';

function buildEvent(overrides: Partial<EventActivityDto> = {}): EventActivityDto {
  return {
    type: 'event',
    id: 'e-1',
    clubId: CLUB_ID,
    title: 'Йога в парке',
    createdAt: '2026-05-23T10:00:00Z',
    isCompleted: false,
    eventDatetime: '2026-05-30T11:00:00Z',
    locationText: 'Парк',
    participantLimit: 20,
    goingCount: 5,
    confirmedCount: 0,
    status: 'upcoming',
    descriptionPreview: null,
    photoUrl: null,
    actionRequired: false,
    ...overrides,
  };
}

// Клуб с одним предстоящим событием и без складчин: под фильтром «Сборы» (type=skladchina)
// фид пустой, под «Все»/«События» — фид с событием.
function mockClubWithOneEvent() {
  server.use(
    http.get(`*/api/clubs/${CLUB_ID}/activities`, ({ request }) => {
      const type = new URL(request.url).searchParams.get('type');
      const feed: ClubActivityFeed =
        type === 'skladchina'
          ? { upcoming: [], past: [] }
          : { upcoming: [buildEvent()], past: [] };
      return HttpResponse.json(feed);
    }),
  );
}

function renderTab(isManager: boolean) {
  const user = userEvent.setup();
  const result = renderWithProviders(
    <ClubActivitiesTab clubId={CLUB_ID} isManager={isManager} />,
  );
  return { ...result, user };
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('ClubActivitiesTab — W3-06 честный текст при активном фильтре типа', () => {
  it('участник: фильтр «Сборы» без складчин → «Нет активностей этого типа» + «Показать все», НЕ ролевая сцена', async () => {
    mockClubWithOneEvent();
    const { user } = renderTab(false);

    // Под фильтром «Все» видно событие клуба.
    expect(await screen.findByText('Йога в парке')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Сборы' }));

    expect(await screen.findByText('Нет активностей этого типа')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Показать все' })).toBeInTheDocument();
    // Ролевая сцена лиса-кафе под активным фильтром не показывается (её тексты — вранье при фильтре).
    expect(screen.queryByText('Активностей пока нет')).toBeNull();
    expect(screen.queryByText('Пока ни одной активности')).toBeNull();

    // «Показать все» сбрасывает фильтр — событие снова видно, фильтр-ветка исчезает.
    await user.click(screen.getByRole('button', { name: 'Показать все' }));
    expect(await screen.findByText('Йога в парке')).toBeInTheDocument();
    expect(screen.queryByText('Нет активностей этого типа')).toBeNull();
  });

  it('владелец: та же фильтр-ветка, но БЕЗ кнопки «Создать активность»', async () => {
    mockClubWithOneEvent();
    const { user } = renderTab(true);
    expect(await screen.findByText('Йога в парке')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Сборы' }));

    expect(await screen.findByText('Нет активностей этого типа')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Создать активность' })).toBeNull();
  });

  it('фильтр «Все» и пустой upcoming → прежняя ролевая сцена лиса-кафе (регрессия W3-07)', async () => {
    server.use(
      http.get(`*/api/clubs/${CLUB_ID}/activities`, () =>
        HttpResponse.json({ upcoming: [], past: [] } satisfies ClubActivityFeed),
      ),
    );
    renderTab(true);

    expect(await screen.findByText('Пока ни одной активности')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Создать активность' })).toBeInTheDocument();
    // Фильтр-ветка не перехватывает, когда фильтр «Все».
    expect(screen.queryByText('Нет активностей этого типа')).toBeNull();
  });
});

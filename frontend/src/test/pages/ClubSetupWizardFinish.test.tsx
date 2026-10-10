import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { FC } from 'react';
import { Route, Routes, useParams } from 'react-router-dom';
import { http, HttpResponse, delay } from 'msw';
import { server } from '../mocks/server';
import { mockClubDetail } from '../mocks/handlers';
import { renderWithProviders } from '../utils/renderWithProviders';
import { useClubQuery } from '../../queries/clubs';
import type { ClubDetailDto } from '../../types/api';

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

import { ClubSetupWizard } from '../../pages/ClubSetupWizard';

const CLUB_ID = 'club-setup-1';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

/** Страница клуба в миниатюре: показывает отметку мастера так, как её видит ClubPage, — из кэша. */
const SetupMarkProbe: FC = () => {
  const { id } = useParams<{ id: string }>();
  const { data } = useClubQuery(id);
  return <div>{data ? `setupCompleted:${data.setupCompleted}` : 'loading'}</div>;
};

/** Клуб на «сервере»: отметка мастера меняется только принятым PUT — как в настоящем API. */
let serverSetupCompleted = false;

function mockClubAndRights() {
  serverSetupCompleted = false;
  server.use(
    http.get('*/api/clubs/:id', () => HttpResponse.json(clubOnServer())),
    // Все права у бота есть — шага прав нет, «Готово» на шаге обложки завершает мастер.
    http.get('*/api/clubs/:id/chat-link', () => HttpResponse.json({
      linked: true, canPinMessages: true, canInviteUsers: true, canRestrictMembers: true,
    })),
  );
}

function clubOnServer(): ClubDetailDto {
  return {
    ...mockClubDetail, id: CLUB_ID, cityId: 'city-1', city: 'Москва', setupCompleted: serverSetupCompleted,
  } as ClubDetailDto;
}

function renderWizard() {
  const user = userEvent.setup();
  renderWithProviders(
    <Routes>
      <Route path="/clubs/:id/setup" element={<ClubSetupWizard />} />
      <Route path="/clubs/:id" element={<SetupMarkProbe />} />
    </Routes>,
    { routerEntries: [`/clubs/${CLUB_ID}/setup?step=4`] },
  );
  return user;
}

describe('ClubSetupWizard · «Готово»', () => {
  beforeEach(() => {
    localStorage.clear();
    mockClubAndRights();
  });

  it('в клуб уходит, когда сервер принял отметку, — страница с первого кадра видит её пройденной', async () => {
    // Чек-лист «Клуб создан» читает отметку из кэша: со старой он встретил бы непройденным шагом.
    server.use(http.put('*/api/clubs/:id', async () => {
      await delay(150);
      serverSetupCompleted = true;
      return HttpResponse.json(clubOnServer());
    }));
    const user = renderWizard();

    await user.click(await screen.findByRole('button', { name: 'Готово' }));

    const mark = await screen.findByText(/^setupCompleted:/);
    expect(mark).toHaveTextContent('setupCompleted:true');
  });

  it('сервер отметку не принял — в клуб всё равно пускает, шаг остаётся непройденным', async () => {
    server.use(http.put('*/api/clubs/:id', () => HttpResponse.json({ message: 'boom' }, { status: 500 })));
    const user = renderWizard();

    await user.click(await screen.findByRole('button', { name: 'Готово' }));

    await waitFor(() => expect(screen.getByText('setupCompleted:false')).toBeInTheDocument());
  });
});

import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import type { ClubDetailDto } from '../../types/api';

// Mock Telegram SDK
vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
  backButton: { show: vi.fn(), hide: vi.fn(), onClick: vi.fn(() => vi.fn()) },
  mountBackButton: Object.assign(vi.fn(), { isAvailable: () => false }),
  unmountBackButton: vi.fn(),
  showBackButton: Object.assign(vi.fn(), { isAvailable: () => false }),
  hideBackButton: Object.assign(vi.fn(), { isAvailable: () => false }),
  onBackButtonClick: Object.assign(vi.fn(() => vi.fn()), { isAvailable: () => false }),
}));

// Mock Telegram UI with our minimal mock components
vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));

// Mock the telegram sdk module
vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

// Import after mocks are set up
import { CreateClubModal } from '../../pages/OrganizerPage';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderModal(props?: { onCreated?: (id: string) => void; onClose?: () => void }) {
  const onClose = props?.onClose ?? vi.fn();
  const onCreated = props?.onCreated ?? vi.fn();
  const user = userEvent.setup();

  const result = render(
    <MemoryRouter>
      <CreateClubModal onClose={onClose} onCreated={onCreated} />
    </MemoryRouter>
  );

  return { ...result, user, onClose, onCreated };
}

describe('CreateClubModal', () => {
  beforeEach(() => {
    server.resetHandlers();
  });

  it('shows error "Название: минимум 3 символа" when name is too short and "Далее" is clicked', async () => {
    const { user } = renderModal();

    // Name is empty by default, just click Далее
    const nextButton = screen.getByRole('button', { name: /далее/i });
    await user.click(nextButton);

    expect(screen.getByText('Название: минимум 3 символа')).toBeInTheDocument();
  });

  it('shows error "Укажите город" when city is empty but name is valid', async () => {
    const { user } = renderModal();

    // Fill in a valid name
    const nameInput = screen.getByLabelText(/название клуба/i);
    await user.type(nameInput, 'Valid Club Name');

    // City is empty by default, click Далее
    const nextButton = screen.getByRole('button', { name: /далее/i });
    await user.click(nextButton);

    expect(screen.getByText('Укажите город')).toBeInTheDocument();
  });

  it('successfully advances through steps when valid data is entered', async () => {
    const { user } = renderModal();

    // Step 0: fill name and city
    const nameInput = screen.getByLabelText(/название клуба/i);
    await user.type(nameInput, 'My Great Club');
    const cityInput = screen.getByLabelText(/город/i);
    await user.type(cityInput, 'Москва');

    // Advance to step 1
    await user.click(screen.getByRole('button', { name: /далее/i }));
    expect(screen.getByText(/шаг 2/i)).toBeInTheDocument();

    // Step 1: category — just advance (no validation)
    await user.click(screen.getByRole('button', { name: /далее/i }));
    expect(screen.getByText(/шаг 3/i)).toBeInTheDocument();

    // Step 2: members — defaults should be valid (memberLimit=30, price=0)
    await user.click(screen.getByRole('button', { name: /далее/i }));
    expect(screen.getByText(/шаг 4/i)).toBeInTheDocument();

    // Step 3: description
    const descInput = screen.getByLabelText(/описание клуба/i);
    await user.type(descInput, 'This is a valid description for the club.');
    await user.click(screen.getByRole('button', { name: /далее/i }));

    // Step 4: final step — should show "Создать клуб" button
    expect(screen.getByText(/шаг 5/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /создать клуб/i })).toBeInTheDocument();
  });

  it('"Создать клуб" button calls API with correct payload and triggers onCreated', async () => {
    let capturedBody: Record<string, unknown> | null = null;

    server.use(
      http.post('*/api/clubs', async ({ request }) => {
        capturedBody = (await request.json()) as Record<string, unknown>;
        const club: ClubDetailDto = {
          id: 'created-club-id',
          ownerId: 'owner-1',
          name: capturedBody.name as string,
          description: capturedBody.description as string,
          category: capturedBody.category as string,
          accessType: capturedBody.accessType as string,
          city: capturedBody.city as string,
          district: null,
          memberLimit: capturedBody.memberLimit as number,
          subscriptionPrice: capturedBody.subscriptionPrice as number,
          avatarUrl: null,
          rules: null,
          applicationQuestion: null,
          inviteLink: null,
          memberCount: 0,
          activityRating: 0,
          isActive: true,
        };
        return HttpResponse.json(club, { status: 201 });
      })
    );

    const onCreated = vi.fn();
    const { user } = renderModal({ onCreated });

    // Step 0
    await user.type(screen.getByLabelText(/название клуба/i), 'API Test Club');
    await user.type(screen.getByLabelText(/город/i), 'Санкт-Петербург');
    await user.click(screen.getByRole('button', { name: /далее/i }));

    // Step 1
    await user.click(screen.getByRole('button', { name: /далее/i }));

    // Step 2
    await user.click(screen.getByRole('button', { name: /далее/i }));

    // Step 3
    await user.type(
      screen.getByLabelText(/описание клуба/i),
      'Description for API test club verification.'
    );
    await user.click(screen.getByRole('button', { name: /далее/i }));

    // Step 4 — submit
    const submitButton = screen.getByRole('button', { name: /создать клуб/i });
    await user.click(submitButton);

    // Wait for the API call and callback
    await waitFor(() => {
      expect(onCreated).toHaveBeenCalledWith('created-club-id');
    });

    // Verify the API received correct payload
    expect(capturedBody).not.toBeNull();
    expect(capturedBody!.name).toBe('API Test Club');
    expect(capturedBody!.city).toBe('Санкт-Петербург');
    expect(capturedBody!.description).toBe('Description for API test club verification.');
    expect(capturedBody!.category).toBe('other');
    expect(capturedBody!.accessType).toBe('open');
    expect(capturedBody!.memberLimit).toBe(30);
    expect(capturedBody!.subscriptionPrice).toBe(0);
  });

  it('shows error from API on submission failure', async () => {
    server.use(
      http.post('*/api/clubs', () => {
        return HttpResponse.json(
          { message: 'Club name already taken' },
          { status: 409 }
        );
      })
    );

    const { user } = renderModal();

    // Fill all steps quickly
    await user.type(screen.getByLabelText(/название клуба/i), 'Duplicate Club');
    await user.type(screen.getByLabelText(/город/i), 'Москва');
    await user.click(screen.getByRole('button', { name: /далее/i }));
    await user.click(screen.getByRole('button', { name: /далее/i }));
    await user.click(screen.getByRole('button', { name: /далее/i }));
    await user.type(screen.getByLabelText(/описание клуба/i), 'A description that is long enough.');
    await user.click(screen.getByRole('button', { name: /далее/i }));

    // Submit
    await user.click(screen.getByRole('button', { name: /создать клуб/i }));

    // Wait for error to appear
    await waitFor(() => {
      expect(screen.getByText('Club name already taken')).toBeInTheDocument();
    });
  });
});

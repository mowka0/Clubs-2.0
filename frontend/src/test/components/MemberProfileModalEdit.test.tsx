import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { AwardDto, MemberListItemDto, MemberProfileDto } from '../../types/api';

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));
vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));
vi.mock('../../telegram/sdk', () => ({ initTelegramSdk: vi.fn(), getInitDataRaw: () => 'test' }));

import { MemberProfileModal } from '../../components/club/MemberProfileModal';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const CLUB = 'club-1';
const inDays = (n: number) => new Date(Date.now() + n * 86_400_000).toISOString();

const MEMBER: MemberListItemDto = {
  userId: 'u-1', firstName: 'Игорь', lastName: 'Соколов', avatarUrl: null, role: 'member',
  joinedAt: inDays(-30), trust: 70, promiseFulfillmentPct: 90, totalConfirmations: 4, awards: [],
  accessStatus: 'active', subscriptionExpiresAt: inDays(20),
};

function mockProfile(note: string | null, awards: AwardDto[] = [], applicationAnswer: string | null = null) {
  const profile: MemberProfileDto = {
    userId: 'u-1', clubId: CLUB, firstName: 'Игорь', username: 'igor_s', avatarUrl: null,
    bio: null, interests: [], awards, role: 'member', trust: 70, promiseFulfillmentPct: 90,
    totalConfirmations: 4, totalAttendances: 3, spontaneityCount: 0, skladchinaPaid: null,
    skladchinaTotal: null, subscriptionExpiresAt: inDays(20), organizerNote: note,
    duesClaimedAt: null, duesClaimMethod: null, duesProofUrl: null, applicationAnswer,
  };
  server.use(http.get(`*/api/clubs/${CLUB}/members/u-1`, () => HttpResponse.json(profile)));
}

describe('MemberProfileModal — admin edit (S1)', () => {
  it('shows the saved note in the always-open field to the organizer', async () => {
    mockProfile('Помогает с площадкой');
    renderWithProviders(
      <MemberProfileModal member={MEMBER} clubId={CLUB} isOrganizer onClose={() => {}} />,
    );
    // The note is an always-open textarea (item 4), so it surfaces as the field's value.
    expect(await screen.findByDisplayValue('Помогает с площадкой')).toBeInTheDocument();
  });

  it('note field is always-open in a free club, alongside «Удалить из клуба» (item 4)', async () => {
    mockProfile(null);
    // Free member: no access window → de-Stars subscription strip is hidden, but the note must still
    // have a home in the management panel (the bug: panel was just «Удалить из клуба»).
    const free: MemberListItemDto = { ...MEMBER, subscriptionExpiresAt: null, accessStatus: 'active' };
    renderWithProviders(
      <MemberProfileModal member={free} clubId={CLUB} isOrganizer onClose={() => {}} />,
    );
    // The note textarea is present without tapping ✎.
    expect(await screen.findByPlaceholderText(/помогает с площадкой/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Удалить из клуба/ })).toBeInTheDocument();
  });

  it('shows the join-application answer to the organizer', async () => {
    mockProfile(null, [], 'Хочу заниматься бегом по утрам');
    renderWithProviders(
      <MemberProfileModal member={MEMBER} clubId={CLUB} isOrganizer onClose={() => {}} />,
    );
    expect(await screen.findByText('Хочу заниматься бегом по утрам')).toBeInTheDocument();
    expect(screen.getByText('Ответ на заявку')).toBeInTheDocument();
  });

  it('does not show the application answer to a regular member', async () => {
    mockProfile(null, [], 'Секретный ответ');
    renderWithProviders(
      <MemberProfileModal member={MEMBER} clubId={CLUB} isOrganizer={false} onClose={() => {}} />,
    );
    await screen.findByText(/Репутация в этом клубе/i);
    expect(screen.queryByText('Секретный ответ')).not.toBeInTheDocument();
  });

  it('does not show the note (or gate) to a regular member', async () => {
    mockProfile('Помогает с площадкой');
    renderWithProviders(
      <MemberProfileModal member={MEMBER} clubId={CLUB} isOrganizer={false} onClose={() => {}} />,
    );
    // Wait for the profile to load (the reputation block renders), then assert no note/edit.
    await screen.findByText(/Репутация в этом клубе/i);
    expect(screen.queryByText('Помогает с площадкой')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Редактировать/ })).not.toBeInTheDocument();
  });

  it('editing the always-open note saves via PATCH /note (its own «Сохранить заметку»)', async () => {
    mockProfile(null);
    let patchedNote: string | null | undefined;
    server.use(
      http.patch(`*/api/clubs/${CLUB}/members/u-1/note`, async ({ request }) => {
        patchedNote = ((await request.json()) as { note: string | null }).note;
        return HttpResponse.json({
          id: 'm-1', userId: 'u-1', clubId: CLUB, status: 'active', role: 'member',
          joinedAt: MEMBER.joinedAt, subscriptionExpiresAt: MEMBER.subscriptionExpiresAt,
        });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(<MemberProfileModal member={MEMBER} clubId={CLUB} isOrganizer onClose={() => {}} />);

    // No ✎ — the note field is always open; «Сохранить заметку» appears once the text changes.
    await user.type(await screen.findByPlaceholderText(/помогает с площадкой/i), 'Завсегдатай');
    await user.click(screen.getByRole('button', { name: /Сохранить заметку/ }));

    await waitFor(() => expect(patchedNote).toBe('Завсегдатай'));
  });
});

describe('MemberProfileModal — reject paid join (B+C)', () => {
  it('organizer rejects a frozen join via reject-dues (two-tap confirm)', async () => {
    mockProfile(null);
    let rejected = false;
    server.use(
      http.post(`*/api/clubs/${CLUB}/members/u-1/reject-dues`, () => {
        rejected = true;
        return HttpResponse.json({
          id: 'm-1', userId: 'u-1', clubId: CLUB, status: 'cancelled', role: 'member',
          joinedAt: null, subscriptionExpiresAt: null,
        });
      }),
    );
    const frozen: MemberListItemDto = { ...MEMBER, accessStatus: 'frozen', subscriptionExpiresAt: null };
    const user = userEvent.setup();
    renderWithProviders(<MemberProfileModal member={frozen} clubId={CLUB} isOrganizer onClose={() => {}} />);

    await user.click(await screen.findByRole('button', { name: /вернуть перевод/ }));
    await user.click(screen.getByRole('button', { name: /Отказать и вернуть/ }));

    await waitFor(() => expect(rejected).toBe(true));
  });
});

describe('MemberProfileModal — remove member (kick)', () => {
  it('organizer kicks an active member via POST /remove with a mandatory reason', async () => {
    mockProfile(null);
    let removeBody: { reason: string } | undefined;
    server.use(
      http.post(`*/api/clubs/${CLUB}/members/u-1/remove`, async ({ request }) => {
        removeBody = (await request.json()) as { reason: string };
        return HttpResponse.json({
          id: 'm-1', userId: 'u-1', clubId: CLUB, status: 'cancelled', role: 'member',
          joinedAt: null, subscriptionExpiresAt: null,
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<MemberProfileModal member={MEMBER} clubId={CLUB} isOrganizer onClose={() => {}} />);

    // Open the kick confirm (active member → «Удалить из клуба» in the panel footer).
    await user.click(await screen.findByRole('button', { name: /Удалить из клуба/ }));
    // The confirm button stays disabled until the reason is long enough.
    await user.type(screen.getByPlaceholderText(/Причина/i), 'нарушает правила клуба');
    await user.click(screen.getByRole('button', { name: /Удалить из клуба/ }));

    await waitFor(() => expect(removeBody?.reason).toBe('нарушает правила клуба'));
  });
});

describe('MemberProfileModal — club awards (S2)', () => {
  const AWARD: AwardDto = { id: 'a-1', emoji: '🔥', label: 'Активист' };

  it('shows award chips to a regular member (public, R3)', async () => {
    mockProfile(null, [AWARD]);
    renderWithProviders(
      <MemberProfileModal member={MEMBER} clubId={CLUB} isOrganizer={false} onClose={() => {}} />,
    );
    expect(await screen.findByText('Активист')).toBeInTheDocument();
    // ...but no edit affordance for a non-organizer.
    expect(screen.queryByRole('button', { name: /Редактировать/ })).not.toBeInTheDocument();
  });

  it('organizer ✎ → adds an award via POST /awards', async () => {
    mockProfile(null, []);
    let posted: { emoji: string; label: string } | undefined;
    server.use(
      http.get(`*/api/clubs/${CLUB}/award-suggestions`, () => HttpResponse.json([])),
      http.post(`*/api/clubs/${CLUB}/members/u-1/awards`, async ({ request }) => {
        posted = (await request.json()) as { emoji: string; label: string };
        return HttpResponse.json({ id: 'a-9', emoji: posted.emoji, label: posted.label });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(<MemberProfileModal member={MEMBER} clubId={CLUB} isOrganizer onClose={() => {}} />);

    await user.click(await screen.findByRole('button', { name: /Редактировать/ }));
    await user.click(screen.getByRole('button', { name: /Добавить награду/ }));
    await user.type(screen.getByPlaceholderText(/Название награды/i), 'Душа клуба');
    await user.click(screen.getByRole('button', { name: /Создать награду/ }));

    await waitFor(() => expect(posted?.label).toBe('Душа клуба'));
    expect(posted?.emoji).toBeTruthy();
  });

  it('organizer can revoke an award via DELETE', async () => {
    mockProfile(null, [AWARD]);
    let deleted = false;
    server.use(
      http.delete(`*/api/clubs/${CLUB}/members/u-1/awards/a-1`, () => {
        deleted = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(<MemberProfileModal member={MEMBER} clubId={CLUB} isOrganizer onClose={() => {}} />);

    await user.click(await screen.findByRole('button', { name: /Редактировать/ }));
    await user.click(await screen.findByRole('button', { name: /Снять награду Активист/ }));

    await waitFor(() => expect(deleted).toBe(true));
  });
});

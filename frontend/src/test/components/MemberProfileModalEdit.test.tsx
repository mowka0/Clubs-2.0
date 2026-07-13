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

// Тестовый id клуба, общий для всех сценариев файла.
const CLUB = 'club-1';
const inDays = (n: number) => new Date(Date.now() + n * 86_400_000).toISOString();

// Базовая фикстура активного платного участника (u-1) — сценарии переопределяют нужные поля.
const MEMBER: MemberListItemDto = {
  userId: 'u-1', firstName: 'Игорь', lastName: 'Соколов', avatarUrl: null, role: 'member',
  joinedAt: inDays(-30), trust: 70, promiseFulfillmentPct: 90, totalConfirmations: 4, awards: [],
  accessStatus: 'active', subscriptionExpiresAt: inDays(20),
};

function mockProfile(
  note: string | null,
  awards: AwardDto[] = [],
  applicationAnswer: string | null = null,
  role: MemberProfileDto['role'] = 'member',
) {
  const profile: MemberProfileDto = {
    userId: 'u-1', clubId: CLUB, firstName: 'Игорь', username: 'igor_s', avatarUrl: null,
    bio: null, interests: [], awards, role, trust: 70, promiseFulfillmentPct: 90,
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
    // Заметка — всегда открытая textarea (пункт 4), поэтому она видна как значение поля.
    expect(await screen.findByDisplayValue('Помогает с площадкой')).toBeInTheDocument();
  });

  it('note field is always-open in a free club, alongside «Удалить из клуба» (item 4)', async () => {
    mockProfile(null);
    // Бесплатный участник: нет окна доступа → de-Stars-полоска подписки скрыта, но у заметки всё равно
    // должно быть место в панели управления (баг: панель состояла из одной «Удалить из клуба»).
    const free: MemberListItemDto = { ...MEMBER, subscriptionExpiresAt: null, accessStatus: 'active' };
    renderWithProviders(
      <MemberProfileModal member={free} clubId={CLUB} isOrganizer onClose={() => {}} />,
    );
    // Textarea заметки присутствует без тапа по ✎.
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
    // Ждём загрузки профиля (рендерится блок репутации), затем проверяем отсутствие заметки/редактирования.
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

    // Без ✎ — поле заметки всегда открыто; «Сохранить заметку» появляется при изменении текста.
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

    // Открываем подтверждение кика (активный участник → «Удалить из клуба» в подвале панели).
    await user.click(await screen.findByRole('button', { name: /Удалить из клуба/ }));
    // Кнопка подтверждения остаётся disabled, пока причина недостаточно длинная.
    await user.type(screen.getByPlaceholderText(/Причина/i), 'нарушает правила клуба');
    await user.click(screen.getByRole('button', { name: /Удалить из клуба/ }));

    await waitFor(() => expect(removeBody?.reason).toBe('нарушает правила клуба'));
  });
});

describe('MemberProfileModal — role selector (club-roles)', () => {
  const CO_ORG: MemberListItemDto = { ...MEMBER, role: 'co_organizer' };

  it('owner sees a role selector with assignable roles and their descriptions (AC-8)', async () => {
    mockProfile(null);
    renderWithProviders(
      <MemberProfileModal member={MEMBER} clubId={CLUB} isOrganizer isOwner onClose={() => {}} />,
    );

    // Оба назначаемых пункта присутствуют как radio, текущая роль (Участник) отмечена «Сейчас».
    const memberOpt = await screen.findByRole('radio', { name: 'Участник' });
    const coOrgOpt = screen.getByRole('radio', { name: 'Со-организатор' });
    expect(memberOpt).toHaveAttribute('aria-checked', 'true');
    expect(coOrgOpt).toHaveAttribute('aria-checked', 'false');
    // «organizer» в селектор не входит (передача владения вне скоупа).
    expect(screen.queryByRole('radio', { name: 'Организатор' })).not.toBeInTheDocument();
    // У каждого пункта — описание из ROLE_DESCRIPTIONS.
    expect(screen.getByText(/Обычный участник клуба/)).toBeInTheDocument();
    expect(screen.getByText(/Ведёт клуб вместе с вами/)).toBeInTheDocument();
  });

  it('owner promotes a member: select «Со-организатор» → confirm shows both buttons → PUT /role', async () => {
    mockProfile(null);
    let putBody: { role: string } | undefined;
    server.use(
      http.put(`*/api/clubs/${CLUB}/members/u-1/role`, async ({ request }) => {
        putBody = (await request.json()) as { role: string };
        return HttpResponse.json({
          id: 'm-1', userId: 'u-1', clubId: CLUB, status: 'active', role: 'co_organizer',
          joinedAt: MEMBER.joinedAt, subscriptionExpiresAt: MEMBER.subscriptionExpiresAt,
        });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(
      <MemberProfileModal member={MEMBER} clubId={CLUB} isOrganizer isOwner onClose={() => {}} />,
    );

    // Выбор роли → инлайн-подтверждение с ВОПРОСОМ и ОБЕИМИ кнопками одновременно (AC-10).
    await user.click(await screen.findByRole('radio', { name: 'Со-организатор' }));
    expect(screen.getByText(/Сделать Игорь со-организатором\?/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Отмена' })).toBeInTheDocument();
    const assign = screen.getByRole('button', { name: 'Назначить' });
    expect(assign).toBeInTheDocument();

    await user.click(assign);
    await waitFor(() => expect(putBody?.role).toBe('co_organizer'));
  });

  it('tapping a role highlights it as picked, and tapping the current role cancels the pick', async () => {
    mockProfile(null);
    const user = userEvent.setup();
    renderWithProviders(
      <MemberProfileModal member={MEMBER} clubId={CLUB} isOrganizer isOwner onClose={() => {}} />,
    );

    const memberOpt = await screen.findByRole('radio', { name: 'Участник' });
    const coOrgOpt = screen.getByRole('radio', { name: 'Со-организатор' });

    // Тап по роли: пункт получает видимое состояние «выбрано» и забирает отметку radiogroup себе,
    // текущая роль гаснет (акцент носит ровно один пункт).
    await user.click(coOrgOpt);
    expect(coOrgOpt).toHaveClass('pend');
    expect(coOrgOpt).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByText('✓ Выбрано')).toBeInTheDocument();
    expect(memberOpt).toHaveAttribute('aria-checked', 'false');
    expect(memberOpt).not.toHaveClass('pend');

    // Тап по текущей роли отменяет выбор: подтверждение исчезает, отметка возвращается к ней.
    await user.click(memberOpt);
    expect(coOrgOpt).not.toHaveClass('pend');
    expect(screen.queryByText('✓ Выбрано')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Назначить' })).not.toBeInTheDocument();
    expect(memberOpt).toHaveAttribute('aria-checked', 'true');
  });

  it('owner demotes a co-organizer: select «Участник» → «Снять роль» → PUT /role', async () => {
    mockProfile(null, [], null, 'co_organizer');
    let putBody: { role: string } | undefined;
    server.use(
      http.put(`*/api/clubs/${CLUB}/members/u-1/role`, async ({ request }) => {
        putBody = (await request.json()) as { role: string };
        return HttpResponse.json({
          id: 'm-1', userId: 'u-1', clubId: CLUB, status: 'active', role: 'member',
          joinedAt: MEMBER.joinedAt, subscriptionExpiresAt: MEMBER.subscriptionExpiresAt,
        });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(
      <MemberProfileModal member={CO_ORG} clubId={CLUB} isOrganizer isOwner onClose={() => {}} />,
    );

    // Владелец сохраняет админ-панель по со-оргу (per-target матрица: owner → co-org разрешено).
    expect(await screen.findByRole('button', { name: /Удалить из клуба/ })).toBeInTheDocument();
    // Со-организатор отмечен «Сейчас», Участник — actionable-пункт демоута.
    expect(screen.getByRole('radio', { name: 'Со-организатор' })).toHaveAttribute('aria-checked', 'true');

    await user.click(screen.getByRole('radio', { name: 'Участник' }));
    await user.click(screen.getByRole('button', { name: 'Снять роль' }));

    await waitFor(() => expect(putBody?.role).toBe('member'));
  });

  it('the «Cancel» button dismisses the confirm without a request', async () => {
    mockProfile(null);
    let called = false;
    server.use(
      http.put(`*/api/clubs/${CLUB}/members/u-1/role`, () => {
        called = true;
        return HttpResponse.json({ id: 'm-1', userId: 'u-1', clubId: CLUB, status: 'active', role: 'co_organizer', joinedAt: MEMBER.joinedAt, subscriptionExpiresAt: MEMBER.subscriptionExpiresAt });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <MemberProfileModal member={MEMBER} clubId={CLUB} isOrganizer isOwner onClose={() => {}} />,
    );

    await user.click(await screen.findByRole('radio', { name: 'Со-организатор' }));
    await user.click(screen.getByRole('button', { name: 'Отмена' }));

    // Подтверждение свёрнуто, запроса не было.
    expect(screen.queryByRole('button', { name: 'Назначить' })).not.toBeInTheDocument();
    expect(called).toBe(false);
  });

  it('promote option is disabled for a frozen member with an explanation (У-9)', async () => {
    mockProfile(null);
    const frozen: MemberListItemDto = { ...MEMBER, accessStatus: 'frozen', subscriptionExpiresAt: null };
    renderWithProviders(
      <MemberProfileModal member={frozen} clubId={CLUB} isOrganizer isOwner onClose={() => {}} />,
    );

    const coOrgOpt = await screen.findByRole('radio', { name: 'Со-организатор' });
    expect(coOrgOpt).toBeDisabled();
    expect(screen.getByText(/только участника с активным доступом/)).toBeInTheDocument();
  });

  it('hides the role selector from a co-organizer manager (isOrganizer without isOwner)', async () => {
    mockProfile(null);
    renderWithProviders(
      <MemberProfileModal member={MEMBER} clubId={CLUB} isOrganizer onClose={() => {}} />,
    );

    await screen.findByText(/Репутация в этом клубе/i);
    expect(screen.queryByRole('radio', { name: 'Со-организатор' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Роль в клубе/)).not.toBeInTheDocument();
  });

  it('co-organizer manager cannot manage another co-organizer (no admin panel, per-target 403 mirror)', async () => {
    mockProfile(null, [], null, 'co_organizer');
    renderWithProviders(
      <MemberProfileModal member={CO_ORG} clubId={CLUB} isOrganizer onClose={() => {}} />,
    );

    await screen.findByText(/Репутация в этом клубе/i);
    expect(screen.queryByText(/Управление участником/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Удалить из клуба/ })).not.toBeInTheDocument();
  });

  it('shows the backend error text when promote hits the co-org limit (400, У-3)', async () => {
    mockProfile(null);
    server.use(
      http.put(`*/api/clubs/${CLUB}/members/u-1/role`, () =>
        HttpResponse.json({ error: 'VALIDATION', message: 'Co-organizer limit reached (5)' }, { status: 400 })),
    );

    const user = userEvent.setup();
    renderWithProviders(
      <MemberProfileModal member={MEMBER} clubId={CLUB} isOrganizer isOwner onClose={() => {}} />,
    );

    await user.click(await screen.findByRole('radio', { name: 'Со-организатор' }));
    await user.click(screen.getByRole('button', { name: 'Назначить' }));

    expect(await screen.findByText(/Co-organizer limit reached/)).toBeInTheDocument();
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
    // ...но без возможности редактирования для не-организатора.
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

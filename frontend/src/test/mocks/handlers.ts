import { http, HttpResponse } from 'msw';
import type { ClubDetailDto, MembershipDto } from '../../types/api';

// Default club for tests
export const mockClubDetail: ClubDetailDto = {
  id: 'club-123',
  ownerId: 'owner-456',
  name: 'Test Club',
  description: 'A great test club for testing purposes.',
  category: 'sport',
  accessType: 'open',
  city: 'Москва',
  district: null,
  memberLimit: 50,
  subscriptionPrice: 0,
  avatarUrl: null,
  rules: null,
  applicationQuestion: null,
  inviteLink: null,
  memberCount: 10,
  activityRating: 0,
  isActive: true,
};

export const mockMembership: MembershipDto = {
  id: 'mem-1',
  userId: 'user-1',
  clubId: 'club-123',
  status: 'active',
  role: 'member',
  joinedAt: '2025-01-01T00:00:00Z',
  subscriptionExpiresAt: null,
};

export const handlers = [
  // GET /api/clubs/:id
  http.get('*/api/clubs/:id', ({ params }) => {
    return HttpResponse.json({ ...mockClubDetail, id: params.id as string });
  }),

  // POST /api/clubs — create club
  http.post('*/api/clubs', async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    const club: ClubDetailDto = {
      ...mockClubDetail,
      id: 'new-club-id',
      name: body.name as string,
      description: body.description as string,
      category: body.category as string,
      accessType: body.accessType as string,
      city: body.city as string,
      district: (body.district as string) ?? null,
      memberLimit: body.memberLimit as number,
      subscriptionPrice: body.subscriptionPrice as number,
      rules: (body.rules as string) ?? null,
      applicationQuestion: (body.applicationQuestion as string) ?? null,
    };
    return HttpResponse.json(club, { status: 201 });
  }),

  // GET /api/users/me/clubs
  http.get('*/api/users/me/clubs', () => {
    return HttpResponse.json([] as MembershipDto[]);
  }),

  // POST /api/clubs/:id/join
  http.post('*/api/clubs/:id/join', ({ params }) => {
    const membership: MembershipDto = {
      ...mockMembership,
      clubId: params.id as string,
    };
    return HttpResponse.json(membership);
  }),

  // POST /api/auth/telegram
  http.post('*/api/auth/telegram', () => {
    return HttpResponse.json({
      token: 'test-jwt-token',
      user: {
        id: 'user-1',
        telegramId: 12345,
        telegramUsername: 'testuser',
        firstName: 'Test',
        lastName: 'User',
        avatarUrl: null,
        city: null,
      },
    });
  }),
];

import type { ClubFilters } from '../api/clubs';

export type EventListParams = { status?: string; page?: string; size?: string };

export const queryKeys = {
  clubs: {
    all: ['clubs'] as const,
    list: (filters: Omit<ClubFilters, 'page'>) => ['clubs', 'list', filters] as const,
    my: () => ['clubs', 'my'] as const,
    detail: (id: string) => ['clubs', 'detail', id] as const,
    byInvite: (code: string) => ['clubs', 'invite', code] as const,
    members: (clubId: string) => ['clubs', 'detail', clubId, 'members'] as const,
    memberProfile: (clubId: string, userId: string) =>
      ['clubs', 'detail', clubId, 'members', userId] as const,
    applications: (clubId: string, status?: string) =>
      ['clubs', 'detail', clubId, 'applications', status ?? 'all'] as const,
    finances: (clubId: string) => ['clubs', 'detail', clubId, 'finances'] as const,
  },
  events: {
    all: ['events'] as const,
    byClub: (clubId: string, params?: EventListParams) =>
      ['events', 'by-club', clubId, params ?? {}] as const,
    byClubAll: (clubId: string) => ['events', 'by-club', clubId] as const,
    detail: (id: string) => ['events', 'detail', id] as const,
    myVote: (id: string) => ['events', 'detail', id, 'my-vote'] as const,
  },
  applications: {
    mine: () => ['applications', 'mine'] as const,
  },
} as const;

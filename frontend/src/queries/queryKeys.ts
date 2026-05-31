import type { ClubFilters } from '../api/clubs';
import type { ActivityType } from '../api/activities';

export type EventListParams = { status?: string; page?: string; size?: string };

export interface ClubActivitiesFilters {
  type?: ActivityType;
}

export interface ClubMembersFilters {
  includeCancelled?: boolean;
}

export const queryKeys = {
  clubs: {
    all: ['clubs'] as const,
    list: (filters: Omit<ClubFilters, 'page'>) => ['clubs', 'list', filters] as const,
    my: () => ['clubs', 'my'] as const,
    detail: (id: string) => ['clubs', 'detail', id] as const,
    byInvite: (code: string) => ['clubs', 'invite', code] as const,
    // Prefix shared by every members-list variant. TanStack invalidates by
    // prefix, so leave / join / member-profile mutations can keep using this
    // and still hit the `includeCancelled` variant cached under
    // `membersWith(clubId, filters)`.
    members: (clubId: string) => ['clubs', 'detail', clubId, 'members'] as const,
    membersWith: (clubId: string, filters: ClubMembersFilters) =>
      ['clubs', 'detail', clubId, 'members', filters] as const,
    awaitingPaymentApplicants: (clubId: string) =>
      ['clubs', 'detail', clubId, 'awaiting-payment-applicants'] as const,
    memberProfile: (clubId: string, userId: string) =>
      ['clubs', 'detail', clubId, 'members', userId] as const,
    myReputation: () => ['clubs', 'my', 'reputation'] as const,
    myInterests: () => ['users', 'me', 'interests'] as const,
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
    myFeed: ['events', 'my-feed'] as const,
  },
  applications: {
    mine: () => ['applications', 'mine'] as const,
    myPending: ['applications', 'my-pending'] as const,
    myAwaitingPayment: ['applications', 'my-awaiting-payment'] as const,
    /** Cross-club organizer view: approved-but-unpaid applicants of caller's owned clubs. */
    organizerAwaitingPayment: ['applications', 'organizer-awaiting-payment'] as const,
    /** Combined inbox + awaiting-payment counts (single endpoint). */
    myPendingActionCounts: ['applications', 'my-pending-action-counts'] as const,
  },
  skladchinas: {
    all: ['skladchinas'] as const,
    myFeed: ['skladchinas', 'my-feed'] as const,
    actionRequiredCount: ['skladchinas', 'action-required-count'] as const,
    detail: (id: string) => ['skladchinas', 'detail', id] as const,
    byClubActive: (clubId: string) => ['skladchinas', 'by-club-active', clubId] as const,
  },
  activities: {
    // Prefix used for invalidation across all filter variants of a club.
    byClubAll: (clubId: string) => ['activities', 'by-club', clubId] as const,
    byClub: (clubId: string, filters?: ClubActivitiesFilters) =>
      ['activities', 'by-club', clubId, filters ?? {}] as const,
  },
} as const;

import type { ClubFilters } from '../api/clubs';
import type { ActivityType } from '../api/activities';

export type EventListParams = { status?: string; page?: string; size?: string };

export interface ClubActivitiesFilters {
  type?: ActivityType;
}

export const queryKeys = {
  clubs: {
    all: ['clubs'] as const,
    list: (filters: Omit<ClubFilters, 'page'>) => ['clubs', 'list', filters] as const,
    my: () => ['clubs', 'my'] as const,
    detail: (id: string) => ['clubs', 'detail', id] as const,
    byInvite: (code: string) => ['clubs', 'invite', code] as const,
    // Prefix shared by the members list. TanStack invalidates by prefix, so
    // leave / join / member-profile / access-gate mutations all use this.
    members: (clubId: string) => ['clubs', 'detail', clubId, 'members'] as const,
    // De-Stars red-dot feed: count of members whose paid access ends within the week.
    memberAttention: (clubId: string) =>
      ['clubs', 'detail', clubId, 'member-attention'] as const,
    memberProfile: (clubId: string, userId: string) =>
      ['clubs', 'detail', clubId, 'members', userId] as const,
    // Member admin S2 — distinct past awards in a club, autocomplete source for the grant form.
    awardSuggestions: (clubId: string) =>
      ['clubs', 'detail', clubId, 'award-suggestions'] as const,
    leavePreview: (clubId: string) => ['clubs', 'detail', clubId, 'leave-preview'] as const,
    myReputation: () => ['clubs', 'my', 'reputation'] as const,
    myGamification: () => ['users', 'me', 'gamification'] as const,
    myInterests: () => ['users', 'me', 'interests'] as const,
    applications: (clubId: string, status?: string) =>
      ['clubs', 'detail', clubId, 'applications', status ?? 'all'] as const,
    finances: (clubId: string) => ['clubs', 'detail', clubId, 'finances'] as const,
    stats: (clubId: string) => ['clubs', 'detail', clubId, 'stats'] as const,
    churnedMembers: (clubId: string) => ['clubs', 'detail', clubId, 'churned-members'] as const,
    quality: (clubId: string) => ['clubs', 'detail', clubId, 'quality'] as const,
    /** Batched Discovery-card facts, keyed by the (sorted) set of club ids on screen. */
    cardFacts: (sortedIds: string[]) => ['clubs', 'card-facts', sortedIds] as const,
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
    /** Cross-club organizer pending-inbox count (single endpoint). */
    myPendingActionCounts: ['applications', 'my-pending-action-counts'] as const,
  },
  organizer: {
    /** Cross-club «Ждут оплаты»: frozen members across the caller's owned clubs. */
    awaitingDues: ['organizer', 'awaiting-dues'] as const,
  },
  skladchinas: {
    all: ['skladchinas'] as const,
    myFeed: ['skladchinas', 'my-feed'] as const,
    actionRequiredCount: ['skladchinas', 'action-required-count'] as const,
    detail: (id: string) => ['skladchinas', 'detail', id] as const,
    byClubActive: (clubId: string) => ['skladchinas', 'by-club-active', clubId] as const,
    eventState: (eventId: string) => ['skladchinas', 'event-state', eventId] as const,
  },
  activities: {
    // Prefix used for invalidation across all filter variants of a club.
    byClubAll: (clubId: string) => ['activities', 'by-club', clubId] as const,
    byClub: (clubId: string, filters?: ClubActivitiesFilters) =>
      ['activities', 'by-club', clubId, filters ?? {}] as const,
  },
  subscription: {
    status: ['subscription', 'status'] as const,
    plans: ['subscription', 'plans'] as const,
  },
} as const;

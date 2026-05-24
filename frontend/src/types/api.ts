export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

export interface ErrorResponse {
  error: string;
  message: string;
}

export interface UserDto {
  id: string;
  telegramId: number;
  telegramUsername: string | null;
  firstName: string;
  lastName: string | null;
  avatarUrl: string | null;
  city: string | null;
}

export interface NearestEventDto {
  id: string;
  title: string;
  eventDatetime: string;
  goingCount: number;
}

export interface ClubListItemDto {
  id: string;
  name: string;
  category: string;
  accessType: string;
  city: string;
  subscriptionPrice: number;
  memberCount: number;
  memberLimit: number;
  avatarUrl: string | null;
  nearestEvent: NearestEventDto | null;
  tags: string[];
}

export interface MemberListItemDto {
  userId: string;
  firstName: string;
  lastName: string | null;
  avatarUrl: string | null;
  role: string;
  joinedAt: string | null;
  reliabilityIndex: number;
  promiseFulfillmentPct: number;
}

export interface MemberProfileDto {
  userId: string;
  clubId: string;
  firstName: string;
  username: string | null;
  avatarUrl: string | null;
  reliabilityIndex: number;
  promiseFulfillmentPct: number;
  totalConfirmations: number;
  totalAttendances: number;
}

export interface UserClubReputationDto {
  clubId: string;
  clubName: string;
  clubAvatarUrl: string | null;
  category: string;
  role: string;
  reliabilityIndex: number;
  promiseFulfillmentPct: number;
  totalAttendances: number;
}

export interface ActionRequiredCountDto {
  count: number;
}

export interface FinancesDto {
  activeMembers: number;
  monthlyRevenue: number;
  organizerShare: number;
  platformFee: number;
  organizerSharePct: number;
  platformFeePct: number;
}

export interface ClubApplicationDto {
  id: string;
  userId: string;
  clubId: string;
  status: string;
  answerText: string | null;
  rejectedReason: string | null;
  createdAt: string | null;
  resolvedAt: string | null;
}

export interface ClubDetailDto {
  id: string;
  ownerId: string;
  name: string;
  description: string;
  category: string;
  accessType: string;
  city: string;
  district: string | null;
  memberLimit: number;
  subscriptionPrice: number;
  avatarUrl: string | null;
  rules: string | null;
  applicationQuestion: string | null;
  inviteLink: string | null;
  memberCount: number;
  activityRating: number;
  isActive: boolean;
}

export interface MembershipDto {
  id: string;
  userId: string;
  clubId: string;
  status: string;
  role: string;
  joinedAt: string | null;
  subscriptionExpiresAt: string | null;
}

export interface PendingPaymentDto {
  status: 'pending_payment';
  clubId: string;
  priceStars: number;
  message: string;
}

export type JoinClubResult = MembershipDto | PendingPaymentDto;

export function isPendingPayment(result: JoinClubResult): result is PendingPaymentDto {
  return result.status === 'pending_payment';
}

export interface EventDetailDto {
  id: string;
  clubId: string;
  title: string;
  description: string | null;
  locationText: string;
  eventDatetime: string;
  participantLimit: number;
  votingOpensDaysBefore: number;
  status: string;
  goingCount: number;
  maybeCount: number;
  notGoingCount: number;
  confirmedCount: number;
  attendanceMarked: boolean;
  attendanceFinalized: boolean;
  createdAt: string | null;
}

export interface EventListItemDto {
  id: string;
  title: string;
  eventDatetime: string;
  locationText: string;
  participantLimit: number;
  goingCount: number;
  status: string;
}

export type SkladchinaMode = 'fixed_equal' | 'fixed_individual' | 'voluntary';
export type SkladchinaStatus = 'active' | 'closed_success' | 'closed_failed' | 'cancelled';
export type SkladchinaParticipantStatus = 'pending' | 'paid' | 'declined' | 'expired_no_response';

export interface SkladchinaParticipantDto {
  userId: string;
  firstName: string;
  lastName: string | null;
  avatarUrl: string | null;
  expectedAmountKopecks: number | null;
  declaredAmountKopecks: number | null;
  status: SkladchinaParticipantStatus;
  paidAt: string | null;
}

export interface SkladchinaDetailDto {
  id: string;
  clubId: string;
  clubName: string;
  clubAvatarUrl: string | null;
  creatorId: string;
  title: string;
  description: string | null;
  rules: string | null;
  photoUrl: string | null;
  paymentMode: SkladchinaMode;
  totalGoalKopecks: number | null;
  collectedKopecks: number;
  paymentLink: string;
  paymentMethodNote: string | null;
  deadline: string;
  affectsReputation: boolean;
  status: SkladchinaStatus;
  closedAt: string | null;
  isOrganizerView: boolean;
  myStatus: SkladchinaParticipantStatus | null;
  myExpectedAmountKopecks: number | null;
  myDeclaredAmountKopecks: number | null;
  participants: SkladchinaParticipantDto[] | null;
  participantCount: number;
  paidCount: number;
}

export interface MySkladchinaListItemDto {
  id: string;
  title: string;
  clubId: string;
  clubName: string;
  clubAvatarUrl: string | null;
  paymentMode: SkladchinaMode;
  totalGoalKopecks: number | null;
  collectedKopecks: number;
  participantCount: number;
  paidCount: number;
  deadline: string;
  status: SkladchinaStatus;
  isOrganizerView: boolean;
  myStatus: SkladchinaParticipantStatus | null;
  actionRequired: boolean;
  affectsReputation: boolean;
}

export interface CreateSkladchinaParticipantInput {
  userId: string;
  expectedAmountKopecks?: number | null;
}

export interface CreateSkladchinaRequest {
  title: string;
  description?: string | null;
  rules?: string | null;
  photoUrl?: string | null;
  paymentMode: SkladchinaMode;
  totalGoalKopecks?: number | null;
  paymentLink: string;
  paymentMethodNote?: string | null;
  deadline: string;
  affectsReputation: boolean;
  participants: CreateSkladchinaParticipantInput[];
}

export interface MyEventListItemDto {
  id: string;
  title: string;
  eventDatetime: string;
  locationText: string;
  status: string;
  clubId: string;
  clubName: string;
  clubAvatarUrl: string | null;
  myVote: 'going' | 'maybe' | 'not_going' | null;
  myParticipationStatus: 'confirmed' | 'waitlisted' | 'declined' | null;
  goingCount: number;
  confirmedCount: number;
  participantLimit: number;
  actionRequired: boolean;
}

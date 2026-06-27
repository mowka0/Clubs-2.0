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
  country: string | null;
  bio: string | null;
}

export interface UpdateProfileBody {
  country: string | null;
  city: string | null;
  bio: string | null;
  interests: string[];
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
  // P1b Trust 0-100. null = "Новичок" (no track record yet, or owner in own club — use `role`
  // to render the organizer framing). The whole reputation block is suppressed when null.
  trust: number | null;
  promiseFulfillmentPct: number | null;
  // Stage-2 confirmations to date. The "Обещания X%" line is gated on this being > 0 so a
  // finance-only member (skladchina record, no events) never shows a misleading 0% (F5-08).
  totalConfirmations: number | null;
  // De-Stars Slice 2 — organizer dashboard only (null for regular members): access state and the
  // paid-through date. Drive the «Скоро закончится» / «Ждут оплаты» / «Активные» buckets.
  // `subscriptionExpiresAt` is also null for free memberships (no expiry).
  accessStatus?: 'active' | 'frozen' | null;
  subscriptionExpiresAt?: string | null;
}

export interface MemberProfileDto {
  userId: string;
  clubId: string;
  firstName: string;
  username: string | null;
  avatarUrl: string | null;
  // Public profile fields, shown to every club member on the member card.
  bio: string | null;
  interests: string[];
  // "organizer" = club owner. Drives the organizer framing when trust is null.
  role: string;
  // P1b Trust 0-100. null = "Новичок"/suppressed (no track record yet, or owner in own club).
  trust: number | null;
  promiseFulfillmentPct: number | null;
  totalConfirmations: number | null;
  totalAttendances: number | null;
  // "Возможно → Подтвердил → Пришёл": came though only said "maybe".
  spontaneityCount: number | null;
  // Reputation-affecting skladchina record in this club: paid / (paid + expired).
  // null when suppressed; the "Сборы" ring is hidden when skladchinaTotal === 0.
  skladchinaPaid: number | null;
  skladchinaTotal: number | null;
  // De-Stars Slice 2 — ORGANIZER ONLY (null for regular members): when this member's paid access
  // window ends. null also for free memberships. Shown as «Подписка активна до …» on the org card.
  subscriptionExpiresAt: string | null;
  // Member admin S1 — ORGANIZER ONLY (null for regular members): the private organizer note.
  organizerNote: string | null;
}

/**
 * Red-dot feed (organizer-only): the dot lights when either count is > 0.
 *  - expiringSoon — active members whose paid window ends within the week.
 *  - awaitingDues — frozen members who joined but haven't been admitted (first dues unconfirmed).
 */
export interface MemberAttentionDto {
  expiringSoon: number;
  awaitingDues: number;
}

/**
 * A frozen member across one of the caller's owned clubs (joined, dues unconfirmed). Powers the
 * cross-club «Ждут оплаты» section on «Мои клубы». `subscriptionExpiresAt` is the lapsed window
 * (null for a never-paid first join); `joinedAt` drives the «вступил(а) N назад» line.
 */
export interface OrganizerDuesMemberDto {
  userId: string;
  firstName: string;
  lastName: string | null;
  avatarUrl: string | null;
  telegramUsername: string | null;
  clubId: string;
  clubName: string;
  clubAvatarUrl: string | null;
  joinedAt: string | null;
  subscriptionExpiresAt: string | null;
}

export interface UserClubReputationDto {
  clubId: string;
  clubName: string;
  clubAvatarUrl: string | null;
  category: string;
  role: string;
  joinedAt: string | null;
  // P1b Trust 0-100. null = "Новичок"/suppressed (no track record yet, or owner in own club —
  // `role` = "organizer" renders the organizer framing).
  trust: number | null;
  promiseFulfillmentPct: number | null;
  totalConfirmations: number | null;
  totalAttendances: number | null;
  spontaneityCount: number | null;
}

/** Global reputation: primary "надёжен в reliableClubs из trackRecordClubs клубов". */
export interface GlobalTrustDto {
  reliableClubs: number;
  trackRecordClubs: number;
  // Secondary 0-100 number; null when there is no track record anywhere (trackRecordClubs === 0).
  score: number | null;
}

/** The authenticated user's reputation overview: global aggregate + per-club lists. */
export interface MyReputationDto {
  global: GlobalTrustDto;
  activeClubs: UserClubReputationDto[];
  // Clubs the user left but still has a track record in ("История").
  historyClubs: UserClubReputationDto[];
}

/**
 * Pre-leave preview for the «Выйти из клуба?» dialog: how many open obligations leaving a
 * free club would break (and thus cost the user reliability). Counts only — penalty
 * magnitudes are server-internal. Paid clubs break nothing (all zero).
 */
export interface LeavePreviewDto {
  eventObligations: number;
  skladchinaObligations: number;
  totalObligations: number;
}

/** A passed-threshold gamification badge. */
export interface BadgeDto {
  id: string;
  name: string;
  family: string;
}

/**
 * The authenticated user's gamification panel (`self` view): exact XP, current level + progress to
 * the next, earned badges. XP is participation-only and only accumulates. See reputation-v2.md §H3.
 */
export interface GamificationDto {
  xp: number;
  level: number;
  levelName: string;
  nextLevelName: string | null;
  xpIntoLevel: number;
  xpSpanToNext: number | null;
  badges: BadgeDto[];
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

export interface ApplicantInfoDto {
  userId: string;
  firstName: string;
  lastName: string | null;
  telegramUsername: string | null;
  avatarUrl: string | null;
  country: string | null;
  city: string | null;
  bio: string | null;
  interests: string[];
}

export type LevelTier = 'base' | 'mid' | 'top';

export interface PeerStatsDto {
  memberClubCount: number;
  totalConfirmations: number;
  totalAttendances: number;
  // Cross-club reputation for the review card: "надёжен в reliableClubs из trackRecordClubs клубов".
  reliableClubs: number;
  trackRecordClubs: number;
  // Global gamification level (others projection) for the pill.
  level: number;
  levelName: string;
  levelTier: LevelTier;
}

export interface ClubBriefDto {
  id: string;
  name: string;
  avatarUrl: string | null;
}

export interface PendingApplicationDto {
  applicationId: string;
  answerText: string | null;
  createdAt: string;
  hoursUntilAutoReject: number;
  applicant: ApplicantInfoDto;
  peerStats: PeerStatsDto;
  club: ClubBriefDto;
}

/**
 * Counter feeding the «Мои клубы» tab-dot: organizer-side pending applications
 * in the cross-club inbox. Single endpoint, single cache slot.
 * See docs/modules/applications-inbox.md.
 */
export interface PendingApplicationsCountDto {
  inboxCount: number;
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
  isActive: boolean;
}

/** Club-quality facts for `GET /api/clubs/{id}/quality` (rings + achievements). */
export interface ClubFactsDto {
  meetingsPerMonth: number;
  avgAttendance: number;
  coreSize: number;
  ageMonths: number;
  totalMeetings: number;
  successfulSkladchinas: number;
}

/** Discovery-card quality facts for `GET /api/clubs/quality/batch` (one per club). */
export interface ClubCardFactsDto {
  clubId: string;
  ageDays: number;
  engagementPercent: number;
  /** "★ Топ-5 в категории" — the only externally-visible L3 signal (a pure boolean; the rank score
   *  never leaves the server). False unless the backend badge feature flag is on. */
  topInCategory: boolean;
}

/** Window-over-window movement of a percent metric. `delta` is signed, in percentage points. */
export interface TrendDto {
  direction: 'up' | 'down' | 'flat';
  delta: number;
}

/** A former member to win back — `GET /api/clubs/{clubId}/churned-members` (owner-only, §9.5). */
export interface ChurnedMemberDto {
  userId: string;
  firstName: string;
  lastName: string | null;
  avatarUrl: string | null;
  leftAt: string;
}

/**
 * Owner-only club statistics for `GET /api/clubs/{id}/stats` (§9 of docs/modules/club-quality.md).
 * Nullable fields don't apply to this club: `retention*` is paid-only, `skladchinaPaid*` needs closed
 * skladchinas, and the application fields are null unless the club is `closed`.
 */
export interface ClubStatsDto {
  clubType: 'paid' | 'free';
  retentionPercent: number | null;
  retentionTrend: TrendDto | null;
  churnedThisPeriod: number;
  rejoinedThisPeriod: number;
  engagementPercent: number;
  engagementTrend: TrendDto | null;
  skladchinaPaidPercent: number | null;
  skladchinaPaidTrend: TrendDto | null;
  pendingApplications: number | null;
  stalePendingApplications: number | null;
  attendanceDisputes: number;
  totalMeetings: number;
  autoRejectedApplications: number | null;
  cancelledMeetings: number;
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
  // F5-14: organizer's optional cancellation reason; null unless cancelled with a reason given.
  cancellationReason: string | null;
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

export interface EventResponderDto {
  userId: string;
  firstName: string;
  lastName: string | null;
  avatarUrl: string | null;
  /** going | maybe | not_going | confirmed | waitlisted | declined | expired_no_confirm */
  status: string;
  /** Post-event attendance mark, once the organizer marked it; null before marking. */
  attendance: 'attended' | 'absent' | 'disputed' | null;
  /** Optional note the participant left when disputing (shown to the organizer). */
  disputeNote?: string | null;
}

/**
 * F5-04: the caller's OWN attendance state (GET /api/events/{id}/my-attendance).
 * Readable without club membership so a participant who left the club still reaches the
 * dispute UI from the deep-link DM. [canDispute] is computed server-side (window open AND
 * attendance=absent AND not yet terminal).
 */
export interface MyAttendanceDto {
  attendance: 'attended' | 'absent' | 'disputed' | null;
  attendanceMarked: boolean;
  attendanceFinalized: boolean;
  /** True once the organizer (or ATT-2) resolved the dispute — no re-dispute (F5-16). */
  disputeTerminal: boolean;
  canDispute: boolean;
  disputeNote: string | null;
}

export type SkladchinaMode = 'fixed_equal' | 'fixed_individual' | 'voluntary';
export type SkladchinaTemplate = 'custom' | 'split_bill' | 'gear' | 'booking' | 'birthday';
export type SkladchinaStatus = 'active' | 'closed_success' | 'closed_failed' | 'cancelled';
export type SkladchinaParticipantStatus =
  | 'pending'
  | 'paid'
  | 'declined'
  | 'expired_no_response'
  // Skladchina closed before the deadline while the participant was still pending:
  // no obligation was broken, no reputation entry is emitted.
  | 'released';

export interface SkladchinaParticipantDto {
  userId: string;
  firstName: string;
  lastName: string | null;
  avatarUrl: string | null;
  expectedAmountKopecks: number | null;
  declaredAmountKopecks: number | null;
  status: SkladchinaParticipantStatus;
  paidAt: string | null;
  // V28 decline-with-approval (organizer view)
  declineRequested: boolean;
  declineNote: string | null;
  declineRejected: boolean;
  declineRejectNote: string | null;       // V29: organizer's reason if the decline was rejected
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
  template: SkladchinaTemplate;
  eventId: string | null;
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
  // V28 decline-with-approval
  declineRequiresApproval: boolean;
  myDeclineRequested: boolean;
  myDeclineRejected: boolean;
  myDeclineRejectNote: string | null;     // V29: organizer's reason for rejecting my decline
  participants: SkladchinaParticipantDto[] | null;
  participantCount: number;
  paidCount: number;
  pendingCount: number;                   // #3: lets the last pending participant see what's left
}

// State of the split linked to an event — drives the EventPage "Разделить счёт" button.
// skladchinaId null = no split yet (create); status active → open it; closed_success → already collected.
export interface EventSplitStateDto {
  skladchinaId: string | null;
  status: SkladchinaStatus | null;
}

export interface MySkladchinaListItemDto {
  id: string;
  title: string;
  clubId: string;
  clubName: string;
  clubAvatarUrl: string | null;
  template: SkladchinaTemplate;
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
  template?: SkladchinaTemplate;          // default "custom" server-side
  eventId?: string | null;                // split_bill: the source event
  excludeSelf?: boolean;                  // split_bill: drop the organizer from the charged attendees
  paymentMode: SkladchinaMode;
  totalGoalKopecks?: number | null;
  paymentLink: string;
  paymentMethodNote?: string | null;
  deadline: string;
  affectsReputation: boolean;
  participants?: CreateSkladchinaParticipantInput[];  // omitted/[] for split_bill
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
  myParticipationStatus: 'confirmed' | 'waitlisted' | 'declined' | 'expired_no_confirm' | null;
  goingCount: number;
  confirmedCount: number;
  participantLimit: number;
  actionRequired: boolean;
}

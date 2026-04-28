# Clubs 2.0 — Architecture Plan

## 1. API Contracts

### 1.1 Authentication

```
POST /api/auth/telegram
  Body: { initData: string }
  Response 200: { token: string, user: UserDto }
  Notes: Validates Telegram initData HMAC-SHA256, creates/updates user, returns JWT (24h TTL)
```

### 1.2 Users

```
GET /api/users/me
  Headers: Authorization: Bearer <JWT>
  Response 200: UserDto

GET /api/users/me/clubs
  Response 200: MembershipDto[]

GET /api/users/me/applications
  Response 200: ApplicationDto[]
```

### 1.3 Clubs

```
GET /api/clubs
  Query: ?category=sport&city=Москва&accessType=open&minPrice=0&maxPrice=1000&search=футбол&page=0&size=20
  Response 200: { content: ClubListItemDto[], totalPages: int, totalElements: long }
  Notes: Private clubs excluded. Sorted by activity_rating DESC

GET /api/clubs/{id}
  Response 200: ClubDetailDto

POST /api/clubs
  Headers: Authorization: Bearer <JWT>
  Body: CreateClubRequest
  Response 201: ClubDetailDto
  Errors: 400 (validation), 409 (limit 10 clubs per organizer)

PUT /api/clubs/{id}
  Headers: Authorization: Bearer <JWT>
  Body: UpdateClubRequest
  Response 200: ClubDetailDto
  Errors: 403 (not owner), 404

POST /api/clubs/{id}/regenerate-invite
  Response 200: { inviteLink: string }
  Errors: 403 (not owner)

POST /api/clubs/{id}/link-group
  Body: { telegramGroupId: long }
  Response 200: ClubDetailDto
  Errors: 403 (not owner)
```

### 1.4 Membership

```
POST /api/clubs/{id}/join
  Response 201: MembershipDto
  Errors: 400 (not open / limit reached), 409 (already member)

POST /api/clubs/{id}/apply
  Body: { answerText: string }
  Response 201: ApplicationDto
  Errors: 400 (not closed club / missing answer), 409 (already applied), 429 (5/day limit)

GET /api/clubs/{id}/applications
  Response 200: ApplicationDto[]
  Errors: 403 (not organizer)

POST /api/applications/{id}/approve
  Response 200: { application: ApplicationDto, membership: MembershipDto }
  Errors: 403 (not organizer), 404, 409 (already resolved)

POST /api/applications/{id}/reject
  Body: { reason?: string }
  Response 200: ApplicationDto
  Errors: 403, 404, 409

POST /api/memberships/{id}/cancel
  Response 200: MembershipDto
  Notes: Status → cancelled, access until subscription_expires_at
```

### 1.5 Invites (Private clubs)

```
GET /api/invite/{code}
  Response 200: ClubDetailDto
  Errors: 404 (invalid code)

POST /api/invite/{code}/join
  Response 201: MembershipDto
  Errors: 400 (limit), 409 (already member)
```

### 1.6 Members

```
GET /api/clubs/{id}/members
  Response 200: ClubMemberDto[]
  Errors: 403 (not member)

GET /api/clubs/{id}/members/{userId}
  Response 200: MemberProfileDto (with reputation + attendance history)
  Errors: 403 (not member)

GET /api/clubs/{id}/members/{userId}/reputation
  Response 200: ReputationDto
```

### 1.7 Events

```
POST /api/clubs/{id}/events
  Body: CreateEventRequest
  Response 201: EventDto
  Errors: 403 (not organizer), 400 (validation)

GET /api/clubs/{id}/events
  Query: ?status=upcoming|completed&page=0&size=20
  Response 200: { content: EventListItemDto[], totalPages, totalElements }
  Errors: 403 (not active member; also returned for non-existent clubs — privacy)

GET /api/events/{id}
  Response 200: EventDetailDto
  Notes: Includes goingCount, maybeCount, notGoingCount, confirmedCount, myVote, myStage2Status

POST /api/events/{id}/vote
  Body: { status: "going" | "maybe" | "not_going" }
  Response 200: EventDetailDto
  Errors: 403 (not member), 400 (stage 2 already started)

POST /api/events/{id}/confirm
  Response 200: EventDetailDto
  Notes: FIFO — confirmed if within participant_limit, else waitlisted
  Errors: 403, 400 (not stage 2 / not eligible)

POST /api/events/{id}/decline
  Response 200: EventDetailDto
  Notes: Auto-promotes first waitlisted participant

POST /api/events/{id}/attendance
  Body: [ { userId: UUID, attended: boolean } ]
  Response 200: EventDetailDto
  Errors: 403 (not organizer), 400 (event not completed)

POST /api/events/{id}/dispute
  Response 200: { status: "disputed" }
  Errors: 400 (window closed / not marked absent)

POST /api/events/{id}/attendance/{userId}/resolve
  Body: { attended: boolean }
  Response 200: EventDetailDto
  Errors: 403, 400 (finalized)
```

### 1.8 Finances

```
GET /api/clubs/{id}/finances
  Response 200: FinancesDto
  Errors: 403 (not organizer)
```

### 1.9 Upload

```
POST /api/upload
  Body: multipart/form-data (file)
  Response 200: { url: string }
  Errors: 400 (>5MB, wrong format)
```

---

## 2. DTO Definitions

### UserDto
```json
{
  "id": "uuid",
  "telegramId": 123456789,
  "telegramUsername": "username",
  "firstName": "Ivan",
  "lastName": "Varlamov",
  "avatarUrl": "https://...",
  "city": "Москва",
  "createdAt": "2026-03-20T12:00:00Z"
}
```

### ClubListItemDto
```json
{
  "id": "uuid",
  "name": "Футбол по четвергам",
  "category": "sport",
  "accessType": "open",
  "city": "Москва",
  "subscriptionPrice": 500,
  "memberCount": 25,
  "memberLimit": 40,
  "avatarUrl": "https://...",
  "tags": ["new", "free_spots"],
  "nearestEvent": {
    "title": "Игра в Лужниках",
    "eventDatetime": "2026-03-25T19:00:00Z",
    "goingCount": 12
  }
}
```

### ClubDetailDto
```json
{
  "id": "uuid",
  "ownerId": "uuid",
  "name": "Футбол по четвергам",
  "description": "Играем каждый четверг...",
  "category": "sport",
  "accessType": "open",
  "city": "Москва",
  "district": "ЦАО",
  "memberLimit": 40,
  "subscriptionPrice": 500,
  "avatarUrl": "https://...",
  "rules": "1. Приходи вовремя...",
  "applicationQuestion": null,
  "memberCount": 25,
  "inviteLink": "invite_abc123",
  "tags": ["new"],
  "isMember": false,
  "isOrganizer": false,
  "nearestEvent": { ... },
  "createdAt": "2026-03-01T10:00:00Z"
}
```

### CreateClubRequest
```json
{
  "name": "string (1-60)",
  "description": "string (1-500)",
  "category": "sport|creative|food|board_games|cinema|education|travel|other",
  "accessType": "open|closed|private",
  "city": "string",
  "district": "string?",
  "memberLimit": "int (10-80)",
  "subscriptionPrice": "int (>=0)",
  "avatarUrl": "string?",
  "rules": "string? (0-2000)",
  "applicationQuestion": "string? (0-200)"
}
```

### EventDetailDto
```json
{
  "id": "uuid",
  "clubId": "uuid",
  "title": "Игра в Лужниках",
  "description": "...",
  "locationText": "Лужники, поле 3",
  "eventDatetime": "2026-03-25T19:00:00Z",
  "participantLimit": 15,
  "votingOpensDaysBefore": 5,
  "status": "stage_1",
  "goingCount": 12,
  "maybeCount": 5,
  "notGoingCount": 3,
  "confirmedCount": 0,
  "waitlistedCount": 0,
  "myVote": "going",
  "myStage2Status": null,
  "attendanceMarked": false,
  "attendanceFinalized": false,
  "participants": [ ... ],
  "createdAt": "2026-03-20T10:00:00Z"
}
```

### CreateEventRequest
```json
{
  "title": "string (1-255)",
  "description": "string?",
  "locationText": "string (1-500)",
  "eventDatetime": "ISO datetime (future)",
  "participantLimit": "int (>0)",
  "votingOpensDaysBefore": "int (1-14, default 5)"
}
```

### MembershipDto
```json
{
  "id": "uuid",
  "userId": "uuid",
  "clubId": "uuid",
  "status": "active|grace_period|cancelled|expired",
  "role": "member|organizer",
  "joinedAt": "2026-03-20T12:00:00Z",
  "subscriptionExpiresAt": "2026-04-20T12:00:00Z"
}
```

### ApplicationDto
```json
{
  "id": "uuid",
  "userId": "uuid",
  "clubId": "uuid",
  "clubName": "string",
  "answerText": "string",
  "status": "pending|approved|rejected|auto_rejected",
  "rejectedReason": "string?",
  "createdAt": "2026-03-20T12:00:00Z",
  "resolvedAt": null
}
```

### ReputationDto
```json
{
  "userId": "uuid",
  "clubId": "uuid",
  "reliabilityIndex": 150,
  "promiseFulfillmentPct": 87.5,
  "totalConfirmations": 8,
  "totalAttendances": 7,
  "spontaneityCount": 2
}
```

### ClubMemberDto
```json
{
  "userId": "uuid",
  "firstName": "string",
  "lastName": "string",
  "avatarUrl": "string?",
  "reliabilityIndex": 150,
  "promiseFulfillmentPct": 87.5,
  "joinedAt": "2026-03-01T10:00:00Z"
}
```

### FinancesDto
```json
{
  "activeMembers": 25,
  "monthlyRevenue": 12500,
  "organizerShare": 10000,
  "platformFee": 2500,
  "nextBillingDate": "2026-04-01T00:00:00Z"
}
```

---

## 3. Database Schema

All tables from PRD section 5.1. Enums created as PostgreSQL types. Full SQL in Flyway migrations.

### Enum Types
```sql
CREATE TYPE club_category AS ENUM ('sport', 'creative', 'food', 'board_games', 'cinema', 'education', 'travel', 'other');
CREATE TYPE access_type AS ENUM ('open', 'closed', 'private');
CREATE TYPE membership_status AS ENUM ('active', 'grace_period', 'cancelled', 'expired');
CREATE TYPE membership_role AS ENUM ('member', 'organizer');
CREATE TYPE application_status AS ENUM ('pending', 'approved', 'rejected', 'auto_rejected');
CREATE TYPE event_status AS ENUM ('upcoming', 'stage_1', 'stage_2', 'completed', 'cancelled');
CREATE TYPE stage_1_vote AS ENUM ('going', 'maybe', 'not_going');
CREATE TYPE stage_2_vote AS ENUM ('confirmed', 'declined', 'waitlisted');
CREATE TYPE final_status AS ENUM ('confirmed', 'waitlisted', 'declined');
CREATE TYPE attendance_status AS ENUM ('attended', 'absent', 'disputed');
CREATE TYPE transaction_type AS ENUM ('subscription', 'renewal');
CREATE TYPE transaction_status AS ENUM ('completed', 'failed', 'refunded');
```

### Indexes (beyond PK/FK/UNIQUE)
```sql
-- clubs: discovery queries
CREATE INDEX idx_clubs_category ON clubs(category);
CREATE INDEX idx_clubs_city ON clubs(city);
CREATE INDEX idx_clubs_access_type ON clubs(access_type);
CREATE INDEX idx_clubs_activity_rating ON clubs(activity_rating DESC);
CREATE INDEX idx_clubs_owner_id ON clubs(owner_id);

-- memberships: lookup
CREATE INDEX idx_memberships_user_id ON memberships(user_id);
CREATE INDEX idx_memberships_club_id ON memberships(club_id);
CREATE INDEX idx_memberships_status ON memberships(status);

-- events: club calendar
CREATE INDEX idx_events_club_id_datetime ON events(club_id, event_datetime DESC);
CREATE INDEX idx_events_status ON events(status);

-- event_responses: voting queries
CREATE INDEX idx_event_responses_event_id ON event_responses(event_id);

-- applications: pending lookup
CREATE INDEX idx_applications_club_id_status ON applications(club_id, status);
CREATE INDEX idx_applications_user_id ON applications(user_id);

-- transactions: finance aggregation
CREATE INDEX idx_transactions_club_id_created ON transactions(club_id, created_at);
```

---

## 4. Backend Package Structure

```
backend/src/main/kotlin/com/clubs/
├── ClubsApplication.kt                    # @SpringBootApplication
├── config/
│   ├── SecurityConfig.kt                  # Spring Security: JWT filter chain
│   ├── JooqConfig.kt                      # jOOQ DSLContext bean
│   ├── RedisConfig.kt                     # Lettuce connection
│   ├── S3Config.kt                        # S3 client bean
│   ├── WebConfig.kt                       # CORS, serialization
│   └── SchedulerConfig.kt                 # @EnableScheduling
├── auth/
│   ├── AuthController.kt                  # POST /api/auth/telegram
│   ├── TelegramInitDataValidator.kt       # HMAC-SHA256 validation
│   ├── JwtService.kt                      # generate/parse JWT (jjwt)
│   ├── JwtAuthenticationFilter.kt         # OncePerRequestFilter
│   └── AuthenticatedUser.kt              # Principal data class
├── common/
│   ├── exception/
│   │   ├── NotFoundException.kt
│   │   ├── ConflictException.kt
│   │   ├── ValidationException.kt
│   │   └── GlobalExceptionHandler.kt     # @RestControllerAdvice
│   ├── dto/
│   │   └── PageResponse.kt               # Generic page wrapper
│   └── security/
│       ├── RequiresMembership.kt          # Custom annotation
│       ├── RequiresOrganizer.kt           # Custom annotation
│       └── ClubAuthorizationAspect.kt     # AOP aspect for role checks
├── user/
│   ├── UserController.kt                  # /api/users/me, /me/clubs, /me/applications
│   ├── UserService.kt
│   ├── UserRepository.kt                 # jOOQ queries
│   └── dto/
│       └── UserDto.kt
├── club/
│   ├── ClubController.kt                  # CRUD + /join, /apply
│   ├── ClubService.kt
│   ├── ClubRepository.kt
│   └── dto/
│       ├── ClubListItemDto.kt
│       ├── ClubDetailDto.kt
│       ├── CreateClubRequest.kt
│       └── UpdateClubRequest.kt
├── membership/
│   ├── MembershipController.kt            # /cancel
│   ├── MembershipService.kt              # joinOpenClub, createFromApproval
│   ├── MembershipRepository.kt
│   └── dto/
│       ├── MembershipDto.kt
│       └── ClubMemberDto.kt
├── application/
│   ├── ApplicationController.kt          # /approve, /reject
│   ├── ApplicationService.kt
│   ├── ApplicationRepository.kt
│   └── dto/
│       └── ApplicationDto.kt
├── invite/
│   ├── InviteController.kt               # GET /api/invite/{code}, POST /join
│   └── InviteService.kt
├── event/
│   ├── EventController.kt                # CRUD + /vote, /confirm, /decline
│   ├── EventService.kt
│   ├── EventRepository.kt
│   ├── EventResponseRepository.kt
│   └── dto/
│       ├── EventDetailDto.kt
│       ├── EventListItemDto.kt
│       ├── CreateEventRequest.kt
│       └── VoteRequest.kt
├── attendance/
│   ├── AttendanceController.kt           # /attendance, /dispute, /resolve
│   ├── AttendanceService.kt
│   └── dto/
│       └── AttendanceRequest.kt
├── reputation/
│   ├── ReputationService.kt              # calculateReputation after finalization
│   ├── ReputationRepository.kt
│   └── dto/
│       └── ReputationDto.kt
├── payment/
│   ├── PaymentController.kt              # Telegram Stars webhook
│   ├── PaymentService.kt                 # createInvoice, processPayment
│   ├── TransactionRepository.kt
│   └── dto/
│       └── FinancesDto.kt
├── notification/
│   ├── NotificationService.kt            # sendToUser, sendToGroup (via Redis queue)
│   └── NotificationConsumer.kt           # Redis subscriber → Bot API
├── bot/
│   ├── BotConfig.kt                      # Bot registration, webhook/polling
│   ├── BotCommandHandler.kt              # /start, /кто_идет, /мой_рейтинг, /события
│   ├── BotNotificationSender.kt          # Send messages via Bot API
│   └── GroupManagementService.kt         # Create group, generate invite links
├── storage/
│   ├── StorageController.kt              # POST /api/upload
│   └── StorageService.kt                 # S3 upload/delete
└── scheduler/
    ├── Stage2TriggerJob.kt               # Every 5 min: check events for stage 2
    ├── ApplicationAutoRejectJob.kt       # Every hour: reject 48h+ pending applications
    ├── AttendanceFinalizeJob.kt          # Every hour: finalize 48h+ attendance
    └── SubscriptionCheckJob.kt           # Daily: check expiring subscriptions
```

---

## 5. Frontend Structure

```
frontend/src/
├── main.tsx                               # Entry point
├── App.tsx                                # AppRoot, BrowserRouter, layout
├── router.tsx                             # Route definitions
├── vite-env.d.ts
├── telegram/
│   ├── sdk.ts                             # Telegram Mini App SDK init
│   └── DeepLinkHandler.tsx                # Parse startParam → redirect to /invite/{code}
├── api/
│   ├── apiClient.ts                       # fetch wrapper with JWT auth
│   ├── clubs.ts                           # getClubs, getClub, createClub, updateClub
│   ├── events.ts                          # getClubEvents, getEvent, createEvent, vote, confirm
│   ├── membership.ts                      # joinClub, applyToClub, cancelMembership
│   ├── applications.ts                    # getApplications, approve, reject
│   ├── users.ts                           # getMe, getMyClubs, getMyApplications
│   ├── finances.ts                        # getFinances
│   └── upload.ts                          # uploadFile
├── store/
│   ├── useAuthStore.ts                    # JWT token, user, isAuthenticated
│   ├── useClubsStore.ts                   # clubs[], myClubs[], loading, error
│   ├── useEventsStore.ts                  # events[], loading
│   └── useUserStore.ts                    # currentUser, myApplications
├── components/
│   ├── BottomTabBar.tsx                   # 4 tabs: Discovery, My Clubs, Organizer, Profile
│   ├── ClubCard.tsx                       # Card for discovery feed
│   ├── EventCard.tsx                      # Card for event list
│   ├── MemberCard.tsx                     # Card for member list
│   ├── ReputationBadge.tsx                # 3 metrics display
│   ├── TagBadge.tsx                       # Promo tag chip
│   ├── VoteButtons.tsx                    # Going / Maybe / Not going
│   ├── ConfirmButton.tsx                  # Stage 2 confirm/decline
│   ├── FilterBar.tsx                      # Category chips + search
│   ├── IncomeCalculator.tsx               # Revenue calculator for club creation
│   ├── AttendanceToggle.tsx               # Attended/Absent toggle for organizer
│   └── Skeleton.tsx                       # Loading skeleton
├── pages/
│   ├── DiscoveryPage.tsx                  # / — club feed with filters
│   ├── ClubPage.tsx                       # /clubs/:id — club detail + join
│   ├── ClubInteriorPage.tsx               # /clubs/:id/interior — events, members, profile tabs
│   ├── EventPage.tsx                      # /events/:id — event detail + voting
│   ├── MyClubsPage.tsx                    # /my-clubs — user's clubs + applications
│   ├── OrganizerPage.tsx                  # /organizer — create club + list own clubs
│   ├── OrganizerClubManagePage.tsx        # /organizer/clubs/:id — manage club dashboard
│   ├── ProfilePage.tsx                    # /profile — user profile
│   └── InvitePage.tsx                     # /invite/:code — private club join
└── hooks/
    ├── useBackButton.ts                   # Telegram BackButton show/hide
    ├── useMainButton.ts                   # Telegram MainButton helper
    └── usePagination.ts                   # Infinite scroll helper
```

### 5.1 Frontend Routes

| Path | Page | Tab | Auth | Notes |
|------|------|-----|------|-------|
| `/` | DiscoveryPage | Discovery | Yes | Default route |
| `/clubs/:id` | ClubPage | — | Yes | Club detail, BackButton |
| `/clubs/:id/interior` | ClubInteriorPage | — | Yes (member) | Interior tabs |
| `/events/:id` | EventPage | — | Yes (member) | Event detail |
| `/my-clubs` | MyClubsPage | My Clubs | Yes | User's clubs |
| `/organizer` | OrganizerPage | Organizer | Yes | Create & list clubs |
| `/organizer/clubs/:id` | OrganizerClubManagePage | — | Yes (organizer) | Manage club |
| `/profile` | ProfilePage | Profile | Yes | User profile |
| `/invite/:code` | InvitePage | — | Yes | Private club join |

---

## 6. Scheduled Jobs

| Job | Frequency | Logic |
|-----|-----------|-------|
| Stage2TriggerJob | Every 5 min | Find events where `event_datetime - 24h <= now` AND `stage_2_triggered = false`. Trigger stage 2 flow. |
| ApplicationAutoRejectJob | Every 1 hour | Find applications where `status = pending` AND `created_at + 48h < now`. Set `auto_rejected`, decrease club `activity_rating` by 5. |
| AttendanceFinalizeJob | Every 1 hour | Find events where `attendance_marked = true` AND `updated_at + 48h < now` AND `attendance_finalized = false`. Finalize attendance, trigger reputation calculation. |
| SubscriptionCheckJob | Daily at 00:00 | Find memberships where `subscription_expires_at <= now + 3d`. Send reminder. If expired: attempt renewal or set `grace_period`. After grace period: set `expired`. |

---

## 7. Key Technical Decisions

1. **jOOQ over JPA** — typesafe SQL, no hidden magic, explicit queries
2. **JWT in memory (frontend)** — not localStorage, refreshed on 401
3. **Redis for notifications queue** — decouple API from Bot API rate limits
4. **FIFO for Stage 2** — `stage_2_timestamp` determines order, first N confirmed
5. **Reputation per club** — `user_club_reputation` table, recalculated on finalization
6. **Promo tags computed on read** — no stored tags, calculated in SQL query based on rules
7. **Invite codes** — UUID-based, stored in `clubs.invite_link`, regeneratable
8. **`--legacy-peer-deps`** — required for npm install due to telegram-ui + React 19 conflict
9. **Bot: polling (dev) / webhook (prod)** — configured via Spring profile

---

## 8. Critical Path

```
TASK-001 (backend init) ──┬── TASK-003 (migrations) ── TASK-004 (jOOQ) ── TASK-005 (auth)
TASK-002 (docker compose) ┘                                                    │
                                                              ┌────────────────┼────────────────┐
                                                         TASK-008          TASK-044          TASK-006
                                                        (clubs CRUD)    (authorization)   (rate limit)
                                                         │    │    │
                                                    TASK-009  TASK-010  TASK-013
                                                   (catalog) (join)   (events)
                                                              │         │
                                                         TASK-011  TASK-014
                                                        (applications) (voting)
                                                              │         │
                                                         TASK-012  TASK-015
                                                        (invites) (stage 2)
                                                                    │
                                                               TASK-016 (attendance)
                                                                    │
                                                               TASK-017 (dispute)
                                                                    │
                                                               TASK-018 (reputation)

TASK-023 (frontend init) ── TASK-025 (routing) ── TASK-024 (SDK + apiClient) ── TASK-038 (stores)
                                                       │
                                              ┌────────┼────────┐
                                         TASK-026  TASK-030  TASK-032
                                        (discovery)(organizer)(my clubs)
                                              │        │
                                         TASK-027  TASK-031
                                        (club page)(org dashboard)
                                              │
                                         TASK-028 (interior) ── TASK-029 (event page)
```

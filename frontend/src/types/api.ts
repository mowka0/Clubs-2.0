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
  // P1b Trust 0-100. null = «Новичок» (ещё нет трек-рекорда, либо владелец в своём клубе — по `role`
  // рендерится организаторская подача). При null весь блок репутации скрывается.
  trust: number | null;
  promiseFulfillmentPct: number | null;
  // Подтверждения stage-2 на текущий момент. Строка «Обещания X%» показывается только при значении > 0,
  // чтобы участник «только финансы» (трек по складчинам, без событий) не видел обманчивые 0% (F5-08).
  totalConfirmations: number | null;
  // Клубные награды (member admin S2) — видны всем участникам (R3), показываются чипами на карточке
  // ростера и в профиле. Косметика; никогда не отражают репутацию (R4). Пустой массив, если наград нет.
  awards: AwardDto[];
  // De-Stars Slice 2 — только для дашборда организатора (null для обычных участников): состояние доступа
  // и дата, до которой оплачено. Определяют корзины «Доступ истёк» / «Скоро закончится» /
  // «Оплата вступления» / «Активные». 'frozen' = новый участник ждёт первого взноса; 'expired' =
  // просрочил продление (должник). `subscriptionExpiresAt` также null для бесплатных членств (без срока).
  accessStatus?: 'active' | 'frozen' | 'expired' | null;
  subscriptionExpiresAt?: string | null;
  // Только для дашборда организатора: заявление участника об оплате взноса (null = нет) + способ
  // ("sbp"|"cash"), чтобы корзина «Ждут оплаты» могла пометить «оплата заявлена».
  duesClaimedAt?: string | null;
  duesClaimMethod?: string | null;
}

/** Карточка доверия организатора для шита оплаты взноса (de-Stars). Про аккаунт в целом; UI скрывает
 *  ещё не значимые факты (clubsCount < 2, trustedMembers ниже порога) → у новых аккаунтов нет нулей. */
export interface OrganizerCardDto {
  firstName: string;
  lastName: string | null;
  username: string | null;
  avatarUrl: string | null;
  onPlatformSince: string;
  clubsCount: number;
  trustedMembers: number;
}

/** Чип клубной награды (member admin S2). Косметика; никогда не отражает репутацию (R4). */
export interface AwardDto {
  id: string;
  emoji: string;
  label: string;
}

/** Вариант автодополнения в форме выдачи — уже использованная в клубе пара (emoji, label). Без id. */
export interface AwardSuggestionDto {
  emoji: string;
  label: string;
}

export interface MemberProfileDto {
  userId: string;
  clubId: string;
  firstName: string;
  username: string | null;
  avatarUrl: string | null;
  // Публичные поля профиля, видны каждому участнику клуба на карточке участника.
  bio: string | null;
  interests: string[];
  // Member admin S2 — клубные награды, видны ВСЕМ участникам (R3); чисто косметика (R4).
  awards: AwardDto[];
  // "organizer" = владелец клуба. Определяет организаторскую подачу, когда trust = null.
  role: string;
  // P1b Trust 0-100. null = «Новичок»/скрыто (ещё нет трек-рекорда, либо владелец в своём клубе).
  trust: number | null;
  promiseFulfillmentPct: number | null;
  totalConfirmations: number | null;
  totalAttendances: number | null;
  // «Возможно → Подтвердил → Пришёл»: пришёл, хотя изначально отвечал «возможно».
  spontaneityCount: number | null;
  // Влияющий на репутацию трек складчин в этом клубе: оплачено / (оплачено + просрочено).
  // null, если скрыто; кольцо «Сборы» скрывается, когда skladchinaTotal === 0.
  skladchinaPaid: number | null;
  skladchinaTotal: number | null;
  // De-Stars Slice 2 — ТОЛЬКО ДЛЯ ОРГАНИЗАТОРА (null для обычных участников): когда заканчивается
  // оплаченный доступ этого участника. Также null для бесплатных членств. Показывается как
  // «Подписка активна до …» на карточке организатора.
  subscriptionExpiresAt: string | null;
  // Member admin S1 — ТОЛЬКО ДЛЯ ОРГАНИЗАТОРА (null для обычных участников): приватная заметка организатора.
  organizerNote: string | null;
  // De-Stars заявление об оплате взноса — ТОЛЬКО ДЛЯ ОРГАНИЗАТОРА (null для обычных участников): когда
  // участник заявил об оплате, способ ("sbp"|"cash") и URL скриншота (только для sbp). Проверяется до
  // отметки «Взнос получен».
  duesClaimedAt: string | null;
  duesClaimMethod: string | null;
  duesProofUrl: string | null;
  // Ответ участника на вопрос при вступлении (закрытые клубы) — ТОЛЬКО ДЛЯ ОРГАНИЗАТОРА. null для
  // открытых клубов / когда вопроса нет.
  applicationAnswer: string | null;
}

/**
 * Участник без доступа в одном из клубов, которыми владеет вызывающий: frozen (вступил, первый взнос
 * не подтверждён) или expired (просрочил продление). Питает кросс-клубовую секцию «Ждут оплаты» на
 * «Мои клубы». `subscriptionExpiresAt` — истёкший период (null для первого вступления без оплаты);
 * `joinedAt` формирует строку «вступил(а) N назад».
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
  // Флаг заявления об оплате для кросс-клубового списка «Ждут оплаты»: «оплата заявлена» + способ ("sbp"|"cash").
  duesClaimedAt: string | null;
  duesClaimMethod: string | null;
  // 'frozen' (первый взнос) | 'expired' (просрочка продления) | 'active' (раннее продление —
  // попадает в ленту только с claim) — тексты и действия карточки различаются.
  accessStatus: 'frozen' | 'expired' | 'active';
}

export interface UserClubReputationDto {
  clubId: string;
  clubName: string;
  clubAvatarUrl: string | null;
  category: string;
  role: string;
  joinedAt: string | null;
  // P1b Trust 0-100. null = «Новичок»/скрыто (ещё нет трек-рекорда, либо владелец в своём клубе —
  // `role` = "organizer" рендерит организаторскую подачу).
  trust: number | null;
  promiseFulfillmentPct: number | null;
  totalConfirmations: number | null;
  totalAttendances: number | null;
  spontaneityCount: number | null;
  // «Путь назад» (reputation-path-back.md): Trust после +1 / +2 посещений — проекция той же формулы
  // на бэке. null = не показывается (trust скрыт, ИЛИ trust >= 70 — просадки нет, ИЛИ клуб в «Истории»).
  projectedNext1: number | null;
  projectedNext2: number | null;
  // Посещений до надёжной зоны (>= 70); cap 9 — UI пишет «9+». null по тем же правилам.
  meetingsToReliable: number | null;
  // Кольцо «Сборы»: оплачено / (оплачено+просрочено) репутационных складчин. total 0 → кольца нет.
  skladchinaPaid: number | null;
  skladchinaTotal: number | null;
  // Ближайшее предстоящее событие клуба — CTA «Ближайшая встреча» в раскрытой карточке «Моих клубов».
  nearestEvent: NearestEventDto | null;
  // Клубные награды вызывающего в этом клубе — чипы в раскрытой карточке (косметика, R3).
  awards: AwardDto[];
}

/** Глобальная репутация: основной показатель — «надёжен в reliableClubs из trackRecordClubs клубов». */
export interface GlobalTrustDto {
  reliableClubs: number;
  trackRecordClubs: number;
  // Вторичное число 0-100; null, если трек-рекорда нигде нет (trackRecordClubs === 0).
  score: number | null;
}

/** Обзор репутации авторизованного пользователя: глобальный агрегат + списки по клубам. */
export interface MyReputationDto {
  global: GlobalTrustDto;
  activeClubs: UserClubReputationDto[];
  // Клубы, которые пользователь покинул, но трек-рекорд в них ещё сохраняется («История»).
  historyClubs: UserClubReputationDto[];
}

/**
 * Предпросмотр перед выходом для диалога «Выйти из клуба?»: сколько открытых обязательств
 * будет нарушено при выходе из бесплатного клуба (и, соответственно, снизит надёжность
 * пользователя). Только счётчики — величины штрафов остаются на сервере. В платных клубах
 * ничего не нарушается (все нули).
 */
export interface LeavePreviewDto {
  eventObligations: number;
  skladchinaObligations: number;
  totalObligations: number;
}

/** Геймификационный бейдж, полученный за прохождение порога. */
export interface BadgeDto {
  id: string;
  name: string;
  family: string;
}

/**
 * Панель геймификации авторизованного пользователя (вид `self`): точный XP, текущий уровень +
 * прогресс до следующего, полученные бейджи. XP начисляется только за участие и только растёт.
 * См. reputation-v2.md §H3.
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
  // Кросс-клубовая репутация для карточки отзыва: «надёжен в reliableClubs из trackRecordClubs клубов».
  reliableClubs: number;
  trackRecordClubs: number;
  // Глобальный уровень геймификации (проекция для других) для пилла.
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
 * Счётчик, питающий точку на табе «Мои клубы»: ожидающие заявки на стороне организатора
 * в кросс-клубовом инбоксе. Один эндпоинт, один слот кэша.
 * См. docs/modules/applications-inbox.md.
 */
export interface PendingApplicationsCountDto {
  inboxCount: number;
  // De-Stars: замороженные участники, заявившие об оплате взноса (оплачено, ждёт решения
  // организатора) по всем клубам вызывающего. Зажигает точку навигации «Мои клубы» наравне с инбоксом заявок.
  awaitingDuesCount: number;
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
  // Реквизиты для оплаты взноса по СБП — заполнены только для участников клуба (active/frozen) + владельца;
  // иначе null. Внеплатформенные платёжные данные организатора, показываются как «Оплатить по СБП»
  // участникам, которые должны взнос.
  paymentLink: string | null;
  paymentMethodNote: string | null;
  // Чат-интеграция (club-chat-link): чат привязан и бот в нём жив — гость видит чип «у клуба есть чат».
  chatLinked: boolean;
  // Включён «вход в чат через заявки» (дверь).
  chatDoorEnabled: boolean;
  // Door-ссылка для кнопки «Чат клуба» — только участникам с доступом + владельцу; иначе null.
  chatInviteLink: string | null;
}

/**
 * Статус привязки телеграм-чата — `GET /api/clubs/{id}/chat-link` (только владелец).
 * botStatus: administrator | member | left | kicked; null пока чат не привязан.
 */
export interface ChatLinkStatusDto {
  linked: boolean;
  chatTitle: string | null;
  linkedAt: string | null;
  botStatus: string | null;
  canPinMessages: boolean;
  canInviteUsers: boolean;
  // Право бота «Блокировка пользователей» — гейт тумблера «Строгий режим» (слайс 5).
  canRestrictMembers: boolean;
  // Право бота «Назначение администраторов» — гейт тумблера «Титулы наград» (слайс 4).
  canPromoteMembers: boolean;
  doorEnabled: boolean;
  doorInviteLink: string | null;
  // Тумблер «Живой закреп» (слайс 3): бот ведёт закреплённый статус событий в чате.
  livePinEnabled: boolean;
  // Тумблер «Статус сборов в чате» (слайс 3.5): живой пост прогресса складчин с упоминаниями.
  skladchinaStatusEnabled: boolean;
  // Тумблер «Строгий режим» (слайс 5): должники — только чтение, покинувшие клуб — бан.
  strictModeEnabled: boolean;
  // Тумблер «Титулы наград» (слайс 4): последняя награда участника — титул рядом с именем в чате.
  awardTitlesEnabled: boolean;
  // Deep link ?startgroup= для кнопки «Привязать чат» (username бота живёт на сервере).
  startGroupUrl: string;
}

/** Частичный PATCH тумблеров чата: меняются только присланные поля. */
export interface UpdateChatLinkRequest {
  doorEnabled?: boolean;
  livePinEnabled?: boolean;
  skladchinaStatusEnabled?: boolean;
  strictModeEnabled?: boolean;
  awardTitlesEnabled?: boolean;
}

/** Факты качества клуба для `GET /api/clubs/{id}/quality` (кольца + достижения). */
export interface ClubFactsDto {
  meetingsPerMonth: number;
  avgAttendance: number;
  coreSize: number;
  ageMonths: number;
  totalMeetings: number;
  successfulSkladchinas: number;
}

/** Факты качества для карточки в Discovery — `GET /api/clubs/quality/batch` (по одному на клуб). */
export interface ClubCardFactsDto {
  clubId: string;
  ageDays: number;
  engagementPercent: number;
  /** «★ Топ-5 в категории» — единственный внешне видимый сигнал L3 (чистый boolean; сам rank score
   *  никогда не покидает сервер). False, пока бэкендовый feature flag бейджа выключен. */
  topInCategory: boolean;
}

/** Изменение процентной метрики от периода к периоду. `delta` со знаком, в процентных пунктах. */
export interface TrendDto {
  direction: 'up' | 'down' | 'flat';
  delta: number;
}

/** Бывший участник, которого стоит вернуть — `GET /api/clubs/{clubId}/churned-members` (только владелец, §9.5). */
export interface ChurnedMemberDto {
  userId: string;
  firstName: string;
  lastName: string | null;
  avatarUrl: string | null;
  leftAt: string;
}

/**
 * Статистика клуба только для владельца — `GET /api/clubs/{id}/stats` (§9 docs/modules/club-quality.md).
 * Nullable-поля не применимы к данному клубу: `retention*` только для платных, `skladchinaPaid*`
 * требует закрытых складчин, а поля заявок null, пока клуб не `closed`.
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
  // Собственное заявление участника об оплате (de-Stars): когда заявил (null = не заявлял) + способ ("sbp"|"cash").
  // Управляет «оплата на проверке» на экране замороженного клуба.
  duesClaimedAt?: string | null;
  duesClaimMethod?: string | null;
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
  // Крайний момент (ISO), до которого подтверждённый участник может отказаться от места. Считается
  // бэкендом из events.stage2-decline-cutoff-minutes — фронт не хранит копию порога, а прячет кнопку
  // «Отказаться» у confirmed, когда текущее время ≥ этого значения. Источник истины — бэкенд.
  confirmedDeclineDeadline: string;
  attendanceMarked: boolean;
  attendanceFinalized: boolean;
  // F5-14: необязательная причина отмены от организатора; null, если отменено без указания причины.
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
  /** Отметка посещения после события, как только организатор её проставил; null до отметки. */
  attendance: 'attended' | 'absent' | 'disputed' | null;
  /** Необязательная заметка участника при оспаривании (видна организатору). */
  disputeNote?: string | null;
}

/**
 * F5-04: СОБСТВЕННОЕ состояние посещения вызывающего (GET /api/events/{id}/my-attendance).
 * Доступно без членства в клубе, чтобы участник, покинувший клуб, всё ещё мог попасть в UI
 * оспаривания по deep-link из DM. [canDispute] вычисляется на сервере (окно открыто И
 * attendance=absent И спор ещё не завершён).
 */
export interface MyAttendanceDto {
  attendance: 'attended' | 'absent' | 'disputed' | null;
  attendanceMarked: boolean;
  attendanceFinalized: boolean;
  /** True, как только организатор (или ATT-2) разрешил спор — повторный спор невозможен (F5-16). */
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
  // Складчина закрыта до дедлайна, пока участник ещё был в статусе pending:
  // обязательство не было нарушено, запись в репутацию не создаётся.
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
  // V28 decline-with-approval (вид организатора)
  declineRequested: boolean;
  declineNote: string | null;
  declineRejected: boolean;
  declineRejectNote: string | null;       // V29: причина организатора, если отказ был отклонён
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
  // V28: отказ-с-подтверждением
  declineRequiresApproval: boolean;
  myDeclineRequested: boolean;
  myDeclineRejected: boolean;
  myDeclineRejectNote: string | null;     // V29: причина организатора, почему отклонил мой отказ
  participants: SkladchinaParticipantDto[] | null;
  participantCount: number;
  paidCount: number;
  pendingCount: number;                   // #3: позволяет последнему pending-участнику увидеть, что осталось
}

// Состояние сплита, привязанного к событию — управляет кнопкой «Разделить счёт» на EventPage.
// skladchinaId null = сплита ещё нет (создать); status active → открыть его; closed_success → уже собрано.
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
  template?: SkladchinaTemplate;          // по умолчанию "custom" на сервере
  eventId?: string | null;                // split_bill: исходное событие
  excludeSelf?: boolean;                  // split_bill: исключить организатора из тех, с кого берут деньги
  paymentMode: SkladchinaMode;
  totalGoalKopecks?: number | null;
  paymentLink: string;
  paymentMethodNote?: string | null;
  deadline: string;
  affectsReputation: boolean;
  participants?: CreateSkladchinaParticipantInput[];  // не передаётся/[] для split_bill
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

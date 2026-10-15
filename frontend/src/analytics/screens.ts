/**
 * Имена экранов для `screen_opened` (product-analytics.md § 3.4) — таблица маршрутов роутера.
 * При добавлении маршрута в `router.tsx` — дописать строку здесь; тест перебирает маршруты роутера
 * и падает на забытом. Идентификаторы сущностей не попадают в имя: клуб — в `clubId`, встреча и
 * сбор — в `props`; чужой userId и код приглашения не пишутся вовсе.
 */
export interface ScreenInfo {
  screen: string;
  clubId?: string;
  props?: Record<string, string>;
}

const UUID = '([0-9a-f-]{36})';

type Rule = [RegExp, (m: RegExpMatchArray) => ScreenInfo | null];

const RULES: Rule[] = [
  [/^\/$/, () => ({ screen: 'home' })],
  [/^\/discovery$/, () => ({ screen: 'discovery' })],
  [/^\/connect-chat$/, () => ({ screen: 'connect_chat' })],
  [/^\/my-clubs$/, () => ({ screen: 'my_clubs' })],
  [/^\/(activities|events|skladchina)$/, () => ({ screen: 'activities' })],
  [/^\/profile$/, () => ({ screen: 'profile' })],
  [new RegExp(`^/clubs/${UUID}$`, 'i'), (m) => ({ screen: 'club', clubId: m[1] })],
  [new RegExp(`^/clubs/${UUID}/setup$`, 'i'), (m) => ({ screen: 'club_setup', clubId: m[1] })],
  [new RegExp(`^/clubs/${UUID}/manage$`, 'i'), (m) => ({ screen: 'club_manage', clubId: m[1] })],
  [new RegExp(`^/clubs/${UUID}/events/new$`, 'i'), (m) => ({ screen: 'event_new', clubId: m[1] })],
  [new RegExp(`^/clubs/${UUID}/event-templates/[^/]+/edit$`, 'i'), (m) => ({ screen: 'event_template_edit', clubId: m[1] })],
  [new RegExp(`^/clubs/${UUID}/skladchina/new$`, 'i'), (m) => ({ screen: 'skladchina_new', clubId: m[1] })],
  [new RegExp(`^/clubs/${UUID}/join$`, 'i'), (m) => ({ screen: 'club_join', clubId: m[1] })],
  [new RegExp(`^/events/${UUID}$`, 'i'), (m) => ({ screen: 'event', props: { eventId: m[1] } })],
  [new RegExp(`^/skladchina/${UUID}$`, 'i'), (m) => ({ screen: 'skladchina', props: { skladchinaId: m[1] } })],
  [/^\/debts$/, () => ({ screen: 'debts' })],
  [/^\/debts\/with\/[^/]+$/, () => ({ screen: 'debt_pair' })],
  [/^\/invite\/[^/]+$/, () => ({ screen: 'invite' })],
  [/^\/feedback$/, () => ({ screen: 'feedback' })],
  // Редиректы мгновенны, страницы оплаты, лендинг и политика живут вне Layout — не экраны приложения.
  [/^\/organizer$/, () => null],
  [new RegExp(`^/clubs/${UUID}/interior$`, 'i'), () => null],
  [/^\/(pay\/return|pay\/fail|about|privacy)$/, () => null],
];

/** Экран по пути; null — путь не считается экраном; `unknown` — неизвестный путь (сам путь не пишется). */
export function resolveScreen(pathname: string): ScreenInfo | null {
  const path = pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname;
  for (const [pattern, build] of RULES) {
    const match = path.match(pattern);
    if (match) return build(match);
  }
  return { screen: 'unknown' };
}

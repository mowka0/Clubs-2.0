/**
 * Виды ссылок запуска `t.me/<bot>?startapp=<value>`. Разбор общий для `DeepLinkHandler` (куда вести)
 * и журнала событий (`app_opened.entry`, product-analytics.md § 3.2): два места не должны расходиться.
 */
export type DeepLinkKind = 'skladchina' | 'event' | 'club' | 'billing' | 'invite';

const UUID = '([0-9a-f-]{36})';

export const DEEP_LINK_RULES: readonly (readonly [DeepLinkKind, RegExp])[] = [
  ['skladchina', new RegExp(`^skladchina_${UUID}$`, 'i')],
  ['event', new RegExp(`^event_${UUID}$`, 'i')],
  ['club', new RegExp(`^club_${UUID}$`, 'i')],
  ['billing', new RegExp(`^billing_${UUID}$`, 'i')],
  // Инвайт-код — 16 hex-символов (ClubService.generateInviteCode); диапазон в regex
  // шире на случай будущей смены длины.
  ['invite', /^invite_([0-9a-f]{8,64})$/i],
];

/** Вид и захваченный идентификатор ссылки запуска; null — параметра нет или он чужой. */
export function parseDeepLink(startParam: string | null): { kind: DeepLinkKind; id: string } | null {
  if (!startParam) return null;
  for (const [kind, pattern] of DEEP_LINK_RULES) {
    const match = startParam.match(pattern);
    const id = match?.[1];
    if (id !== undefined) return { kind, id };
  }
  return null;
}

/** Вид ссылки запуска для `app_opened.entry`; без параметра или с чужим — `none`. */
export function deepLinkKind(startParam: string | null): DeepLinkKind | 'none' {
  return parseDeepLink(startParam)?.kind ?? 'none';
}

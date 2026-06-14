import type { PeerStatsDto } from '../../../types/api';

/**
 * Russian plural form picker: forms = [one, few, many].
 * Mirrors the helper in MyClubsPage but kept feature-local to avoid a circular
 * dependency on a page-level module from a leaf util.
 */
function pluralRu(n: number, forms: readonly [string, string, string]): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return forms[0];
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return forms[1];
  return forms[2];
}

/**
 * Build the peer-signal string shown in the organizer inbox for each
 * applicant. Three cases (see docs/modules/applications-inbox.md §
 * "Peer-signal — формула и edge cases"):
 *   - no clubs at all → "Новый пользователь"
 *   - has clubs, no Stage-2 confirmations yet → "В N клубе/клубах · ещё не было событий"
 *   - normal case → "В N клубе/клубах · посетил X из Y событий"
 */
export function formatPeerSignal(stats: PeerStatsDto): string {
  const { memberClubCount, totalConfirmations, totalAttendances } = stats;

  if (memberClubCount === 0) {
    return 'Новый пользователь';
  }

  // Предложный падеж: «в 1 клубе» (ед.), «в 2/5 клубах» (мн.).
  const clubsWord = pluralRu(memberClubCount, ['клубе', 'клубах', 'клубах']);

  if (totalConfirmations === 0) {
    return `В ${memberClubCount} ${clubsWord} · ещё не было событий`;
  }

  // "из {Y} <word>" — genitive: «события» for 1 (also 21, 31…), «событий»
  // for all other counts (включая 0, 2–20, и т.д.).
  const eventsWord = pluralRu(totalConfirmations, ['события', 'событий', 'событий']);
  return `В ${memberClubCount} ${clubsWord} · посетил ${totalAttendances} из ${totalConfirmations} ${eventsWord}`;
}

/**
 * Genitive form of «клуб» after a count in an "из M" construction: «из 1 клуба»,
 * «из 2/5/8 клубов», «из 21 клуба». (1 and counts ending in 1 except 11 → singular.)
 */
function clubsGenitive(n: number): string {
  return n % 10 === 1 && n % 100 !== 11 ? 'клуба' : 'клубов';
}

/**
 * Headline for the application card "Активность на платформе" donut: «Надёжен в N из M клубов».
 * Only meaningful when the applicant has a track record (trackRecordClubs ≥ 1); the caller gates it.
 */
export function formatReliabilityHeadline(reliableClubs: number, trackRecordClubs: number): string {
  return `Надёжен в ${reliableClubs} из ${trackRecordClubs} ${clubsGenitive(trackRecordClubs)}`;
}

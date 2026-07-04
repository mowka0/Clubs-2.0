import type { PeerStatsDto } from '../../../types/api';

/**
 * Выбор русской формы множественного числа: forms = [один, несколько, много].
 * Зеркалит хелпер из MyClubsPage, но намеренно живёт внутри фичи — чтобы листовая
 * утилита не тянула циклическую зависимость от модуля уровня страницы.
 */
function pluralRu(n: number, forms: readonly [string, string, string]): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return forms[0];
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return forms[1];
  return forms[2];
}

/**
 * Собирает строку peer-signal, показываемую в инбоксе организатора у каждого
 * заявителя. Три случая (см. docs/modules/applications-inbox.md §
 * «Peer-signal — формула и edge cases»):
 *   - клубов нет вовсе → «Новый пользователь»
 *   - клубы есть, Stage-2-подтверждений ещё нет → «В N клубе/клубах · ещё не было событий»
 *   - обычный случай → «В N клубе/клубах · посетил X из Y событий»
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

  // «из {Y} <слово>» — родительный падеж: «события» для 1 (а также 21, 31…), «событий»
  // для всех остальных количеств (включая 0, 2–20 и т.д.).
  const eventsWord = pluralRu(totalConfirmations, ['события', 'событий', 'событий']);
  return `В ${memberClubCount} ${clubsWord} · посетил ${totalAttendances} из ${totalConfirmations} ${eventsWord}`;
}

/**
 * Родительный падеж слова «клуб» после числа в конструкции «из M»: «из 1 клуба»,
 * «из 2/5/8 клубов», «из 21 клуба». (1 и числа, оканчивающиеся на 1, кроме 11 → ед. число.)
 */
function clubsGenitive(n: number): string {
  return n % 10 === 1 && n % 100 !== 11 ? 'клуба' : 'клубов';
}

/**
 * Заголовок donut-блока «Активность на платформе» в карточке заявки: «Надёжен в N из M клубов».
 * Осмыслен только когда у заявителя есть трек-рекорд (trackRecordClubs ≥ 1); гейтит вызывающая сторона.
 */
export function formatReliabilityHeadline(reliableClubs: number, trackRecordClubs: number): string {
  return `Надёжен в ${reliableClubs} из ${trackRecordClubs} ${clubsGenitive(trackRecordClubs)}`;
}

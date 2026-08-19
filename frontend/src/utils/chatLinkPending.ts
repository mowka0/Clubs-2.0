/**
 * Отметка «организатор ушёл привязывать чат» — мост через выход из приложения.
 *
 * Привязка происходит ВНЕ Mini App: тап по «Привязать чат» уводит в Telegram выбирать группу,
 * и на iOS приложение при этом закрывается. Вернувшись, человек должен сразу увидеть окно
 * со статусом подключения бота, а не искать его в «Управлении» (просьба PO 2026-08-15).
 * Ключ переживает перезапуск приложения — поэтому localStorage, а не состояние React.
 */

const PENDING_KEY = 'clubs:chat-linking-pending';

/**
 * Дольше часа ждать нечего: человек передумал или привязал так давно, что окно уже не про
 * «только что подключили». Протухшая отметка тихо выбрасывается при чтении.
 */
const PENDING_TTL_MS = 60 * 60 * 1000;

interface PendingLinking {
  /** null — клуба ещё нет: человек ушёл заводить клуб из чата, id узнаем по возвращении. */
  clubId: string | null;
  /** Клубы, известные ДО ухода, — по разнице находим новорождённый (только для clubId = null). */
  knownClubIds?: string[];
  startedAt: number;
}

/** Запоминает, что для этого клуба сейчас идёт привязка чата. */
export function rememberChatLinkingStarted(clubId: string, now: number): void {
  try {
    const pending: PendingLinking = { clubId, startedAt: now };
    localStorage.setItem(PENDING_KEY, JSON.stringify(pending));
  } catch {
    // localStorage недоступен (приватный режим, вебвью без storage) — окно просто не всплывёт,
    // статус подключения остаётся виден в «Управлении → Чат». Осознанная деградация.
  }
}

/**
 * Запоминает уход за НОВЫМ клубом: его id заранее неизвестен, поэтому сохраняем снимок уже
 * известных клубов — по возвращении новорождённый находится как разница множеств.
 */
export function rememberNewClubLinkingStarted(knownClubIds: string[], now: number): void {
  try {
    const pending: PendingLinking = { clubId: null, knownClubIds, startedAt: now };
    localStorage.setItem(PENDING_KEY, JSON.stringify(pending));
  } catch {
    // См. rememberChatLinkingStarted: без storage человек просто останется на текущем экране.
  }
}

/** Живая (непротухшая) отметка ожидания или null. */
function readPending(now: number): PendingLinking | null {
  try {
    const raw = localStorage.getItem(PENDING_KEY);
    if (!raw) return null;
    const pending = JSON.parse(raw) as Partial<PendingLinking>;
    if (typeof pending.startedAt !== 'number') return null;
    if (pending.clubId !== null && typeof pending.clubId !== 'string') return null;
    if (now - pending.startedAt > PENDING_TTL_MS) {
      forgetChatLinking();
      return null;
    }
    return pending as PendingLinking;
  } catch {
    // Битое значение или недоступный storage — ведём себя как «не ждём»
    return null;
  }
}

/** Клуб, привязку чата которого мы ждём. null — не ждём ничего, ждём новый клуб, или протухло. */
export function readPendingChatLinkClubId(now: number): string | null {
  return readPending(now)?.clubId ?? null;
}

/**
 * Ждём ли мы клуб, рождающийся из чата, и какие клубы были до ухода. null — не ждём.
 * Нужно, чтобы вернуть человека сразу на страницу нового клуба (просьба PO 2026-08-17):
 * Telegram обратно в Mini App не перебрасывает, и приложение открывается заново.
 */
export function readPendingNewClub(now: number): { knownClubIds: string[] } | null {
  const pending = readPending(now);
  if (!pending || pending.clubId !== null) return null;
  return { knownClubIds: pending.knownClubIds ?? [] };
}

/** Снимает отметку: окно показано и закрыто. */
export function forgetChatLinking(): void {
  try {
    localStorage.removeItem(PENDING_KEY);
  } catch {
    // Не удалось стереть — окно всплывёт ещё раз, но не дольше срока жизни отметки
  }
}

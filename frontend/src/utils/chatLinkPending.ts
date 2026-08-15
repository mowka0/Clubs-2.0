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
  clubId: string;
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

/** Клуб, привязку чата которого мы ждём. null — не ждём ничего или отметка протухла. */
export function readPendingChatLinkClubId(now: number): string | null {
  try {
    const raw = localStorage.getItem(PENDING_KEY);
    if (!raw) return null;
    const pending = JSON.parse(raw) as Partial<PendingLinking>;
    if (typeof pending.clubId !== 'string' || typeof pending.startedAt !== 'number') return null;
    if (now - pending.startedAt > PENDING_TTL_MS) {
      forgetChatLinking();
      return null;
    }
    return pending.clubId;
  } catch {
    // Битое значение или недоступный storage — ведём себя как «не ждём»
    return null;
  }
}

/** Снимает отметку: окно показано и закрыто. */
export function forgetChatLinking(): void {
  try {
    localStorage.removeItem(PENDING_KEY);
  } catch {
    // Не удалось стереть — окно всплывёт ещё раз, но не дольше срока жизни отметки
  }
}

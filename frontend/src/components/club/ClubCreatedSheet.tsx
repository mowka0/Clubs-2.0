import { FC } from 'react';
import { Modal } from '@telegram-apps/telegram-ui';
import { ClubCreatedScene } from './ClubCreatedScene';

/**
 * Клубы, где владелец нажал «Позже»: до перезапуска приложения шторка там молчит. В памяти, а не
 * в localStorage — на следующем запуске чек-лист должен снова напомнить о себе.
 */
const postponedClubIds = new Set<string>();

export function postponeClubCreatedSheet(clubId: string): void {
  postponedClubIds.add(clubId);
}

export function isClubCreatedSheetPostponed(clubId: string): boolean {
  return postponedClubIds.has(clubId);
}

export function resetPostponedClubCreatedSheetsForTests(): void {
  postponedClubIds.clear();
}

interface ClubCreatedSheetProps {
  clubName: string;
  /** Шаг 1 пройден: мастер наполнения завершён. */
  setupCompleted: boolean;
  /** Шаг 2 пройден: бот закрепил ссылку на клуб в чате. */
  clubLinkPinned: boolean;
  onFillClub: () => void;
  onShowInChat: () => void;
  onPostpone: () => void;
}

/**
 * «Клуб создан» — чек-лист владельца клуба, рождённого из чата (PO 2026-10-06, 2026-10-10): та же
 * сцена, что у формы создания с нуля, и два шага — наполнить клуб и показать его в чате. Страница
 * клуба открывает её при каждом заходе, пока оба шага не пройдены; пройденный зачёркнут, кнопка
 * ведёт к первому непройденному.
 *
 * В чат бот до закрепа ничего не пишет, чтобы участники увидели уже наполненный клуб, — поэтому
 * сначала мастер наполнения, потом «Показать клуб в чате» из шита приглашения.
 */
export const ClubCreatedSheet: FC<ClubCreatedSheetProps> = ({
  clubName, setupCompleted, clubLinkPinned, onFillClub, onShowInChat, onPostpone,
}) => (
  <Modal open onOpenChange={(open) => !open && onPostpone()}>
    <ClubCreatedScene
      clubName={clubName}
      lead={clubLinkPinned
        ? 'Клуб уже закреплён в чате — наполни его, чтобы участникам было что посмотреть.'
        : 'Бот уже в чате, но участникам пока ничего не написал — первое впечатление за тобой.'}
    >
      <ol className="rd-created-steps">
        <li className={setupCompleted ? 'is-done' : undefined}>
          <b>Наполни клуб:</b> город, описание и обложка — пара минут.
        </li>
        <li className={clubLinkPinned ? 'is-done' : undefined}>
          <b>Покажи клуб в чате:</b> «Пригласить в клуб» → «Показать клуб в чате». Бот закрепит
          сообщение со ссылкой.
        </li>
      </ol>
      <button type="button" className="rd-btn-primary" onClick={setupCompleted ? onShowInChat : onFillClub}>
        {setupCompleted ? 'Показать клуб в чате' : 'Заполнить клуб'}
      </button>
      <button type="button" className="rd-btn-outline" style={{ marginTop: 8 }} onClick={onPostpone}>
        Позже
      </button>
    </ClubCreatedScene>
  </Modal>
);

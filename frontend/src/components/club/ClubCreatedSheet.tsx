import { FC } from 'react';
import { Modal } from '@telegram-apps/telegram-ui';
import { ClubCreatedScene } from './ClubCreatedScene';

interface ClubCreatedSheetProps {
  clubName: string;
  /** Мастер уже пройден — следующий шаг сразу показ клуба в чате, а не наполнение. */
  setupCompleted: boolean;
  onFillClub: () => void;
  onShowInChat: () => void;
  onClose: () => void;
}

/**
 * «Клуб создан» для клуба, рождённого из чата (PO 2026-10-06): та же сцена и та же шторка, что
 * у формы создания с нуля, другие подзаголовок и кнопки. Страница клуба открывает её по
 * `?created=1` — с этим адресом бот присылает кнопку в личку сразу после привязки.
 *
 * В чат бот пока ничего не писал, чтобы участники увидели уже наполненный клуб, — поэтому
 * сначала мастер наполнения, потом «Показать клуб в чате» из шита приглашения.
 */
export const ClubCreatedSheet: FC<ClubCreatedSheetProps> = ({
  clubName, setupCompleted, onFillClub, onShowInChat, onClose,
}) => (
  <Modal open onOpenChange={(open) => !open && onClose()}>
    <ClubCreatedScene
      clubName={clubName}
      lead="Бот уже в чате, но участникам пока ничего не написал — первое впечатление за тобой."
    >
      <ol className="rd-created-steps">
        <li className={setupCompleted ? 'is-done' : undefined}>
          <b>Наполни клуб:</b> город, описание и обложка — пара минут.
        </li>
        <li>
          <b>Покажи клуб в чате:</b> «Пригласить в клуб» → «Показать клуб в чате». Бот закрепит
          сообщение со ссылкой.
        </li>
      </ol>
      <button type="button" className="rd-btn-primary" onClick={setupCompleted ? onShowInChat : onFillClub}>
        {setupCompleted ? 'Показать клуб в чате' : 'Заполнить клуб'}
      </button>
      <button type="button" className="rd-btn-outline" style={{ marginTop: 8 }} onClick={onClose}>
        Позже
      </button>
    </ClubCreatedScene>
  </Modal>
);

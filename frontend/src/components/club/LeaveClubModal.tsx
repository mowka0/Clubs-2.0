import { FC } from 'react';
import { Button, Modal, Spinner, Text } from '@telegram-apps/telegram-ui';

interface LeaveClubModalProps {
  open: boolean;
  clubName: string;
  /**
   * Free → immediate access loss + cascade copy.
   * Paid → autorenew-off + «доступ до …» copy. `paidUntil` must be passed in
   * pre-formatted ru-RU, parent owns date formatting.
   */
  variant: 'free' | 'paid';
  paidUntilLabel: string | null;
  submitting: boolean;
  errorMessage: string | null;
  onConfirm: () => void;
  onClose: () => void;
}

const FREE_BODY =
  'Доступ к клубу будет отозван сразу. Вы будете удалены из всех активных событий и сборов.';

function paidBody(paidUntil: string): string {
  return `Подписка будет отменена. Доступ к клубу сохранится до ${paidUntil}. После этой даты вы будете удалены из клуба.`;
}

export const LeaveClubModal: FC<LeaveClubModalProps> = ({
  open,
  clubName,
  variant,
  paidUntilLabel,
  submitting,
  errorMessage,
  onConfirm,
  onClose,
}) => {
  if (!open) return null;

  const body =
    variant === 'free'
      ? `Вы выйдете из клуба «${clubName}». ${FREE_BODY}`
      : paidBody(paidUntilLabel ?? '');

  return (
    <Modal open onOpenChange={(o) => !o && onClose()}>
      <div style={{ padding: 16 }}>
        <Text weight="2" style={{ fontSize: 18, display: 'block', marginBottom: 12 }}>
          Выйти из клуба?
        </Text>
        <div
          style={{
            color: 'var(--tgui--hint_color)',
            fontSize: 14,
            lineHeight: 1.45,
            marginBottom: 12,
          }}
        >
          {body}
        </div>
        {errorMessage && (
          <div
            style={{
              padding: '8px 0',
              color: 'var(--tgui--destructive_text_color)',
              fontSize: 14,
            }}
          >
            {errorMessage}
          </div>
        )}
        <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
          <Button size="m" mode="outline" onClick={onClose} disabled={submitting} stretched>
            Отмена
          </Button>
          <Button
            size="m"
            mode="filled"
            onClick={onConfirm}
            disabled={submitting}
            stretched
            style={{
              background: 'var(--tgui--destructive_text_color, #E53935)',
              color: '#fff',
            }}
          >
            {submitting ? <Spinner size="s" /> : 'Выйти'}
          </Button>
        </div>
      </div>
    </Modal>
  );
};

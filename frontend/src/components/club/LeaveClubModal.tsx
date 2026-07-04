import { FC } from 'react';
import { Button, Modal, Spinner, Text } from '@telegram-apps/telegram-ui';

interface LeaveClubModalProps {
  open: boolean;
  clubName: string;
  /**
   * Free → немедленная потеря доступа + каскадный текст.
   * Paid → отключение автопродления + текст «доступ до …». `paidUntil` должен
   * передаваться уже отформатированным в ru-RU, форматирование даты — на стороне родителя.
   */
  variant: 'free' | 'paid';
  paidUntilLabel: string | null;
  /**
   * Число открытых обязательств (подтверждённые записи + незакрытые репутационные
   * складчины), которые вызывающий сломает, выйдя из free-клуба. > 0 → показать
   * предупреждение о надёжности. Игнорируется для paid-варианта (ничего не ломается
   * до истечения срока). 0, пока [obligationsLoading].
   */
  obligationsCount: number;
  obligationsLoading: boolean;
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

/** Russian plural for «обязательство» (1 / 2-4 / 5+). */
function obligationsWord(n: number): string {
  const mod100 = n % 100;
  const mod10 = n % 10;
  if (mod100 >= 11 && mod100 <= 14) return 'обязательств';
  if (mod10 === 1) return 'обязательство';
  if (mod10 >= 2 && mod10 <= 4) return 'обязательства';
  return 'обязательств';
}

export const LeaveClubModal: FC<LeaveClubModalProps> = ({
  open,
  clubName,
  variant,
  paidUntilLabel,
  obligationsCount,
  obligationsLoading,
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

  const showObligationsWarning =
    variant === 'free' && !obligationsLoading && obligationsCount > 0;

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
        {showObligationsWarning && (
          <div
            style={{
              padding: '10px 12px',
              marginBottom: 12,
              borderRadius: 10,
              background: 'var(--tgui--secondary_fill, rgba(255,149,0,0.12))',
              color: 'var(--tgui--text_color)',
              fontSize: 14,
              lineHeight: 1.45,
            }}
          >
            Вы бросите {obligationsCount} {obligationsWord(obligationsCount)} перед клубом — это
            снизит вашу надёжность.
          </div>
        )}
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

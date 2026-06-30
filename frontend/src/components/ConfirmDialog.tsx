import { FC, ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Spinner } from '@telegram-apps/telegram-ui';

interface ConfirmDialogProps {
  title: string;
  message: ReactNode;
  confirmLabel: string;
  cancelLabel?: string;
  /** Tint the confirm button as destructive. */
  danger?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * Small centered confirmation popup (warning before a one-tap action). Rendered via portal over a dim
 * overlay; the overlay tap cancels unless busy. Reusable across the app — first use: withdrawing a
 * club application.
 */
export const ConfirmDialog: FC<ConfirmDialogProps> = ({
  title,
  message,
  confirmLabel,
  cancelLabel = 'Отмена',
  danger = false,
  busy = false,
  onConfirm,
  onCancel,
}) => {
  return createPortal(
    <>
      <div className="rd-sheet-overlay" onClick={busy ? undefined : onCancel} aria-hidden="true" />
      <div className="rd-dialog" role="dialog" aria-modal="true" aria-label={title}>
        <div className="rd-dialog-title">{title}</div>
        <div className="rd-dialog-msg">{message}</div>
        <div className="rd-dialog-acts">
          <button type="button" className="rd-btn-outline" disabled={busy} onClick={onCancel}>
            {cancelLabel}
          </button>
          <button
            type="button"
            className={`rd-btn-primary${danger ? ' rd-btn-danger' : ''}`}
            disabled={busy}
            onClick={onConfirm}
          >
            {busy ? <Spinner size="s" /> : confirmLabel}
          </button>
        </div>
      </div>
    </>,
    document.body,
  );
};

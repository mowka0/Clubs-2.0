import { FC, ReactNode, useState } from 'react';
import { createPortal } from 'react-dom';

interface ConfirmSheetProps {
  text: string;
  confirmLabel?: string;
  /** Дополнительный выбор между текстом и кнопками (например, переключатель). */
  children?: ReactNode;
  onConfirm: () => void;
  onCancel: () => void;
}

/** Шторка подтверждения вместо `window.confirm`: свои подписи кнопок и без заголовка с доменом. */
export const ConfirmSheet: FC<ConfirmSheetProps> = ({ text, confirmLabel = 'Подтвердить', children, onConfirm, onCancel }) =>
  createPortal(
    <>
      <div className="rd-sheet-overlay rd-overlay-in" onClick={onCancel} aria-hidden="true" />
      <div className="rd-sheet rd-sheet-in rd-confirm-sheet" role="dialog" aria-modal="true" aria-label={text}>
        <div className="rd-sheet-grabber" aria-hidden="true" />
        <p className="rd-confirm-text">{text}</p>
        {children}
        <div className="rd-form-actions">
          <button type="button" className="rd-btn-primary" onClick={onConfirm}>{confirmLabel}</button>
          <button type="button" className="rd-btn-outline" onClick={onCancel}>Отмена</button>
        </div>
      </div>
    </>,
    document.body,
  );

interface PendingConfirm {
  text: string;
  confirmLabel?: string;
  resolve: (ok: boolean) => void;
}

/**
 * `const { confirm, confirmSheet } = useConfirm()`: `await confirm('…')` даёт true/false,
 * `confirmSheet` рендерится в JSX один раз. Один вопрос за раз, как у `window.confirm`.
 */
export function useConfirm() {
  const [pending, setPending] = useState<PendingConfirm | null>(null);

  const confirm = (text: string, confirmLabel?: string): Promise<boolean> =>
    new Promise((resolve) => setPending({ text, confirmLabel, resolve }));

  const settle = (ok: boolean) => {
    pending?.resolve(ok);
    setPending(null);
  };

  const confirmSheet = pending ? (
    <ConfirmSheet text={pending.text} confirmLabel={pending.confirmLabel} onConfirm={() => settle(true)} onCancel={() => settle(false)} />
  ) : null;

  return { confirm, confirmSheet };
}

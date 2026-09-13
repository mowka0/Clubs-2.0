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

interface ChoiceSheetOption {
  label: string;
  hint?: string;
  onPick: () => void;
}

interface ChoiceSheetProps {
  title: string;
  options: ChoiceSheetOption[];
  onCancel: () => void;
}

/** Шторка выбора из двух-трёх вариантов (например, вид сбора со страницы встречи). */
export const ChoiceSheet: FC<ChoiceSheetProps> = ({ title, options, onCancel }) =>
  createPortal(
    <>
      <div className="rd-sheet-overlay rd-overlay-in" onClick={onCancel} aria-hidden="true" />
      <div className="rd-sheet rd-sheet-in rd-confirm-sheet" role="dialog" aria-modal="true" aria-label={title}>
        <div className="rd-sheet-grabber" aria-hidden="true" />
        <p className="rd-confirm-text">{title}</p>
        <div className="rd-pick-list">
          {options.map((o) => (
            <button key={o.label} type="button" className="rd-pick-toggle" style={{ width: '100%' }} onClick={o.onPick}>
              <span className="rd-pick-name">{o.label}</span>
              {o.hint && <span className="rd-pick-note">{o.hint}</span>}
            </button>
          ))}
        </div>
        <div className="rd-form-actions" style={{ marginTop: 12 }}>
          <button type="button" className="rd-btn-outline" onClick={onCancel}>Отмена</button>
        </div>
      </div>
    </>,
    document.body,
  );

import { FC, useEffect, useState } from 'react';

interface Props {
  message: string;
  durationMs?: number;
  onClose?: () => void;
}

export const Toast: FC<Props> = ({ message, durationMs = 3000, onClose }) => {
  const [visible, setVisible] = useState(true);

  useEffect(() => {
    const timer = setTimeout(() => {
      setVisible(false);
      onClose?.();
    }, durationMs);
    return () => clearTimeout(timer);
  }, [durationMs, onClose]);

  if (!visible) return null;

  return (
    <div
      role="status"
      style={{
        position: 'fixed',
        left: '50%',
        bottom: 80,
        transform: 'translateX(-50%)',
        background: 'var(--tgui--secondary_bg_color, #333)',
        color: 'var(--tgui--text_color, #fff)',
        padding: '10px 16px',
        borderRadius: 12,
        fontSize: 14,
        boxShadow: '0 4px 16px rgba(0,0,0,0.2)',
        maxWidth: '80vw',
        textAlign: 'center',
        zIndex: 1000,
        pointerEvents: 'none',
      }}
    >
      {message}
    </div>
  );
};

import { FC, useEffect } from 'react';
import { useHaptic } from '../hooks/useHaptic';

interface Props {
  /** The image to show full-screen; null/empty keeps the lightbox closed. */
  src: string | null;
  alt?: string;
  onClose: () => void;
}

/**
 * Full-screen image viewer (e.g. a receipt — so everyone reads it and works out their share).
 * The image fills the screen width and scrolls vertically, which keeps a tall receipt legible;
 * tapping the backdrop or the ✕ closes it, tapping the image itself does not (so you can scroll).
 * Reusable: any `<img>` thumbnail can open this by lifting `src` into state.
 */
export const ImageLightbox: FC<Props> = ({ src, alt = '', onClose }) => {
  const haptic = useHaptic();

  // Sync with the external world: close on Escape and lock body scroll while open. Cleanup
  // restores both — re-runs whenever `src` toggles open/closed.
  useEffect(() => {
    if (!src) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = prevOverflow;
    };
  }, [src, onClose]);

  if (!src) return null;

  const close = () => {
    haptic.impact('light');
    onClose();
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      onClick={close}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 9999,
        background: 'rgba(0, 0, 0, 0.94)',
        overflowY: 'auto',
        display: 'flex',
        alignItems: 'flex-start',
        justifyContent: 'center',
        WebkitOverflowScrolling: 'touch',
      }}
    >
      <button
        type="button"
        aria-label="Закрыть"
        onClick={(e) => { e.stopPropagation(); close(); }}
        style={{
          position: 'fixed',
          top: 'max(12px, env(safe-area-inset-top))',
          right: 16,
          width: 40,
          height: 40,
          borderRadius: '50%',
          border: 'none',
          background: 'rgba(0, 0, 0, 0.5)',
          color: '#fff',
          fontSize: 22,
          lineHeight: '40px',
          cursor: 'pointer',
          zIndex: 1,
        }}
      >
        ✕
      </button>
      <img
        src={src}
        alt={alt}
        onClick={(e) => e.stopPropagation()}
        style={{ width: '100%', maxWidth: 720, height: 'auto', display: 'block', margin: 'auto 0' }}
      />
    </div>
  );
};

import { FC, ReactNode } from 'react';

interface FeedEmptyProps {
  icon?: ReactNode;
  title: string;
  description: string;
  ctaLabel?: string;
  onCta?: () => void;
}

export const FeedEmpty: FC<FeedEmptyProps> = ({ icon, title, description, ctaLabel, onCta }) => (
  <div className="rd-glass rd-empty">
    {icon && <div style={{ color: 'var(--text-faint)', marginBottom: 8 }}>{icon}</div>}
    <div className="rd-title">{title}</div>
    <div className="rd-sub">{description}</div>
    {ctaLabel && onCta && (
      <button type="button" className="rd-ghost-btn" onClick={onCta}>{ctaLabel}</button>
    )}
  </div>
);

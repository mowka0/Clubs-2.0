import { FC, ReactNode } from 'react';

interface FeedEmptyProps {
  icon?: ReactNode;
  title: string;
  description: string;
  ctaLabel?: string;
  onCta?: () => void;
}

export const FeedEmpty: FC<FeedEmptyProps> = ({ icon, title, description, ctaLabel, onCta }) => (
  <div className="feed-empty">
    {icon && <div className="ico">{icon}</div>}
    <div className="title">{title}</div>
    <div className="sub">{description}</div>
    {ctaLabel && onCta && (
      <div className="actions">
        <button type="button" className="ghost-btn" onClick={onCta}>{ctaLabel}</button>
      </div>
    )}
  </div>
);

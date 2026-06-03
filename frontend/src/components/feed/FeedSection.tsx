import { FC, ReactNode } from 'react';

interface FeedSectionProps {
  title: string;
  count: number;
  accent?: boolean;
  children: ReactNode;
}

export const FeedSection: FC<FeedSectionProps> = ({ title, count, accent = false, children }) => (
  <>
    <div className="rd-section-sub-h" style={accent ? { color: 'var(--accent)' } : undefined}>
      {title} <span className="rd-count">· {count}</span>
    </div>
    <div>{children}</div>
  </>
);

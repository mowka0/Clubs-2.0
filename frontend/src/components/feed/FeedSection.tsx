import { FC, ReactNode } from 'react';

interface FeedSectionProps {
  title: string;
  count: number;
  accent?: boolean;
  children: ReactNode;
}

export const FeedSection: FC<FeedSectionProps> = ({ title, count, accent = false, children }) => (
  <>
    <div className={accent ? 'feed-section-label accent' : 'feed-section-label'}>
      {title} <span className="count">· {count}</span>
    </div>
    <div className="feed-list">{children}</div>
  </>
);

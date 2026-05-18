import { FC } from 'react';

interface FeedSkeletonProps {
  count?: number;
}

export const FeedSkeleton: FC<FeedSkeletonProps> = ({ count = 3 }) => (
  <div className="feed-list" aria-busy="true" aria-label="Загружаем события">
    {Array.from({ length: count }).map((_, i) => (
      <div className="feed-skeleton" key={i}>
        <div className="sk-row sk-row-date" />
        <div className="sk-row sk-row-title" />
        <div className="sk-row sk-row-meta" />
      </div>
    ))}
  </div>
);

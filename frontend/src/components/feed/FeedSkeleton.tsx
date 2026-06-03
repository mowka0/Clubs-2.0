import { FC } from 'react';

interface FeedSkeletonProps {
  count?: number;
}

export const FeedSkeleton: FC<FeedSkeletonProps> = ({ count = 3 }) => (
  <div aria-busy="true" aria-label="Загружаем активности">
    {Array.from({ length: count }).map((_, i) => (
      <div className="rd-skeleton" key={i}>
        <div className="rd-sk-cover" />
        <div className="rd-sk-body">
          <div className="rd-sk-line rd-short" />
          <div className="rd-sk-line" />
          <div className="rd-sk-line rd-short" />
        </div>
      </div>
    ))}
  </div>
);

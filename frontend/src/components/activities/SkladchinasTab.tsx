import { FC, useEffect, useMemo, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import { useMySkladchinasQuery } from '../../queries/skladchina';
import { FeedSection } from '../feed/FeedSection';
import { FeedSkeleton } from '../feed/FeedSkeleton';
import { FeedEmpty } from '../feed/FeedEmpty';
import { SkladchinaCard } from '../feed/SkladchinaCard';
import type { MySkladchinaListItemDto } from '../../types/api';

const COIN_ICON = (
  <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="12" cy="12" r="9" />
    <path d="M9 9.5C9 8 10.3 7 12 7s3 1 3 2.5-1.3 2.5-3 2.5-3 1-3 2.5S10.3 17 12 17s3-1 3-2.5" />
    <line x1="12" y1="6" x2="12" y2="18" />
  </svg>
);

interface Group {
  key: 'action_required' | 'active' | 'history';
  title: string;
  items: MySkladchinaListItemDto[];
}

function groupSkladchinas(list: readonly MySkladchinaListItemDto[]): Group[] {
  const action: MySkladchinaListItemDto[] = [];
  const active: MySkladchinaListItemDto[] = [];
  const history: MySkladchinaListItemDto[] = [];
  for (const s of list) {
    if (s.status !== 'active') history.push(s);
    else if (s.actionRequired) action.push(s);
    else active.push(s);
  }
  const result: Group[] = [];
  if (action.length > 0) result.push({ key: 'action_required', title: 'Требует оплаты', items: action });
  if (active.length > 0) result.push({ key: 'active', title: 'Активные сборы', items: active });
  if (history.length > 0) result.push({ key: 'history', title: 'История', items: history });
  return result;
}

export const SkladchinasTab: FC = () => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  const query = useMySkladchinasQuery();

  const items = useMemo(
    () => query.data?.pages.flatMap((p) => p.content) ?? [],
    [query.data],
  );
  const groups = useMemo(() => groupSkladchinas(items), [items]);

  const loadMoreRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const target = loadMoreRef.current;
    if (!target || !query.hasNextPage || query.isFetchingNextPage) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) {
          query.fetchNextPage();
        }
      },
      { rootMargin: '200px' },
    );
    observer.observe(target);
    return () => observer.disconnect();
  }, [query.hasNextPage, query.isFetchingNextPage, query.fetchNextPage]);

  const handleClick = (id: string) => {
    haptic.impact('light');
    navigate(`/skladchina/${id}`);
  };

  const handleSearchClick = () => {
    haptic.impact('light');
    navigate('/');
  };

  const isLoadingInitial = query.isPending;
  const isError = query.isError && !query.isPending;
  const isEmpty = !isLoadingInitial && !isError && items.length === 0;

  return (
    <>
      {isLoadingInitial && <FeedSkeleton count={3} />}

      {isError && (
        <FeedEmpty
          icon={COIN_ICON}
          title="Не удалось загрузить сборы"
          description="Проверьте соединение и попробуйте снова."
          ctaLabel="Повторить"
          onCta={() => query.refetch()}
        />
      )}

      {isEmpty && (
        <FeedEmpty
          icon={COIN_ICON}
          title="Активных сборов нет"
          description="Когда организатор клуба создаст сбор и выберет вас участником — он появится здесь."
          ctaLabel="Перейти в Поиск"
          onCta={handleSearchClick}
        />
      )}

      {!isLoadingInitial && !isError && groups.map((group) => (
        <FeedSection
          key={group.key}
          title={group.title}
          count={group.items.length}
          accent={group.key === 'action_required'}
        >
          {group.items.map((s) => (
            <SkladchinaCard key={s.id} skladchina={s} onClick={() => handleClick(s.id)} />
          ))}
        </FeedSection>
      ))}

      {query.hasNextPage && (
        <div ref={loadMoreRef} style={{ display: 'flex', justifyContent: 'center', padding: 24 }}>
          {query.isFetchingNextPage && <Spinner size="s" />}
        </div>
      )}
    </>
  );
};

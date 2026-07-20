import { FC, useEffect, useMemo, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import { useMySkladchinasQuery } from '../../queries/skladchina';
import { useOrganizerClubs } from '../../queries/organizerClubs';
import { useCreateFlowStore } from '../../store/useCreateFlowStore';
import { FeedSection } from '../feed/FeedSection';
import { FeedSkeleton } from '../feed/FeedSkeleton';
import { FoxEmpty } from '../feed/FoxEmpty';
import { SkladchinaCard } from '../feed/SkladchinaCard';
import foxSkladchinaArt from '../../assets/mascot/fox-skladchina.png';
import foxErrorArt from '../../assets/mascot/fox-error.png';
import type { MySkladchinaListItemDto } from '../../types/api';

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
  // Роль-развилка пустого состояния (зеркало EventsTab): участнику складчины не создать —
  // он ждёт, пока организатор его добавит; организатору предлагаем создать первый сбор.
  const { clubs: organizerClubs, isLoading: isRoleLoading } = useOrganizerClubs();
  const isOrganizer = organizerClubs.length > 0;
  const openCreateFlow = useCreateFlowStore((s) => s.open);

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

  const handleCreateClick = () => {
    haptic.impact('light');
    openCreateFlow();
  };

  const isLoadingInitial = query.isPending;
  const isError = query.isError && !query.isPending;
  // Сцена лиса — при отсутствии АКТИВНЫХ сборов (решение PO 2026-07-20): закрытые
  // и прошедшие живут в «Истории» и не должны прятать пустое состояние — иначе
  // юзер с одной старой складчиной никогда не видит ни лиса, ни CTA «Создать сбор».
  const hasActive = items.some((s) => s.status === 'active');
  const hasHistory = items.some((s) => s.status !== 'active');
  const isEmpty = !isLoadingInitial && !isError && !hasActive;
  // Пока роль не определена, пустую сцену держим на скелетоне — иначе организатор
  // на мгновение увидит участнический вариант без CTA «Создать сбор».
  const isEmptySceneResolving = isEmpty && isRoleLoading;

  return (
    <>
      {(isLoadingInitial || isEmptySceneResolving) && <FeedSkeleton count={3} />}

      {isError && (
        <FoxEmpty
          art={foxErrorArt}
          variant="error"
          title="Не удалось загрузить сборы"
          description="Проверь соединение и попробуй ещё раз."
          primary={{ label: 'Повторить', onClick: () => { haptic.impact('light'); query.refetch(); } }}
        />
      )}

      {isEmpty && !isEmptySceneResolving && (
        isOrganizer ? (
          <FoxEmpty
            art={foxSkladchinaArt}
            soonIcon={hasHistory ? undefined : '💰'}
            title={hasHistory ? 'Активных сборов нет' : 'Сборов пока нет'}
            description="Собери на аренду, инвентарь или общий подарок — создай сбор, и участники увидят его здесь."
            primary={{ label: 'Создать сбор', onClick: handleCreateClick }}
          />
        ) : (
          <FoxEmpty
            art={foxSkladchinaArt}
            soonIcon={hasHistory ? undefined : '💰'}
            title={hasHistory ? 'Активных сборов нет' : 'Сборов пока нет'}
            description="Когда организатор клуба создаст сбор и добавит тебя — он появится здесь."
          />
        )
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

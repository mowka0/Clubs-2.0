import { FC } from 'react';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import { useMyClubsQuery } from '../../queries/clubs';
import {
  useCancelSubscriptionMutation,
  useSubscribeMutation,
  useSubscriptionPlansQuery,
  useSubscriptionStatusQuery,
} from '../../queries/subscription';
import { PlanCard } from './PlanCard';
import { formatPeriodEnd, planRank } from './planDisplay';

/**
 * Карточка управления подпиской в профиле (Вариант A): лестница тарифов с отметкой текущего плана,
 * кнопки апгрейда на старших тарифах и отмена для активного платного плана. Ориентирована на
 * организатора; рендерится для любого аккаунта.
 */
export const SubscriptionCard: FC = () => {
  const haptic = useHaptic();
  const statusQuery = useSubscriptionStatusQuery();
  const plansQuery = useSubscriptionPlansQuery();
  const myClubsQuery = useMyClubsQuery();
  const subscribeMutation = useSubscribeMutation();
  const cancelMutation = useCancelSubscriptionMutation();

  const status = statusQuery.data;
  // Поверхность только для организатора: показывается, когда пользователь владеет хотя бы одним клубом.
  const isOrganizer = (myClubsQuery.data ?? []).some((m) => m.role === 'organizer');
  if (!status || !isOrganizer) return null;

  const actionError =
    cancelMutation.error instanceof Error
      ? cancelMutation.error.message
      : subscribeMutation.error instanceof Error
        ? subscribeMutation.error.message
        : null;

  const plans = [...(plansQuery.data ?? [])].sort((a, b) => planRank(a.plan) - planRank(b.plan));
  const currentRank = planRank(status.plan);
  const isActivePaid = status.status === 'ACTIVE' && status.priceKopecks > 0;
  const isCancelling = status.status === 'CANCELLED_PENDING_END';

  return (
    <>
      <div className="rd-section-sub-h">Подписка</div>

      {plans.map((plan) => {
        const isCurrent = plan.plan === status.plan;
        const selectable = planRank(plan.plan) > currentRank;
        const loadingThis = subscribeMutation.isPending && subscribeMutation.variables === plan.plan;
        return (
          <PlanCard
            key={plan.plan}
            plan={plan}
            dimmed={!isCurrent && !selectable}
            chip={isCurrent ? 'current' : null}
            cta={
              selectable ? (
                <button
                  type="button"
                  className="rd-plan__cta rd-ghost-btn"
                  style={{ width: '100%' }}
                  disabled={subscribeMutation.isPending}
                  onClick={() => { haptic.impact('light'); subscribeMutation.mutate(plan.plan); }}
                >
                  {loadingThis ? <Spinner size="s" /> : 'Перейти'}
                </button>
              ) : null
            }
          />
        );
      })}

      {(isActivePaid || isCancelling) && status.currentPeriodEnd && (
        <p style={{ fontSize: 12.5, color: 'var(--text-dim)', margin: '2px 2px 8px' }}>
          {isCancelling
            ? `Продление отключено · доступ до ${formatPeriodEnd(status.currentPeriodEnd)}`
            : `Следующее списание ${formatPeriodEnd(status.currentPeriodEnd)}`}
        </p>
      )}

      {isActivePaid && (
        <button
          type="button"
          className="rd-ghost-btn"
          style={{ width: '100%' }}
          disabled={cancelMutation.isPending}
          onClick={() => { haptic.impact('light'); cancelMutation.mutate(); }}
        >
          {cancelMutation.isPending ? <Spinner size="s" /> : 'Отменить подписку'}
        </button>
      )}

      {actionError && (
        <div className="rd-error" style={{ textAlign: 'left', marginTop: 8 }}>{actionError}</div>
      )}
    </>
  );
};

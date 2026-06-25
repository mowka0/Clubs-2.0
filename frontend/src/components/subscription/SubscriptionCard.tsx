import { FC } from 'react';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import {
  useCancelSubscriptionMutation,
  useSubscribeMutation,
  useSubscriptionPlansQuery,
  useSubscriptionStatusQuery,
} from '../../queries/subscription';
import { PlanCard } from './PlanCard';
import { formatPeriodEnd, planRank } from './planDisplay';

/**
 * Profile management card (Вариант A): the tier ladder with the current plan marked, upgrade buttons
 * on higher tiers, and cancel for an active paid plan. Organizer-centric; renders for any account.
 */
export const SubscriptionCard: FC = () => {
  const haptic = useHaptic();
  const statusQuery = useSubscriptionStatusQuery();
  const plansQuery = useSubscriptionPlansQuery();
  const subscribeMutation = useSubscribeMutation();
  const cancelMutation = useCancelSubscriptionMutation();

  const status = statusQuery.data;
  if (!status) return null;

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
    </>
  );
};

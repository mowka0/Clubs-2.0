import { FC } from 'react';
import { Spinner } from '@telegram-apps/telegram-ui';
import type { PaywallInfo } from '../../api/subscription';
import { useSubscriptionPlansQuery } from '../../queries/subscription';
import { PlanCard } from './PlanCard';
import { planRank } from './planDisplay';

interface PaywallModalProps {
  info: PaywallInfo;
  /** План, подписка на который в процессе (спиннер на его кнопке; все кнопки заблокированы, пока это значение задано). */
  submittingPlan: string | null;
  error: string | null;
  onSelectPlan: (plan: string) => void;
  onClose: () => void;
}

/**
 * Пейволл варианта A: показывается внутри CreateClubModal, когда backend возвращает 402.
 * Рендерит полную лестницу тарифов: текущий план затемнён, требуемый — подсвечен/рекомендован.
 */
export const PaywallModal: FC<PaywallModalProps> = ({ info, submittingPlan, error, onSelectPlan, onClose }) => {
  const plansQuery = useSubscriptionPlansQuery();
  const plans = [...(plansQuery.data ?? [])].sort((a, b) => planRank(a.plan) - planRank(b.plan));
  const currentRank = planRank(info.currentPlan);
  const busy = submittingPlan !== null;

  return (
    <div className="rd-modal-form" style={{ padding: 16 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <span style={{ fontSize: 16, fontWeight: 700 }}>Нужна подписка</span>
        <button
          onClick={onClose}
          disabled={busy}
          style={{ background: 'none', border: 'none', fontSize: 20, cursor: 'pointer', color: 'var(--text)' }}
        >
          &#x2715;
        </button>
      </div>

      <div className="rd-paywall-ctx">
        <span className="rd-paywall-ctx__lock">&#128274;</span>
        <span>Лимит текущего плана. Чтобы вести больше платных клубов, выберите тариф ниже.</span>
      </div>

      {error && <div className="rd-error" style={{ textAlign: 'left', marginBottom: 10 }}>{error}</div>}

      {plansQuery.isPending ? (
        <div style={{ textAlign: 'center', padding: 24 }}><Spinner size="m" /></div>
      ) : (
        plans.map((plan) => {
          const isCurrent = plan.plan === info.currentPlan;
          const isRecommended = plan.plan === info.requiredPlan;
          const selectable = planRank(plan.plan) > currentRank;
          return (
            <PlanCard
              key={plan.plan}
              plan={plan}
              highlighted={isRecommended}
              dimmed={!selectable && !isRecommended}
              chip={isCurrent ? 'current' : isRecommended ? 'recommended' : null}
              cta={
                selectable ? (
                  <button
                    type="button"
                    className={`rd-plan__cta ${isRecommended ? 'rd-btn-primary' : 'rd-ghost-btn'}`}
                    style={{ width: '100%' }}
                    disabled={busy}
                    onClick={() => onSelectPlan(plan.plan)}
                  >
                    {submittingPlan === plan.plan ? <Spinner size="s" /> : 'Подключить'}
                  </button>
                ) : null
              }
            />
          );
        })
      )}

      <button
        type="button"
        className="rd-ghost-btn"
        style={{ width: '100%', marginTop: 6 }}
        onClick={onClose}
        disabled={busy}
      >
        Не сейчас
      </button>
      <p style={{ textAlign: 'center', fontSize: 12, color: 'var(--text-dim)', marginTop: 10 }}>
        Бесплатные клубы не считаются — их можно сколько угодно.
      </p>
    </div>
  );
};

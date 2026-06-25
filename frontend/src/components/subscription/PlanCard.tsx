import { FC, ReactNode } from 'react';
import type { PlanOptionDto } from '../../api/subscription';
import { formatRub, planCapacityLabel, planLabel } from './planDisplay';

interface PlanCardProps {
  plan: PlanOptionDto;
  /** Accent border + accent price (the recommended / target tier). */
  highlighted?: boolean;
  /** Faded styling (the current plan or a lower, non-selectable tier). */
  dimmed?: boolean;
  chip?: 'current' | 'recommended' | null;
  /** Action button(s), or nothing for non-selectable tiers. */
  cta?: ReactNode;
}

/** One tier row in the plan ladder. Shared by the paywall modal and the profile management card. */
export const PlanCard: FC<PlanCardProps> = ({ plan, highlighted = false, dimmed = false, chip = null, cta = null }) => {
  const className = `rd-plan${dimmed ? ' rd-plan--current' : ''}${highlighted ? ' rd-plan--rec' : ''}`;
  return (
    <div className={className}>
      <div className="rd-plan__top">
        <span className="rd-plan__name">{planLabel(plan.plan)}</span>
        {chip === 'current' && <span className="rd-chip rd-chip--cur">Текущий</span>}
        {chip === 'recommended' && <span className="rd-chip rd-chip--rec">Рекомендуем</span>}
      </div>
      <div className="rd-plan__cap">{planCapacityLabel(plan.maxPaidClubs)}</div>
      <div className={`rd-plan__price${highlighted ? ' rd-plan__price--accent' : ''}`}>
        {formatRub(plan.priceKopecks)}
        <small> / мес</small>
      </div>
      {cta}
    </div>
  );
};

import { FC, ReactNode } from 'react';
import type { PlanOptionDto } from '../../api/subscription';
import { formatRub, planCapacityLabel, planLabel } from './planDisplay';

interface PlanCardProps {
  plan: PlanOptionDto;
  /** Акцентная рамка + акцентная цена (рекомендуемый / целевой тариф). */
  highlighted?: boolean;
  /** Приглушённое оформление (текущий план или более низкий, невыбираемый тариф). */
  dimmed?: boolean;
  chip?: 'current' | 'recommended' | null;
  /** Кнопка(и) действия, либо ничего для невыбираемых тарифов. */
  cta?: ReactNode;
}

/** Одна строка тарифа в лестнице планов. Используется и в paywall-модалке, и в карточке управления профилем. */
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

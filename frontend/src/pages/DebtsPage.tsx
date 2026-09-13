import { FC } from 'react';
import { useNavigate } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useDebtsOverviewQuery } from '../queries/debts';
import type { DebtCounterpartyDto } from '../types/api';
import { formatRub } from '../utils/money';
import { DAY_FMT, initials, personName } from '../utils/skladchinaKind';

function pluralDebts(n: number): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return `${n} долг`;
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return `${n} долга`;
  return `${n} долгов`;
}

const PersonPlate: FC<{ p: DebtCounterpartyDto; onClick: () => void }> = ({ p, onClick }) => {
  const name = personName(p.user);
  const balance = p.balanceKopecks;
  const balanceCls = balance > 0 ? 'rd-debt-pos' : balance < 0 ? 'rd-debt-neg' : '';
  const balanceText = balance > 0 ? `+${formatRub(balance)}` : balance < 0 ? formatRub(balance) : 'по нулям';
  return (
    <button type="button" className="rd-glass rd-debt-plate" onClick={onClick}>
      <span className="rd-av rd-debt-av">{p.user.avatarUrl ? <img src={p.user.avatarUrl} alt="" /> : initials(name)}</span>
      <span className="rd-debt-who">
        <b>{name}</b>
        {p.user.username && <span className="rd-debt-handle">@{p.user.username}</span>}
        <span className="rd-debt-meta">
          {pluralDebts(p.debtCount)}
          {p.nearestDueAt && ` · до ${DAY_FMT.format(new Date(p.nearestDueAt))}`}
        </span>
        {p.awaitingMyConfirmation > 0 && <span className="rd-debt-flag">говорит, что отдал · подтвердите</span>}
        {p.awaitingMyConfirmation === 0 && p.awaitingTheirConfirmation > 0 && <span className="rd-debt-meta">ждём подтверждения</span>}
      </span>
      <span className={`rd-debt-balance ${balanceCls}`}>{balanceText}</span>
    </button>
  );
};

/** Экран «Долги»: личная книга через все клубы, вход из профиля (skladchina-v3 § 9). */
export const DebtsPage: FC = () => {
  useBackButton(true);
  const navigate = useNavigate();
  const haptic = useHaptic();
  const query = useDebtsOverviewQuery();

  return (
    <div className="rd-page">
      <div className="rd-ft-eyebrow">Профиль</div>
      <h1 className="rd-page-h" style={{ marginBottom: 14 }}>Долги</h1>

      {query.isPending && <div className="rd-spinner-row" style={{ paddingTop: 40 }}><Spinner size="m" /></div>}
      {query.isError && (
        <div className="rd-glass rd-empty" role="alert">
          <div className="rd-title">Не удалось загрузить долги</div>
          <button type="button" className="rd-ghost-btn" onClick={() => query.refetch()}>Повторить</button>
        </div>
      )}

      {query.data && (
        <>
          <div className="rd-glass rd-debt-summary">
            <div className="rd-debt-sum-col">
              <span className="rd-debt-sum-lbl">Вы должны</span>
              <b className={query.data.oweKopecks > 0 ? 'rd-debt-neg' : ''}>{formatRub(query.data.oweKopecks)}</b>
            </div>
            <div className="rd-debt-sum-col">
              <span className="rd-debt-sum-lbl">Вам должны</span>
              <b className={query.data.owedKopecks > 0 ? 'rd-debt-pos' : ''}>{formatRub(query.data.owedKopecks)}</b>
            </div>
          </div>

          {query.data.people.length === 0 ? (
            <div className="rd-glass rd-empty">
              <div className="rd-title">Долгов нет</div>
              <div className="rd-sub">Появятся, когда в клубе будет сбор с вашим участием.</div>
            </div>
          ) : (
            <div className="rd-debt-list">
              {query.data.people.map((p) => (
                <PersonPlate key={p.user.id} p={p} onClick={() => { haptic.impact('light'); navigate(`/debts/with/${p.user.id}`); }} />
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
};

import { FC, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useAuthStore } from '../store/useAuthStore';
import { useDebtActionMutation, useDebtPairQuery, useSettlementMutation, type DebtAction, type SettlementAction } from '../queries/debts';
import { ApiError } from '../api/apiClient';
import { DebtRow } from '../components/debt/DebtRow';
import { Toast } from '../components/Toast';
import { formatRub } from '../utils/money';
import { initials, personName } from '../utils/skladchinaKind';

function errorMessage(e: unknown): string {
  if (e instanceof ApiError && (e.status === 400 || e.status === 403 || e.status === 409) && e.message) return e.message;
  return 'Не получилось. Попробуйте ещё раз.';
}

/**
 * Пара: все долги с одним человеком в обе стороны, внизу сальдо и кнопка, закрывающая всё разом
 * (skladchina-v3 § 2.3, § 9). Сальдо только внутри пары, через третьих ничего не схлопывается.
 */
export const DebtPairPage: FC = () => {
  useBackButton(true);
  const { userId } = useParams<{ userId: string }>();
  const haptic = useHaptic();
  const viewerId = useAuthStore((st) => st.user?.id) ?? '';
  const query = useDebtPairQuery(userId);
  const debtMut = useDebtActionMutation();
  const settlementMut = useSettlementMutation();
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  if (query.isPending) {
    return <div className="rd-page"><div className="rd-spinner-row" style={{ paddingTop: 60 }}><Spinner size="m" /></div></div>;
  }
  if (query.isError || !query.data) {
    return (
      <div className="rd-page">
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--text-dim)' }}>Не удалось загрузить долги. Попробуйте позже.</div>
      </div>
    );
  }

  const pair = query.data;
  const name = personName(pair.user);
  const settlement = pair.settlement;
  const busy = debtMut.isPending || settlementMut.isPending;
  const settlementPending = settlement !== null;
  const iAmPayee = settlement?.payeeId === viewerId;
  const empty = pair.owe.length === 0 && pair.owed.length === 0;

  const runDebt = async (debtId: string, action: DebtAction) => {
    setError(null);
    try {
      haptic.impact('medium');
      await debtMut.mutateAsync({ debtId, action });
      haptic.notify('success');
    } catch (e) {
      console.error('debt action failed', e);
      haptic.notify('error');
      setError(errorMessage(e));
    }
  };

  const runSettlement = async (action: SettlementAction, done: string, confirmText: string) => {
    if (!window.confirm(confirmText)) return;
    setError(null);
    try {
      haptic.impact('heavy');
      await settlementMut.mutateAsync(action);
      haptic.notify('success');
      setToast(done);
    } catch (e) {
      console.error('settlement failed', e);
      haptic.notify('error');
      setError(errorMessage(e));
    }
  };

  return (
    <div className="rd-page">
      <div className="rd-debt-pair-head">
        <span className="rd-av rd-debt-av rd-debt-av-lg">{pair.user.avatarUrl ? <img src={pair.user.avatarUrl} alt="" /> : initials(name)}</span>
        <div>
          <h1 className="rd-page-h" style={{ margin: 0 }}>{name}</h1>
          {pair.user.username && <div className="rd-debt-handle">@{pair.user.username}</div>}
        </div>
      </div>

      {empty && (
        <div className="rd-glass rd-empty">
          <div className="rd-title">Долгов с {name} нет</div>
        </div>
      )}

      {pair.owe.length > 0 && (
        <>
          <div className="rd-section-sub-h">Вы должны <span className="rd-count">· {formatRub(pair.oweKopecks)}</span></div>
          <div className="rd-glass" style={{ padding: '6px 12px', marginBottom: 14 }}>
            {pair.owe.map((d) => (
              <DebtRow key={d.id} debt={d} viewerId={viewerId} showContext readOnly={settlementPending} busy={busy} onAction={(a) => runDebt(d.id, a)} />
            ))}
          </div>
        </>
      )}

      {pair.owed.length > 0 && (
        <>
          <div className="rd-section-sub-h">Вам должны <span className="rd-count">· {formatRub(pair.owedKopecks)}</span></div>
          <div className="rd-glass" style={{ padding: '6px 12px', marginBottom: 14 }}>
            {pair.owed.map((d) => (
              <DebtRow key={d.id} debt={d} viewerId={viewerId} showContext readOnly={settlementPending} busy={busy} onAction={(a) => runDebt(d.id, a)} />
            ))}
          </div>
        </>
      )}

      {!empty && (
        <div className="rd-glass rd-debt-balance-box">
          <div className="rd-debt-sum-lbl">Сальдо</div>
          <div className={`rd-debt-balance-big ${pair.balanceKopecks > 0 ? 'rd-debt-pos' : pair.balanceKopecks < 0 ? 'rd-debt-neg' : ''}`}>
            {pair.balanceKopecks > 0 ? `вам должны ${formatRub(pair.balanceKopecks)}` : pair.balanceKopecks < 0 ? `вы должны ${formatRub(-pair.balanceKopecks)}` : 'по нулям'}
          </div>

          {settlement && iAmPayee && (
            <>
              <div className="rd-debt-meta" style={{ marginBottom: 10 }}>{name} говорит, что перевёл {formatRub(settlement.amountKopecks)} — сальдо по всем долгам.</div>
              <div className="rd-form-actions">
                <button type="button" className="rd-btn-primary" disabled={busy} onClick={() => runSettlement({ type: 'confirm', settlementId: settlement.id }, 'Все долги пары закрыты.', `Получили ${formatRub(settlement.amountKopecks)}? Все долги пары закроются разом.`)}>
                  Получил {formatRub(settlement.amountKopecks)}
                </button>
                <button type="button" className="rd-btn-outline" disabled={busy} onClick={() => runSettlement({ type: 'reject', settlementId: settlement.id }, 'Долги снова открыты.', 'Не получили? Долги снова станут открытыми.')}>
                  Не получил
                </button>
              </div>
            </>
          )}
          {settlement && !iAmPayee && (
            <div className="rd-debt-meta">Вы отдали {formatRub(settlement.amountKopecks)} — ждём подтверждения от {name}.</div>
          )}
          {!settlement && pair.balanceKopecks < 0 && (
            <button type="button" className="rd-btn-primary" disabled={busy} onClick={() => runSettlement({ type: 'settle', userId: pair.user.id }, `Отмечено: отдали ${formatRub(-pair.balanceKopecks)}.`, `Отдали ${formatRub(-pair.balanceKopecks)}? Все долги пары уйдут на подтверждение к ${name}.`)}>
              Отдал {formatRub(-pair.balanceKopecks)}
            </button>
          )}
          {!settlement && pair.balanceKopecks > 0 && (
            <div className="rd-debt-meta">Кнопка «Отдал» появится у {name}; по каждому долгу можно ответить и отдельно.</div>
          )}
          {error && <div className="rd-error" style={{ marginTop: 8 }}>{error}</div>}
        </div>
      )}

      {toast && <Toast message={toast} onClose={() => setToast(null)} />}
    </div>
  );
};

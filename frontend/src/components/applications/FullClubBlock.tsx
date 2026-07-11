import { FC, useState } from 'react';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useExpandAndApproveMutation } from '../../queries/applications';
import { useHaptic } from '../../hooks/useHaptic';
import { formatPeerSignal } from '../../features/applications-inbox/lib/peer-signal-format';
import { pluralRu } from '../../utils/formatters';
import type { PendingApplicationDto } from '../../types/api';

interface FullClubBlockProps {
  /** Pending-заявки ОДНОГО полного клуба (непустой список; клуб берётся из первой). */
  applications: PendingApplicationDto[];
  /** Тап по строке/крестику — существующая карточка рассмотрения (отказ там, с причиной). */
  onReview: (application: PendingApplicationDto) => void;
  /** Успешное расширение — тост наружу; кэш инвалидируется мутацией, блок рассыпается сам. */
  onExpanded: (message: string) => void;
}

/**
 * Блок заявок полного клуба в инбоксе организатора (club-invites, кадры H/I мокапа).
 * Крестик ведёт в существующую карточку рассмотрения (отказ — с обязательной причиной и DM).
 * Футер: поле нового лимита (подсказка — текущий лимит числом) + «Расширить клуб и принять
 * всех (N)» — атомарный эндпоинт expand-and-approve.
 */
export const FullClubBlock: FC<FullClubBlockProps> = ({ applications, onReview, onExpanded }) => {
  const haptic = useHaptic();
  const expandMutation = useExpandAndApproveMutation();
  const [limitInput, setLimitInput] = useState('');
  const [error, setError] = useState<string | null>(null);

  const club = applications[0]!.club;
  const count = applications.length;
  // Минимальный лимит, вмещающий всех оставшихся в блоке (валидация зеркалит бэкенд).
  const minLimit = club.memberCount + count;
  const parsedLimit = Number(limitInput);
  const limitValid = Number.isInteger(parsedLimit) && parsedLimit >= minLimit;

  const handleExpand = () => {
    if (!limitValid || expandMutation.isPending) return;
    haptic.impact('medium');
    setError(null);
    expandMutation.mutate(
      {
        clubId: club.id,
        newMemberLimit: parsedLimit,
        applicationIds: applications.map((a) => a.applicationId),
      },
      {
        onSuccess: () => {
          haptic.notify('success');
          onExpanded(`Клуб расширен до ${parsedLimit} мест, принято: ${count}`);
        },
        onError: (e) => {
          haptic.notify('error');
          setError(e instanceof Error ? e.message : 'Не удалось расширить клуб');
        },
      },
    );
  };

  return (
    <div className="rd-glass rd-fullblock">
      <div className="rd-fb-head">
        <span className="rd-fb-ava">
          {club.avatarUrl ? <img src={club.avatarUrl} alt="" /> : club.name.charAt(0).toUpperCase()}
        </span>
        <div className="rd-fb-meta">
          <div className="rd-fb-name">{club.name}</div>
          <div className="rd-fb-sub">{count} {pluralRu(count, ['заявка', 'заявки', 'заявок'])}</div>
        </div>
        <span className="rd-badge rd-warn">Клуб полон · {club.memberCount}/{club.memberLimit}</span>
      </div>

      {applications.map((p) => {
        const fullName = `${p.applicant.firstName}${p.applicant.lastName ? ` ${p.applicant.lastName}` : ''}`;
        return (
          <div key={p.applicationId} className="rd-fb-row">
            <button
              type="button"
              className="rd-fb-person"
              onClick={() => { haptic.impact('light'); onReview(p); }}
            >
              <span className="rd-fb-ava">
                {p.applicant.avatarUrl
                  ? <img src={p.applicant.avatarUrl} alt="" />
                  : fullName.charAt(0).toUpperCase()}
              </span>
              <span className="rd-fb-meta">
                <span className="rd-fb-name">{fullName}</span>
                <span className="rd-fb-sub">{formatPeerSignal(p.peerStats)}</span>
              </span>
            </button>
            <button
              type="button"
              className="rd-fullblock-x"
              aria-label={`Отклонить заявку: ${fullName}`}
              onClick={() => { haptic.impact('light'); onReview(p); }}
            >
              ✕
            </button>
          </div>
        );
      })}

      <div className="rd-fullblock-foot">
        <div className="rd-fb-label">Новый лимит участников</div>
        <input
          type="number"
          inputMode="numeric"
          className="rd-fullblock-input"
          placeholder={String(club.memberLimit)}
          value={limitInput}
          onChange={(e) => { setLimitInput(e.target.value); setError(null); }}
        />
        <div className="rd-cta-hint" style={{ textAlign: 'left' }}>
          Сейчас {club.memberLimit} {pluralRu(club.memberLimit, ['место', 'места', 'мест'])}, все заняты.
          {' '}{count} {pluralRu(count, ['заявка ждёт', 'заявки ждут', 'заявок ждут'])} —
          чтобы принять всех, укажите не меньше {minLimit}
        </div>

        {error && <div className="rd-error" style={{ textAlign: 'left' }}>{error}</div>}

        <button
          type="button"
          className="rd-btn-primary"
          style={{ marginTop: 8 }}
          disabled={!limitValid || expandMutation.isPending}
          onClick={handleExpand}
        >
          {expandMutation.isPending ? <Spinner size="s" /> : `Расширить клуб и принять всех (${count})`}
        </button>
      </div>
    </div>
  );
};

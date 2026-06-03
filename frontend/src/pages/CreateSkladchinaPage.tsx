import { FC, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { AvatarUpload } from '../components/AvatarUpload';
import { useClubMembersQuery } from '../queries/members';
import { useCreateSkladchinaMutation } from '../queries/skladchina';
import type { CreateSkladchinaRequest, SkladchinaMode } from '../types/api';

const MODE_LABELS: Record<SkladchinaMode, string> = {
  fixed_equal: 'Поровну между всеми',
  fixed_individual: 'Индивидуальная сумма каждому',
  voluntary: 'По желанию (без фикс. суммы)',
};

const MODE_DESCRIPTIONS: Record<SkladchinaMode, string> = {
  fixed_equal: 'Общая сумма делится поровну. Каждый видит свою долю.',
  fixed_individual: 'Вы задаёте сумму для каждого участника отдельно.',
  voluntary: 'Без фиксированной суммы. Участник вводит свою сумму при оплате.',
};

const CalendarIcon: FC = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="3" y="4" width="18" height="18" rx="2" />
    <path d="M16 2v4M8 2v4M3 10h18" />
  </svg>
);

function rubToKopecks(rub: string): number | null {
  const v = Number(rub.replace(',', '.').trim());
  if (!Number.isFinite(v) || v <= 0) return null;
  return Math.round(v * 100);
}

function defaultDeadlineLocal(): string {
  const d = new Date();
  d.setDate(d.getDate() + 3);
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
}

export const CreateSkladchinaPage: FC = () => {
  useBackButton(true);
  const { id: clubId } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const haptic = useHaptic();
  const membersQuery = useClubMembersQuery(clubId, { includeCancelled: true });
  const createMut = useCreateSkladchinaMutation();

  // Cancelled-but-in-period members must remain visible (so organizer sees
  // why they can't pick that person) but cannot be added to a new skladchina.
  // Sort them to the end so the active roster reads first.
  const eligibleMembers = useMemo(
    () => {
      const rows = membersQuery.data ?? [];
      return [...rows].sort((a, b) => {
        const aCanc = a.subscriptionCancelled ? 1 : 0;
        const bCanc = b.subscriptionCancelled ? 1 : 0;
        return aCanc - bCanc;
      });
    },
    [membersQuery.data],
  );

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [rules, setRules] = useState('');
  const [photoUrl, setPhotoUrl] = useState<string | null>(null);
  const [mode, setMode] = useState<SkladchinaMode>('fixed_equal');
  const [totalRub, setTotalRub] = useState('');
  const [paymentLink, setPaymentLink] = useState('');
  const [paymentMethodNote, setPaymentMethodNote] = useState('');
  const [deadline, setDeadline] = useState(defaultDeadlineLocal());
  const [affectsReputation, setAffectsReputation] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [individualAmounts, setIndividualAmounts] = useState<Record<string, string>>({});
  const [submitError, setSubmitError] = useState<string | null>(null);

  if (!clubId) {
    return (
      <div className="rd-page">
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--text-dim)' }}>
          Клуб не найден
        </div>
      </div>
    );
  }

  const toggleParticipant = (userId: string) => {
    const member = eligibleMembers.find((m) => m.userId === userId);
    if (member?.subscriptionCancelled) return;
    haptic.select();
    const next = new Set(selectedIds);
    if (next.has(userId)) next.delete(userId);
    else next.add(userId);
    setSelectedIds(next);
  };

  const fail = (msg: string) => {
    haptic.notify('error');
    setSubmitError(msg);
  };

  const handleSubmit = async () => {
    setSubmitError(null);
    if (!title.trim()) return fail('Введите название сбора');
    if (!paymentLink.trim()) return fail('Укажите платёжную ссылку');
    if (selectedIds.size === 0) return fail('Выберите хотя бы одного участника');

    let totalKopecks: number | null = null;
    if (mode === 'fixed_equal') {
      totalKopecks = rubToKopecks(totalRub);
      if (totalKopecks === null) return fail('Укажите общую сумму (₽)');
    }

    const participants = Array.from(selectedIds).map((userId) => {
      if (mode === 'fixed_individual') {
        const amount = rubToKopecks(individualAmounts[userId] ?? '');
        return { userId, expectedAmountKopecks: amount };
      }
      return { userId, expectedAmountKopecks: null };
    });

    if (mode === 'fixed_individual') {
      const missing = participants.find((p) => p.expectedAmountKopecks === null);
      if (missing) return fail('Укажите сумму для каждого участника');
    }

    const deadlineIso = new Date(deadline).toISOString();
    const body: CreateSkladchinaRequest = {
      title: title.trim(),
      description: description.trim() || null,
      rules: rules.trim() || null,
      photoUrl,
      paymentMode: mode,
      totalGoalKopecks: mode === 'fixed_equal' ? totalKopecks : null,
      paymentLink: paymentLink.trim(),
      paymentMethodNote: paymentMethodNote.trim() || null,
      deadline: deadlineIso,
      affectsReputation,
      participants,
    };

    try {
      haptic.impact('medium');
      const created = await createMut.mutateAsync({ clubId, body });
      haptic.notify('success');
      navigate(`/skladchina/${created.id}`, { replace: true });
    } catch (e) {
      console.error('createSkladchina failed', e);
      haptic.notify('error');
      setSubmitError('Не удалось создать сбор. Проверьте поля и попробуйте снова.');
    }
  };

  const handleCancel = () => {
    haptic.impact('light');
    navigate(-1);
  };

  return (
    <div className="rd-page">
      <div className="rd-ft-eyebrow">Создание</div>
      <h1 className="rd-page-h" style={{ marginBottom: 18 }}>Новый сбор</h1>

      <div className="rd-form">
        <label className="rd-field">
          <span className="rd-label">Название <span className="rd-req">*</span></span>
          <input className="rd-input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={255} />
        </label>

        <label className="rd-field">
          <span className="rd-label">Описание</span>
          <textarea className="rd-textarea" value={description} onChange={(e) => setDescription(e.target.value)} rows={3} />
        </label>

        <label className="rd-field">
          <span className="rd-label">Правила (опц.)</span>
          <textarea className="rd-textarea" value={rules} onChange={(e) => setRules(e.target.value)} rows={2} />
        </label>

        <div className="rd-field">
          <span className="rd-label">Фото (опц.)</span>
          <AvatarUpload value={photoUrl} onChange={setPhotoUrl} />
        </div>

        <div className="rd-field">
          <span className="rd-label">Порядок сбора <span className="rd-req">*</span></span>
          <div className="rd-mode-list">
            {(Object.keys(MODE_LABELS) as SkladchinaMode[]).map((m) => (
              <label key={m} className={mode === m ? 'rd-mode-option rd-active' : 'rd-mode-option'}>
                <input
                  type="radio"
                  name="mode"
                  checked={mode === m}
                  onChange={() => setMode(m)}
                />
                <div>
                  <div className="rd-mo-title">{MODE_LABELS[m]}</div>
                  <div className="rd-mo-desc">{MODE_DESCRIPTIONS[m]}</div>
                </div>
              </label>
            ))}
          </div>
        </div>

        {mode === 'fixed_equal' && (
          <label className="rd-field">
            <span className="rd-label">Общая сумма (₽) <span className="rd-req">*</span></span>
            <input
              className="rd-input"
              type="number"
              inputMode="decimal"
              min="1"
              value={totalRub}
              onChange={(e) => setTotalRub(e.target.value)}
              placeholder="Например, 5000"
            />
          </label>
        )}

        <label className="rd-field">
          <span className="rd-label">Платёжная ссылка <span className="rd-req">*</span></span>
          <input
            className="rd-input"
            value={paymentLink}
            onChange={(e) => setPaymentLink(e.target.value)}
            placeholder="https://www.tinkoff.ru/cf/…"
          />
          <span className="rd-hint">⚠️ Ссылка будет видна всем участникам сбора</span>
        </label>

        <label className="rd-field">
          <span className="rd-label">Банк / способ (опц.)</span>
          <input
            className="rd-input"
            value={paymentMethodNote}
            onChange={(e) => setPaymentMethodNote(e.target.value)}
            placeholder="Тинькофф, СБП, ВТБ…"
          />
        </label>

        <label className="rd-field">
          <span className="rd-label">Срок до <span className="rd-req">*</span></span>
          <div className="rd-datetime">
            <input
              className="rd-input"
              type="datetime-local"
              value={deadline}
              onChange={(e) => setDeadline(e.target.value)}
            />
            <span className="rd-dt-ico" aria-hidden="true"><CalendarIcon /></span>
          </div>
        </label>

        <label className="rd-check">
          <input
            type="checkbox"
            checked={affectsReputation}
            onChange={(e) => setAffectsReputation(e.target.checked)}
          />
          <span>Влияет на репутацию участников (за неответ −25, за отказ −5)</span>
        </label>

        <div className="rd-field">
          <span className="rd-label">
            Участники <span className="rd-req">*</span> <span className="rd-count">· выбрано {selectedIds.size}</span>
          </span>
          {membersQuery.isPending && <Spinner size="s" />}
          {!membersQuery.isPending && eligibleMembers.length === 0 && (
            <div className="rd-hint">В клубе пока нет активных участников.</div>
          )}
          {eligibleMembers.length > 0 && (
            <div className="rd-pick-list">
              {eligibleMembers.map((m) => {
                const isSelected = selectedIds.has(m.userId);
                const isCancelled = m.subscriptionCancelled === true;
                return (
                  <div key={m.userId} className="rd-pick-row">
                    <button
                      type="button"
                      className={`rd-pick-toggle${isSelected ? ' rd-selected' : ''}`}
                      onClick={() => toggleParticipant(m.userId)}
                      disabled={isCancelled}
                      aria-disabled={isCancelled}
                    >
                      <span className="rd-check-box">{isSelected ? '✓' : ''}</span>
                      <span className="rd-pick-name">
                        {m.firstName}{m.lastName ? ` ${m.lastName}` : ''}
                      </span>
                      {isCancelled && (
                        <span className="rd-pick-note">Отменил подписку</span>
                      )}
                    </button>
                    {!isCancelled && mode === 'fixed_individual' && isSelected && (
                      <input
                        type="number"
                        inputMode="decimal"
                        min="1"
                        placeholder="₽"
                        className="rd-input rd-pick-amount"
                        value={individualAmounts[m.userId] ?? ''}
                        onChange={(e) =>
                          setIndividualAmounts((prev) => ({ ...prev, [m.userId]: e.target.value }))
                        }
                      />
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {submitError && <div className="rd-error">{submitError}</div>}

        <div className="rd-form-actions">
          <button type="button" className="rd-btn-outline" onClick={handleCancel}>Отмена</button>
          <button
            type="button"
            className="rd-btn-primary"
            onClick={handleSubmit}
            disabled={createMut.isPending}
          >
            {createMut.isPending ? 'Создаём…' : 'Создать сбор'}
          </button>
        </div>
      </div>
    </div>
  );
};

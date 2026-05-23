import { FC, useMemo, useState } from 'react';
import { Modal, Spinner } from '@telegram-apps/telegram-ui';
import { useClubMembersQuery } from '../../queries/members';
import { useCreateSkladchinaMutation } from '../../queries/skladchina';
import { useHaptic } from '../../hooks/useHaptic';
import { Toast } from '../Toast';
import type { CreateSkladchinaRequest, SkladchinaMode } from '../../types/api';

interface CreateSkladchinaModalProps {
  clubId: string;
  organizerUserId: string;
  onClose: () => void;
  onCreated: (skladchinaId: string) => void;
}

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

export const CreateSkladchinaModal: FC<CreateSkladchinaModalProps> = ({
  clubId,
  organizerUserId,
  onClose,
  onCreated,
}) => {
  const haptic = useHaptic();
  const membersQuery = useClubMembersQuery(clubId);
  const createMut = useCreateSkladchinaMutation();

  // Members minus the organizer (organizer doesn't participate in own collection by default)
  const eligibleMembers = useMemo(
    () => (membersQuery.data ?? []).filter((m) => m.userId !== organizerUserId),
    [membersQuery.data, organizerUserId],
  );

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [rules, setRules] = useState('');
  const [photoUrl, setPhotoUrl] = useState('');
  const [mode, setMode] = useState<SkladchinaMode>('fixed_equal');
  const [totalRub, setTotalRub] = useState('');
  const [paymentLink, setPaymentLink] = useState('');
  const [paymentMethodNote, setPaymentMethodNote] = useState('');
  const [deadline, setDeadline] = useState(defaultDeadlineLocal());
  const [affectsReputation, setAffectsReputation] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [individualAmounts, setIndividualAmounts] = useState<Record<string, string>>({});

  const [submitError, setSubmitError] = useState<string | null>(null);
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  const toggleParticipant = (userId: string) => {
    haptic.select();
    const next = new Set(selectedIds);
    if (next.has(userId)) next.delete(userId);
    else next.add(userId);
    setSelectedIds(next);
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
      photoUrl: photoUrl.trim() || null,
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
      onCreated(created.id);
    } catch (e) {
      console.error('createSkladchina failed', e);
      haptic.notify('error');
      setSubmitError('Не удалось создать сбор. Проверьте поля и попробуйте снова.');
    }
  };

  const fail = (msg: string) => {
    haptic.notify('error');
    setSubmitError(msg);
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <div className="sklad-create-modal">
        <h2>Новый сбор</h2>

        <label className="field">
          <span className="label">Название *</span>
          <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={255} />
        </label>

        <label className="field">
          <span className="label">Описание</span>
          <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3} />
        </label>

        <label className="field">
          <span className="label">Правила (опц.)</span>
          <textarea value={rules} onChange={(e) => setRules(e.target.value)} rows={2} />
        </label>

        <label className="field">
          <span className="label">Ссылка на фото (опц.)</span>
          <input value={photoUrl} onChange={(e) => setPhotoUrl(e.target.value)} placeholder="https://…" />
        </label>

        <div className="field">
          <span className="label">Порядок сбора *</span>
          <div className="mode-options">
            {(Object.keys(MODE_LABELS) as SkladchinaMode[]).map((m) => (
              <label key={m} className={mode === m ? 'mode-option active' : 'mode-option'}>
                <input
                  type="radio"
                  name="mode"
                  checked={mode === m}
                  onChange={() => setMode(m)}
                />
                <div className="mode-text">
                  <div className="mode-title">{MODE_LABELS[m]}</div>
                  <div className="mode-desc">{MODE_DESCRIPTIONS[m]}</div>
                </div>
              </label>
            ))}
          </div>
        </div>

        {mode === 'fixed_equal' && (
          <label className="field">
            <span className="label">Общая сумма (₽) *</span>
            <input
              type="number"
              inputMode="decimal"
              min="1"
              value={totalRub}
              onChange={(e) => setTotalRub(e.target.value)}
              placeholder="Например, 5000"
            />
          </label>
        )}

        <label className="field">
          <span className="label">Платёжная ссылка *</span>
          <input
            value={paymentLink}
            onChange={(e) => setPaymentLink(e.target.value)}
            placeholder="https://www.tinkoff.ru/cf/…"
          />
          <span className="hint">⚠️ Ссылка будет видна всем участникам сбора</span>
        </label>

        <label className="field">
          <span className="label">Банк / способ (опц.)</span>
          <input
            value={paymentMethodNote}
            onChange={(e) => setPaymentMethodNote(e.target.value)}
            placeholder="Тинькофф, СБП, ВТБ…"
          />
        </label>

        <label className="field">
          <span className="label">Срок до *</span>
          <input
            type="datetime-local"
            value={deadline}
            onChange={(e) => setDeadline(e.target.value)}
          />
        </label>

        <label className="field checkbox">
          <input
            type="checkbox"
            checked={affectsReputation}
            onChange={(e) => setAffectsReputation(e.target.checked)}
          />
          <span>Влияет на репутацию участников (за неответ −25)</span>
        </label>

        <div className="field">
          <span className="label">Участники * <span className="count">· выбрано {selectedIds.size}</span></span>
          {membersQuery.isPending && <Spinner size="s" />}
          {!membersQuery.isPending && eligibleMembers.length === 0 && (
            <div className="hint">В клубе пока нет других активных участников.</div>
          )}
          {eligibleMembers.length > 0 && (
            <div className="participants-list">
              {eligibleMembers.map((m) => {
                const isSelected = selectedIds.has(m.userId);
                return (
                  <div key={m.userId} className={isSelected ? 'p-row selected' : 'p-row'}>
                    <button
                      type="button"
                      className="p-toggle"
                      onClick={() => toggleParticipant(m.userId)}
                    >
                      <span className="check">{isSelected ? '✓' : ''}</span>
                      <span className="name">
                        {m.firstName}{m.lastName ? ` ${m.lastName}` : ''}
                      </span>
                    </button>
                    {mode === 'fixed_individual' && isSelected && (
                      <input
                        type="number"
                        inputMode="decimal"
                        min="1"
                        placeholder="₽"
                        className="individual-amount"
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

        {submitError && <div className="submit-error">{submitError}</div>}

        <div className="modal-actions">
          <button type="button" className="ghost-btn" onClick={onClose}>Отмена</button>
          <button
            type="button"
            className="primary-btn"
            onClick={handleSubmit}
            disabled={createMut.isPending}
          >
            {createMut.isPending ? 'Создаём…' : 'Создать сбор'}
          </button>
        </div>

        {toastMessage && <Toast message={toastMessage} onClose={() => setToastMessage(null)} />}
      </div>
    </Modal>
  );
};

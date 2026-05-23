import { FC, useMemo, useState } from 'react';
import {
  Modal,
  Spinner,
  Input,
  Textarea,
  Select,
  Checkbox,
} from '@telegram-apps/telegram-ui';
import { useClubMembersQuery } from '../../queries/members';
import { useCreateSkladchinaMutation } from '../../queries/skladchina';
import { useHaptic } from '../../hooks/useHaptic';
import type { CreateSkladchinaRequest, SkladchinaMode } from '../../types/api';

interface CreateSkladchinaModalProps {
  clubId: string;
  // organizerUserId сохранён в типе для будущих расширений (например, organizer-default),
  // но сейчас не используется — organizer может быть участником сбора как любой member.
  organizerUserId: string;
  onClose: () => void;
  onCreated: (skladchinaId: string) => void;
}

const MODE_OPTIONS: Array<{ value: SkladchinaMode; label: string; desc: string }> = [
  { value: 'fixed_equal',      label: 'Поровну между всеми',           desc: 'Общая сумма делится поровну. Каждый видит свою долю.' },
  { value: 'fixed_individual', label: 'Индивидуальная сумма каждому',  desc: 'Вы задаёте сумму для каждого участника отдельно.' },
  { value: 'voluntary',        label: 'По желанию (без фикс. суммы)',  desc: 'Участник вводит свою сумму при оплате.' },
];

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
  organizerUserId: _organizerUserId,
  onClose,
  onCreated,
}) => {
  const haptic = useHaptic();
  const membersQuery = useClubMembersQuery(clubId);
  const createMut = useCreateSkladchinaMutation();

  // Все active members клуба, включая organizer'а — он тоже может скинуться.
  const eligibleMembers = useMemo(
    () => membersQuery.data ?? [],
    [membersQuery.data],
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

  const toggleParticipant = (userId: string) => {
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

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <div className="sklad-create-modal">
        <h2>Новый сбор</h2>

        <Input
          header="Название *"
          placeholder="Бронь корта"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          maxLength={255}
        />

        <Textarea
          header="Описание"
          placeholder="Зачем сбор и что собираем"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          rows={3}
        />

        <Textarea
          header="Правила (опц.)"
          placeholder="Например: возврат при отмене"
          value={rules}
          onChange={(e) => setRules(e.target.value)}
          rows={2}
        />

        <Input
          header="Ссылка на фото (опц.)"
          placeholder="https://…"
          value={photoUrl}
          onChange={(e) => setPhotoUrl(e.target.value)}
        />

        <Select
          header="Порядок сбора *"
          value={mode}
          onChange={(e) => setMode(e.target.value as SkladchinaMode)}
        >
          {MODE_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </Select>
        <div className="mode-hint">
          {MODE_OPTIONS.find((o) => o.value === mode)?.desc}
        </div>

        {mode === 'fixed_equal' && (
          <Input
            header="Общая сумма (₽) *"
            type="number"
            inputMode="decimal"
            placeholder="5000"
            value={totalRub}
            onChange={(e) => setTotalRub(e.target.value)}
          />
        )}

        <Input
          header="Платёжная ссылка *"
          placeholder="https://www.tinkoff.ru/cf/…"
          value={paymentLink}
          onChange={(e) => setPaymentLink(e.target.value)}
        />
        <div className="modal-hint">⚠️ Ссылка будет видна всем участникам сбора</div>

        <Input
          header="Банк / способ (опц.)"
          placeholder="Тинькофф, СБП…"
          value={paymentMethodNote}
          onChange={(e) => setPaymentMethodNote(e.target.value)}
        />

        <Input
          header="Срок до *"
          type="datetime-local"
          value={deadline}
          onChange={(e) => setDeadline(e.target.value)}
        />

        <div className="reputation-toggle">
          <Checkbox
            checked={affectsReputation}
            onChange={(e) => setAffectsReputation(e.target.checked)}
          />
          <div className="reputation-toggle-text">
            <div className="title">Влияет на репутацию</div>
            <div className="sub">−5 за отказ, −25 за молчание</div>
          </div>
        </div>

        <div className="participants-block">
          <div className="participants-label">
            Участники * <span className="count">· выбрано {selectedIds.size}</span>
          </div>
          {membersQuery.isPending && <Spinner size="s" />}
          {!membersQuery.isPending && eligibleMembers.length === 0 && (
            <div className="modal-hint">В клубе пока нет активных участников.</div>
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
                      <Input
                        type="number"
                        inputMode="decimal"
                        placeholder="₽"
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
      </div>
    </Modal>
  );
};

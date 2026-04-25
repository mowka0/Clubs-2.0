import { FC, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  List,
  Section,
  Cell,
  Button,
  Input,
  Select,
  Textarea,
  Spinner,
  Modal,
  Placeholder,
} from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import { useClubsStore } from '../store/useClubsStore';
import { AvatarUpload } from '../components/AvatarUpload';
import { createClub, getClub } from '../api/clubs';
import type { CreateClubBody } from '../api/clubs';
import type { ClubDetailDto } from '../types/api';
import { validateStep } from '../utils/validators';
import type { ClubFormData } from '../utils/validators';

const CATEGORIES = [
  { value: 'sport', label: 'Спорт' },
  { value: 'creative', label: 'Творчество' },
  { value: 'food', label: 'Еда' },
  { value: 'board_games', label: 'Настолки' },
  { value: 'cinema', label: 'Кино' },
  { value: 'education', label: 'Образование' },
  { value: 'travel', label: 'Путешествия' },
  { value: 'other', label: 'Другое' },
];

const STEP_TITLES = ['Основное', 'Категория', 'Участники', 'Описание', 'Заявка'];

type FormData = ClubFormData;

const INITIAL_FORM: FormData = {
  name: '',
  city: '',
  district: '',
  category: 'other',
  accessType: 'open',
  memberLimit: '30',
  subscriptionPrice: '0',
  description: '',
  rules: '',
  applicationQuestion: '',
};

export const CreateClubModal: FC<{ onClose: () => void; onCreated: (id: string) => void }> = ({ onClose, onCreated }) => {
  const haptic = useHaptic();
  const [step, setStep] = useState(0);
  const [form, setForm] = useState<FormData>(INITIAL_FORM);
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const update = (field: keyof FormData, value: string) => setForm((f) => ({ ...f, [field]: value }));

  const monthlyIncome = Math.round(Number(form.memberLimit) * Number(form.subscriptionPrice) * 0.8);

  const handleNext = () => {
    const err = validateStep(step, form);
    if (err) { setError(err); return; }
    haptic.impact('light');
    setError(null);
    setStep((s) => s + 1);
  };

  const handleSubmit = async () => {
    const err = validateStep(step, form);
    if (err) { setError(err); return; }
    haptic.impact('heavy');
    setSubmitting(true);
    setError(null);
    try {
      const body: CreateClubBody = {
        name: form.name.trim(),
        description: form.description.trim(),
        category: form.category,
        accessType: form.accessType,
        city: form.city.trim(),
        district: form.district.trim() || undefined,
        memberLimit: Number(form.memberLimit),
        subscriptionPrice: Number(form.subscriptionPrice),
        avatarUrl: avatarUrl ?? undefined,
        rules: form.rules.trim() || undefined,
        applicationQuestion: (form.accessType === 'closed' && form.applicationQuestion.trim())
          ? form.applicationQuestion.trim()
          : undefined,
      };
      const club = await createClub(body);
      haptic.notify('success');
      onCreated(club.id);
    } catch (e) {
      setError((e as Error).message);
      haptic.notify('error');
      setSubmitting(false);
    }
  };

  return (
    <div style={{ padding: 16 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <span style={{ fontSize: 13, color: 'var(--tgui--hint_color)' }}>Шаг {step + 1} из {STEP_TITLES.length}: {STEP_TITLES[step]}</span>
        <button onClick={onClose} style={{ background: 'none', border: 'none', fontSize: 20, cursor: 'pointer', color: 'var(--tgui--text_color)' }}>&#x2715;</button>
      </div>

      {error && (
        <div style={{ padding: '8px 12px', borderRadius: 8, background: 'rgba(255,59,48,0.1)', color: 'var(--tgui--destructive_text_color)', marginBottom: 12, fontSize: 14 }}>
          {error}
        </div>
      )}

      {step === 0 && (
        <Section>
          <Input header="Название клуба *" placeholder="Например: Книжный клуб Москвы" value={form.name} onChange={(e) => update('name', e.target.value)} />
          <Input header="Город *" placeholder="Москва" value={form.city} onChange={(e) => update('city', e.target.value)} />
          <Input header="Район (необязательно)" placeholder="Центральный" value={form.district} onChange={(e) => update('district', e.target.value)} />
        </Section>
      )}

      {step === 1 && (
        <Section>
          <Select header="Категория *" value={form.category} onChange={(e) => update('category', e.target.value)}>
            {CATEGORIES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
          </Select>
          <Cell
            Component="label"
            after={<input type="radio" checked={form.accessType === 'open'} onChange={() => update('accessType', 'open')} />}
            description="Любой желающий может вступить"
          >
            Открытый клуб
          </Cell>
          <Cell
            Component="label"
            after={<input type="radio" checked={form.accessType === 'closed'} onChange={() => update('accessType', 'closed')} />}
            description="Вступление по заявке (организатор одобряет)"
          >
            Закрытый клуб
          </Cell>
        </Section>
      )}

      {step === 2 && (
        <Section>
          <Input header="Лимит участников *" type="number" placeholder="30" value={form.memberLimit} onChange={(e) => update('memberLimit', e.target.value)} />
          <Input header="Цена подписки (Stars/мес)" type="number" placeholder="0 — бесплатно" value={form.subscriptionPrice} onChange={(e) => update('subscriptionPrice', e.target.value)} />
          {Number(form.subscriptionPrice) > 0 && Number(form.memberLimit) > 0 && (
            <Cell description={`При ${form.memberLimit} участниках вы будете зарабатывать ${monthlyIncome} Stars в месяц (80% от дохода)`}>
              Доход организатора
            </Cell>
          )}
        </Section>
      )}

      {step === 3 && (
        <Section>
          <div style={{ padding: 16 }}>
            <div style={{ fontSize: 13, color: 'var(--tgui--hint_color)', marginBottom: 8 }}>Аватар (необязательно)</div>
            <AvatarUpload value={avatarUrl} onChange={setAvatarUrl} disabled={submitting} />
          </div>
          <Textarea header="Описание клуба *" placeholder="Расскажите о своём клубе (10-500 символов)" value={form.description} onChange={(e) => update('description', e.target.value)} />
          <Textarea header="Правила (необязательно)" placeholder="Правила сообщества" value={form.rules} onChange={(e) => update('rules', e.target.value)} />
        </Section>
      )}

      {step === 4 && (
        <Section>
          {form.accessType === 'closed' ? (
            <Input
              header="Вопрос для вступления (необязательно)"
              placeholder="Почему вы хотите вступить?"
              value={form.applicationQuestion}
              onChange={(e) => update('applicationQuestion', e.target.value)}
            />
          ) : (
            <Placeholder description="Для открытого клуба вопрос при вступлении не нужен" />
          )}
        </Section>
      )}

      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        {step > 0 && (
          <Button size="m" mode="outline" onClick={() => { haptic.impact('light'); setError(null); setStep((s) => s - 1); }} stretched>
            Назад
          </Button>
        )}
        {step < STEP_TITLES.length - 1 ? (
          <Button size="m" onClick={handleNext} stretched>
            Далее
          </Button>
        ) : (
          <Button size="m" onClick={handleSubmit} disabled={submitting} stretched>
            {submitting ? <Spinner size="s" /> : 'Создать клуб'}
          </Button>
        )}
      </div>
    </div>
  );
};

export const OrganizerPage: FC = () => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  const { myClubs, loading, fetchMyClubs } = useClubsStore();
  const [showModal, setShowModal] = useState(false);
  const [clubDetails, setClubDetails] = useState<Record<string, ClubDetailDto>>({});

  useEffect(() => {
    fetchMyClubs();
  }, [fetchMyClubs]);

  const ownedClubs = myClubs.filter((m) => m.role === 'organizer');

  // Load club names for owned clubs
  useEffect(() => {
    const missing = ownedClubs.map((m) => m.clubId).filter((id) => !clubDetails[id]);
    if (missing.length === 0) return;
    missing.forEach((id) => {
      getClub(id)
        .then((club) => setClubDetails((prev) => ({ ...prev, [id]: club })))
        .catch(() => {});
    });
  }, [ownedClubs]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleCreated = async (id: string) => {
    setShowModal(false);
    await fetchMyClubs();
    navigate(`/clubs/${id}/manage`);
  };

  return (
    <List>
      <Section header="Мои клубы">
        {loading && <div style={{ display: 'flex', justifyContent: 'center', padding: 24 }}><Spinner size="m" /></div>}
        {!loading && ownedClubs.length === 0 && (
          <Placeholder description="У вас пока нет клубов. Создайте первый!" />
        )}
        {ownedClubs.map((m) => (
          <Cell
            key={m.id}
            onClick={() => { haptic.impact('light'); navigate(`/clubs/${m.clubId}/manage`); }}
            subtitle="Организатор"
          >
            {clubDetails[m.clubId]?.name ?? `Клуб ${m.clubId.slice(0, 8)}...`}
          </Cell>
        ))}
      </Section>

      <Section>
        <Button size="l" stretched onClick={() => setShowModal(true)}>
          + Создать новый клуб
        </Button>
      </Section>

      {showModal && (
        <Modal open onOpenChange={(open) => !open && setShowModal(false)}>
          <CreateClubModal onClose={() => setShowModal(false)} onCreated={handleCreated} />
        </Modal>
      )}
    </List>
  );
};

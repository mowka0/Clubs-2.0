import { FC, useState } from 'react';
import { useForm } from 'react-hook-form';
import {
  Section,
  Cell,
  Button,
  Input,
  Select,
  Textarea,
  Spinner,
  Placeholder,
} from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import { useCreateClubMutation } from '../queries/clubs';
import { AvatarUpload } from './AvatarUpload';
import type { CreateClubBody } from '../api/clubs';

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

interface ClubFormValues {
  name: string;
  city: string;
  district: string;
  category: string;
  accessType: 'open' | 'closed';
  memberLimit: string;
  subscriptionPrice: string;
  description: string;
  rules: string;
  applicationQuestion: string;
}

const STEP_FIELDS: Array<Array<keyof ClubFormValues>> = [
  ['name', 'city'],
  ['category', 'accessType'],
  ['memberLimit', 'subscriptionPrice'],
  ['description'],
  [],
];

const FieldError: FC<{ message?: string }> = ({ message }) =>
  message ? (
    <div style={{ color: 'var(--tgui--destructive_text_color)', fontSize: 13, padding: '4px 16px 0' }}>
      {message}
    </div>
  ) : null;

export const CreateClubModal: FC<{ onClose: () => void; onCreated: (id: string) => void }> = ({ onClose, onCreated }) => {
  const haptic = useHaptic();
  const createClubMutation = useCreateClubMutation();
  const [step, setStep] = useState(0);
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    trigger,
    watch,
    formState: { errors },
  } = useForm<ClubFormValues>({
    mode: 'onTouched',
    defaultValues: {
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
    },
  });

  const submitting = createClubMutation.isPending;

  const memberLimit = watch('memberLimit');
  const subscriptionPrice = watch('subscriptionPrice');
  const accessType = watch('accessType');

  const monthlyIncome = Math.round(Number(memberLimit) * Number(subscriptionPrice) * 0.8);

  const handleNext = async () => {
    const fields = STEP_FIELDS[step];
    const valid = fields && fields.length > 0 ? await trigger(fields) : true;
    if (!valid) {
      haptic.notify('error');
      return;
    }
    haptic.impact('light');
    setStep((s) => s + 1);
  };

  // RHF invokes this when handleSubmit() detects validation errors.
  // Mirrors handleNext fail-path so users feel the same haptic on the final step.
  const onInvalid = () => {
    haptic.notify('error');
  };

  const onValid = (data: ClubFormValues) => {
    haptic.impact('heavy');
    setError(null);
    const body: CreateClubBody = {
      name: data.name.trim(),
      description: data.description.trim(),
      category: data.category,
      accessType: data.accessType,
      city: data.city.trim(),
      district: data.district.trim() || undefined,
      memberLimit: Number(data.memberLimit),
      subscriptionPrice: Number(data.subscriptionPrice),
      avatarUrl: avatarUrl ?? undefined,
      rules: data.rules.trim() || undefined,
      applicationQuestion: (data.accessType === 'closed' && data.applicationQuestion.trim())
        ? data.applicationQuestion.trim()
        : undefined,
    };
    createClubMutation.mutate(body, {
      onSuccess: (club) => {
        haptic.notify('success');
        onCreated(club.id);
      },
      onError: (e) => {
        setError(e.message);
        haptic.notify('error');
      },
    });
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
          <Input
            header="Название клуба *"
            placeholder="Например: Книжный клуб Москвы"
            status={errors.name ? 'error' : undefined}
            {...register('name', {
              validate: (v) => {
                const t = v.trim();
                if (t.length < 3) return 'Название: минимум 3 символа';
                if (t.length > 60) return 'Название: максимум 60 символов';
                return true;
              },
            })}
          />
          <FieldError message={errors.name?.message} />
          <Input
            header="Город *"
            placeholder="Москва"
            status={errors.city ? 'error' : undefined}
            {...register('city', {
              validate: (v) => v.trim().length > 0 || 'Укажите город',
            })}
          />
          <FieldError message={errors.city?.message} />
          <Input
            header="Район (необязательно)"
            placeholder="Центральный"
            {...register('district')}
          />
        </Section>
      )}

      {step === 1 && (
        <Section>
          <Select header="Категория *" {...register('category', { required: 'Выберите категорию' })}>
            {CATEGORIES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
          </Select>
          <FieldError message={errors.category?.message} />
          <Cell
            Component="label"
            after={<input type="radio" value="open" {...register('accessType')} />}
            description="Любой желающий может вступить"
          >
            Открытый клуб
          </Cell>
          <Cell
            Component="label"
            after={<input type="radio" value="closed" {...register('accessType')} />}
            description="Вступление по заявке (организатор одобряет)"
          >
            Закрытый клуб
          </Cell>
        </Section>
      )}

      {step === 2 && (
        <Section>
          <Input
            header="Лимит участников *"
            type="number"
            placeholder="30"
            status={errors.memberLimit ? 'error' : undefined}
            {...register('memberLimit', {
              validate: (v) => {
                const n = Number(v);
                // Aligned with backend Bean Validation in CreateClubRequest.kt (10-80).
                if (!v || !Number.isFinite(n) || n < 10 || n > 80) return 'Лимит участников: 10–80';
                if (!Number.isInteger(n)) return 'Лимит участников: 10–80';
                return true;
              },
            })}
          />
          <FieldError message={errors.memberLimit?.message} />
          <Input
            header="Цена подписки (Stars/мес)"
            type="number"
            placeholder="0 — бесплатно"
            status={errors.subscriptionPrice ? 'error' : undefined}
            {...register('subscriptionPrice', {
              validate: (v) => {
                const n = Number(v);
                if (v === '' || !Number.isFinite(n) || n < 0) return 'Укажите корректную цену';
                if (!Number.isInteger(n)) return 'Цена должна быть целым числом';
                return true;
              },
            })}
          />
          <FieldError message={errors.subscriptionPrice?.message} />
          {Number(subscriptionPrice) > 0 && Number(memberLimit) > 0 && (
            <Cell description={`При ${memberLimit} участниках вы будете зарабатывать ${monthlyIncome} Stars в месяц (80% от дохода)`}>
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
          <Textarea
            header="Описание клуба *"
            placeholder="Расскажите о своём клубе (10-500 символов)"
            status={errors.description ? 'error' : undefined}
            {...register('description', {
              validate: (v) => {
                const t = v.trim();
                if (t.length < 10) return 'Описание: минимум 10 символов';
                if (t.length > 500) return 'Описание: максимум 500 символов';
                return true;
              },
            })}
          />
          <FieldError message={errors.description?.message} />
          <Textarea
            header="Правила (необязательно)"
            placeholder="Правила сообщества"
            {...register('rules')}
          />
        </Section>
      )}

      {step === 4 && (
        <Section>
          {accessType === 'closed' ? (
            <Input
              header="Вопрос для вступления (необязательно)"
              placeholder="Почему вы хотите вступить?"
              {...register('applicationQuestion')}
            />
          ) : (
            <Placeholder description="Для открытого клуба вопрос при вступлении не нужен" />
          )}
        </Section>
      )}

      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        {step > 0 && (
          <Button size="m" mode="outline" onClick={() => { haptic.impact('light'); setStep((s) => s - 1); }} stretched>
            Назад
          </Button>
        )}
        {step < STEP_TITLES.length - 1 ? (
          <Button size="m" onClick={handleNext} stretched>
            Далее
          </Button>
        ) : (
          <Button size="m" onClick={handleSubmit(onValid, onInvalid)} disabled={submitting} stretched>
            {submitting ? <Spinner size="s" /> : 'Создать клуб'}
          </Button>
        )}
      </div>
    </div>
  );
};

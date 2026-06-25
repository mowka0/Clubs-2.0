import { FC, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import { useCreateClubMutation } from '../queries/clubs';
import { useSubscribeMutation } from '../queries/subscription';
import { paywallFromError, type PaywallInfo } from '../api/subscription';
import { PaywallModal } from './subscription/PaywallModal';
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
    <div style={{ color: 'var(--danger)', fontSize: 13, paddingLeft: 2 }}>
      {message}
    </div>
  ) : null;

export const CreateClubModal: FC<{ onClose: () => void; onCreated: (id: string) => void }> = ({ onClose, onCreated }) => {
  const haptic = useHaptic();
  const createClubMutation = useCreateClubMutation();
  const subscribeMutation = useSubscribeMutation();
  const [step, setStep] = useState(0);
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Paywall state: the backend returns 402 when a paid club exceeds the plan ceiling. We stash the
  // pending club body, show the plan ladder, and on a successful subscribe retry the create.
  const [paywall, setPaywall] = useState<PaywallInfo | null>(null);
  const [pendingBody, setPendingBody] = useState<CreateClubBody | null>(null);
  const [paywallError, setPaywallError] = useState<string | null>(null);
  const [submittingPlan, setSubmittingPlan] = useState<string | null>(null);

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
        const pw = paywallFromError(e);
        if (pw) {
          setPendingBody(body);
          setPaywall(pw);
          setPaywallError(null);
          haptic.notify('warning');
          return;
        }
        setError(e instanceof Error ? e.message : 'Не удалось создать клуб');
        haptic.notify('error');
      },
    });
  };

  // Paywall: subscribe to the chosen plan, then retry the stashed create.
  const handleSelectPlan = (plan: string) => {
    if (!pendingBody) return;
    setPaywallError(null);
    setSubmittingPlan(plan);
    subscribeMutation.mutate(plan, {
      onSuccess: () => {
        createClubMutation.mutate(pendingBody, {
          onSuccess: (club) => {
            haptic.notify('success');
            onCreated(club.id);
          },
          onError: (e) => {
            setSubmittingPlan(null);
            setPaywallError(e instanceof Error ? e.message : 'Не удалось создать клуб');
          },
        });
      },
      onError: (e) => {
        setSubmittingPlan(null);
        setPaywallError(e instanceof Error ? e.message : 'Не удалось оформить подписку');
      },
    });
  };

  if (paywall) {
    return (
      <PaywallModal
        info={paywall}
        submittingPlan={submittingPlan}
        error={paywallError}
        onSelectPlan={handleSelectPlan}
        onClose={() => {
          setPaywall(null);
          setPendingBody(null);
          setSubmittingPlan(null);
          setPaywallError(null);
        }}
      />
    );
  }

  return (
    <div className="rd-modal-form" style={{ padding: 16 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <span style={{ fontSize: 13, color: 'var(--text-dim)' }}>Шаг {step + 1} из {STEP_TITLES.length}: {STEP_TITLES[step]}</span>
        <button onClick={onClose} style={{ background: 'none', border: 'none', fontSize: 20, cursor: 'pointer', color: 'var(--text)' }}>&#x2715;</button>
      </div>

      {error && (
        <div className="rd-error" style={{ textAlign: 'left', marginBottom: 12 }}>
          {error}
        </div>
      )}

      {step === 0 && (
        <div className="rd-form">
          <label className="rd-field">
            <span className="rd-label">Название клуба <span className="rd-req">*</span></span>
            <input
              className={`rd-input${errors.name ? ' rd-invalid' : ''}`}
              placeholder="Например: Книжный клуб Москвы"
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
          </label>
          <label className="rd-field">
            <span className="rd-label">Город <span className="rd-req">*</span></span>
            <input
              className={`rd-input${errors.city ? ' rd-invalid' : ''}`}
              placeholder="Москва"
              {...register('city', {
                validate: (v) => v.trim().length > 0 || 'Укажите город',
              })}
            />
            <FieldError message={errors.city?.message} />
          </label>
          <label className="rd-field">
            <span className="rd-label">Район (необязательно)</span>
            <input className="rd-input" placeholder="Центральный" {...register('district')} />
          </label>
        </div>
      )}

      {step === 1 && (
        <div className="rd-form">
          <label className="rd-field">
            <span className="rd-label">Категория <span className="rd-req">*</span></span>
            <div className="rd-select-wrap">
              <select className="rd-select" {...register('category', { required: 'Выберите категорию' })}>
                {CATEGORIES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
              </select>
            </div>
            <FieldError message={errors.category?.message} />
          </label>
          <div className="rd-mode-list">
            <label className={`rd-mode-option${accessType === 'open' ? ' rd-active' : ''}`}>
              <input type="radio" value="open" {...register('accessType')} />
              <div>
                <div className="rd-mo-title">Открытый клуб</div>
                <div className="rd-mo-desc">Любой желающий может вступить</div>
              </div>
            </label>
            <label className={`rd-mode-option${accessType === 'closed' ? ' rd-active' : ''}`}>
              <input type="radio" value="closed" {...register('accessType')} />
              <div>
                <div className="rd-mo-title">Закрытый клуб</div>
                <div className="rd-mo-desc">Вступление по заявке (организатор одобряет)</div>
              </div>
            </label>
          </div>
        </div>
      )}

      {step === 2 && (
        <div className="rd-form">
          <label className="rd-field">
            <span className="rd-label">Лимит участников <span className="rd-req">*</span></span>
            <input
              className={`rd-input${errors.memberLimit ? ' rd-invalid' : ''}`}
              type="number"
              placeholder="30"
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
          </label>
          <label className="rd-field">
            <span className="rd-label">Цена подписки (Stars/мес)</span>
            <input
              className={`rd-input${errors.subscriptionPrice ? ' rd-invalid' : ''}`}
              type="number"
              placeholder="0 — бесплатно"
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
          </label>
          {Number(subscriptionPrice) > 0 && Number(memberLimit) > 0 && (
            <div className="rd-hint">
              При {memberLimit} участниках вы будете зарабатывать {monthlyIncome} Stars в месяц (80% от дохода)
            </div>
          )}
        </div>
      )}

      {step === 3 && (
        <div className="rd-form">
          <div className="rd-field">
            <span className="rd-label">Аватар (необязательно)</span>
            <AvatarUpload value={avatarUrl} onChange={setAvatarUrl} disabled={submitting} />
          </div>
          <label className="rd-field">
            <span className="rd-label">Описание клуба <span className="rd-req">*</span></span>
            <textarea
              className={`rd-textarea${errors.description ? ' rd-invalid' : ''}`}
              rows={4}
              placeholder="Расскажите о своём клубе (10-500 символов)"
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
          </label>
          <label className="rd-field">
            <span className="rd-label">Правила (необязательно)</span>
            <textarea className="rd-textarea" rows={3} placeholder="Правила сообщества" {...register('rules')} />
          </label>
        </div>
      )}

      {step === 4 && (
        <div className="rd-form">
          {accessType === 'closed' ? (
            <label className="rd-field">
              <span className="rd-label">Вопрос для вступления (необязательно)</span>
              <input className="rd-input" placeholder="Почему вы хотите вступить?" {...register('applicationQuestion')} />
            </label>
          ) : (
            <div className="rd-hint">Для открытого клуба вопрос при вступлении не нужен</div>
          )}
        </div>
      )}

      <div className="rd-form-actions">
        {step > 0 && (
          <button type="button" className="rd-btn-outline" onClick={() => { haptic.impact('light'); setStep((s) => s - 1); }}>
            Назад
          </button>
        )}
        {step < STEP_TITLES.length - 1 ? (
          <button type="button" className="rd-btn-primary" onClick={handleNext}>
            Далее
          </button>
        ) : (
          <button type="button" className="rd-btn-primary" onClick={handleSubmit(onValid, onInvalid)} disabled={submitting}>
            {submitting ? <Spinner size="s" /> : 'Создать клуб'}
          </button>
        )}
      </div>
    </div>
  );
};

import { FC, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import { useCreateClubMutation } from '../queries/clubs';
import { useSubscribeMutation } from '../queries/subscription';
import { paywallFromError, type PaywallInfo } from '../api/subscription';
import { PaywallModal } from './subscription/PaywallModal';
import { AvatarUpload } from './AvatarUpload';
import foxClubCreatedArt from '../assets/mascot/fox-club-created.png';
import type { CreateClubBody } from '../api/clubs';
import type { ClubDetailDto } from '../types/api';

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
  paymentLink: string;
  paymentMethodNote: string;
  description: string;
  rules: string;
  applicationQuestion: string;
}

const STEP_FIELDS: Array<Array<keyof ClubFormValues>> = [
  ['name', 'city'],
  ['category', 'accessType'],
  ['memberLimit', 'subscriptionPrice', 'paymentLink'],
  ['description'],
  [],
];

const FieldError: FC<{ message?: string }> = ({ message }) =>
  message ? (
    <div style={{ color: 'var(--danger)', fontSize: 13, paddingLeft: 2 }}>
      {message}
    </div>
  ) : null;

export const CreateClubModal: FC<{
  onClose: () => void;
  /** openInvite = true — «Пригласить участников» с экрана успеха (кадр E): клуб откроется с шитом. */
  onCreated: (id: string, openInvite: boolean) => void;
  /** «Привязать чат» с экрана успеха — ведёт в Управление → Чат созданного клуба. */
  onLinkChat: (id: string) => void;
}> = ({ onClose, onCreated, onLinkChat }) => {
  const haptic = useHaptic();
  const createClubMutation = useCreateClubMutation();
  const subscribeMutation = useSubscribeMutation();
  const [step, setStep] = useState(0);
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // club-invites (кадр E): после успешного создания форма сменяется экраном «Клуб создан 🎉».
  const [created, setCreated] = useState<ClubDetailDto | null>(null);
  // Состояние пейвола: бэкенд возвращает 402, когда платный клуб превышает потолок плана. Сохраняем
  // тело клуба в ожидании, показываем лестницу планов и после успешной подписки повторяем создание.
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
      paymentLink: '',
      paymentMethodNote: '',
      description: '',
      rules: '',
      applicationQuestion: '',
    },
  });

  const submitting = createClubMutation.isPending;

  const memberLimit = watch('memberLimit');
  const subscriptionPrice = watch('subscriptionPrice');
  const accessType = watch('accessType');

  // De-Stars: взносы идут напрямую участник→организатор (вне платформы), платформа не берёт комиссию — вся сумма.
  const monthlyIncome = Math.round(Number(memberLimit) * Number(subscriptionPrice));

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

  // RHF вызывает это, когда handleSubmit() обнаруживает ошибки валидации.
  // Повторяет fail-путь handleNext, чтобы на последнем шаге была та же тактильная отдача.
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
      // Реквизиты СБП важны только для платного клуба (бэкенд требует paymentLink при price > 0).
      paymentLink: Number(data.subscriptionPrice) > 0 ? data.paymentLink.trim() : undefined,
      paymentMethodNote: Number(data.subscriptionPrice) > 0 && data.paymentMethodNote.trim()
        ? data.paymentMethodNote.trim()
        : undefined,
    };
    createClubMutation.mutate(body, {
      onSuccess: (club) => {
        haptic.notify('success');
        setCreated(club);
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

  // Пейвол: подписаться на выбранный план, затем повторить отложенное создание.
  const handleSelectPlan = (plan: string) => {
    if (!pendingBody) return;
    setPaywallError(null);
    setSubmittingPlan(plan);
    subscribeMutation.mutate(plan, {
      onSuccess: () => {
        createClubMutation.mutate(pendingBody, {
          onSuccess: (club) => {
            haptic.notify('success');
            setCreated(club);
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

  // club-invites (кадр E мокапа): momentum-экран — клуб только что создан и пуст,
  // «позвать своих» — следующее действие. «Позже» просто открывает клуб без шита.
  if (created) {
    return (
      <div style={{ padding: '26px 24px 24px', textAlign: 'center' }}>
        <img src={foxClubCreatedArt} alt="" className="rd-foxcreated-art" draggable={false} />
        <h3 style={{ fontSize: 20, fontWeight: 700, margin: '0 0 8px' }}>
          Клуб «{created.name}» создан
        </h3>
        <p style={{ fontSize: 13, color: 'var(--text-dim)', lineHeight: 1.55, margin: '0 0 22px' }}>
          Теперь позовите своих — приглашение уйдёт в Telegram от вашего имени, с карточкой клуба.
        </p>
        <button
          type="button"
          className="rd-btn-primary"
          onClick={() => { haptic.impact('medium'); onCreated(created.id, true); }}
        >
          Пригласить участников
        </button>
        <button
          type="button"
          className="rd-btn-outline"
          style={{
            marginTop: 8,
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 8,
          }}
          onClick={() => { haptic.impact('light'); onLinkChat(created.id); }}
        >
          <svg
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M22 2 11 13" />
            <path d="M22 2l-7 20-4-9-9-4 22-7z" />
          </svg>
          Привязать чат в Telegram
        </button>
        <button
          type="button"
          className="rd-btn-outline"
          style={{ marginTop: 8 }}
          onClick={() => { haptic.impact('light'); onCreated(created.id, false); }}
        >
          Позже
        </button>
      </div>
    );
  }

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
                  // Согласовано с Bean Validation на бэкенде в CreateClubRequest.kt
                  // (минимум временно 1 — тест заполняемости, PO 2026-07-11).
                  if (!v || !Number.isFinite(n) || n < 1 || n > 80) return 'Лимит участников: 1–80';
                  if (!Number.isInteger(n)) return 'Лимит участников: 1–80';
                  return true;
                },
              })}
            />
            <FieldError message={errors.memberLimit?.message} />
          </label>
          <label className="rd-field">
            <span className="rd-label">Цена подписки (₽/мес)</span>
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
              При {memberLimit} участниках это до {monthlyIncome} ₽ в месяц. Участники платят вам напрямую — платформа комиссию не берёт.
            </div>
          )}

          {/* Платный клуб → реквизиты СБП обязательны: участники должны знать, как платить (de-Stars honor-system). */}
          {Number(subscriptionPrice) > 0 && (
            <>
              <label className="rd-field">
                <span className="rd-label">Реквизиты для взноса (СБП) <span className="rd-req">*</span></span>
                <input
                  className={`rd-input${errors.paymentLink ? ' rd-invalid' : ''}`}
                  placeholder="Ссылка СБП / банка или номер телефона"
                  {...register('paymentLink', {
                    validate: (v) =>
                      // Обязательно только для платного клуба; повторяет инвариант бэкенда (price > 0 ⇒ link).
                      Number(subscriptionPrice) > 0 && !v.trim()
                        ? 'Для платного клуба укажите реквизиты для взноса'
                        : true,
                  })}
                />
                <FieldError message={errors.paymentLink?.message} />
              </label>
              <label className="rd-field">
                <span className="rd-label">Подсказка к оплате (необязательно)</span>
                <input
                  className="rd-input"
                  placeholder="Например: Тинькофф, СБП по номеру…"
                  {...register('paymentMethodNote')}
                />
              </label>
              <div className="rd-hint">
                Участник увидит кнопку «Оплатить по СБП» на экране вступления. Оплата идёт напрямую вам, доступ откроете вы.
              </div>
            </>
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

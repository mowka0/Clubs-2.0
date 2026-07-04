import { FC, useEffect, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  Spinner,
  Placeholder,
  Button,
  Modal,
  Text,
} from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useSetClubContext } from '../store/useClubContextStore';
import { AvatarUpload } from '../components/AvatarUpload';
import { Toast } from '../components/Toast';
import { ManageHeader } from '../components/manage/ManageHeader';
import { ClubMembersTab } from '../components/club/ClubMembersTab';
import { ClubStatsTab } from '../components/manage/ClubStatsTab';
import { useClubQuery, useDeleteClubMutation, useUpdateClubMutation } from '../queries/clubs';
import { useMemberAttentionQuery } from '../queries/members';
import { useClubFinancesQuery } from '../queries/finances';
import type { UpdateClubBody } from '../api/clubs';
import type { ClubDetailDto } from '../types/api';

type TabKey = 'members' | 'stats' | 'finances' | 'settings';

// Вкладки страницы управления клубом (порядок массива = порядок отображения).
const TABS: ReadonlyArray<{ key: TabKey; label: string }> = [
  { key: 'members', label: 'Участники' },
  { key: 'stats', label: 'Статистика' },
  { key: 'finances', label: 'Финансы' },
  { key: 'settings', label: 'Настройки' },
];

// Допустимые значения `?tab=` в URL — для валидации deep-link'ов.
const VALID_TABS = new Set<string>(TABS.map((t) => t.key));

// Создание/просмотр активностей переехали из «Управления» (теперь на глобальном табе
// «Активности»). Заявки переехали в кросс-клубовый инбокс на MyClubsPage — см.
// docs/modules/applications-inbox.md. Legacy deep-link'и на удалённые вкладки
// откатываются на «Участники», чтобы старые шары/рефреши не отдавали 404.
const LEGACY_TAB_KEYS = new Set<string>(['activities', 'applications', 'events', 'skladchina']);

function resolveInitialTab(raw: string | null): TabKey {
  if (raw && VALID_TABS.has(raw)) return raw as TabKey;
  return 'members';
}

// ---- Вкладка «Финансы» ----

const FinancesTab: FC<{ clubId: string }> = ({ clubId }) => {
  const financesQuery = useClubFinancesQuery(clubId);
  const finances = financesQuery.data;

  if (financesQuery.isPending) {
    return (
      <div className="rd-spinner-row">
        <Spinner size="m" />
      </div>
    );
  }

  if (!finances) {
    return <Placeholder description="Не удалось загрузить финансы" />;
  }

  return (
    <>
      <div className="rd-section-sub-h">Финансовая сводка</div>
      <div className="rd-stats">
        <div className="rd-glass rd-stat">
          <div className="rd-stat-label">Активных участников</div>
          <div className="rd-stat-value rd-plain">{finances.activeMembers}</div>
        </div>
      </div>
      {/* De-Stars: взносы идут напрямую участник→организатор, мимо платформы — платформа не берёт
          комиссию и не отслеживает суммы. Полученные взносы подтверждаются во вкладке «Участники». */}
      <div className="rd-cta-hint" style={{ textAlign: 'left', marginTop: 8 }}>
        Оплата подписок идёт напрямую вам, мимо платформы — поэтому суммы здесь не считаются.
        Подтверждайте полученные взносы во вкладке «Участники» кнопкой «Взнос получен».
      </div>
    </>
  );
};

// ---- Вкладка «Настройки» ----

// Русские подписи категорий клуба для read-only блока «Нельзя изменить».
const CATEGORY_LABELS_RU: Record<string, string> = {
  sport: 'Спорт', creative: 'Творчество', food: 'Еда',
  board_games: 'Настолки', cinema: 'Кино', education: 'Образование',
  travel: 'Путешествия', other: 'Другое',
};
// Русские подписи типов доступа клуба (open / closed / private).
const ACCESS_LABELS_RU: Record<string, string> = {
  open: 'Открытый', closed: 'По заявке', private: 'Приватный',
};

interface SettingsTabProps {
  club: ClubDetailDto;
  onDeleted: (clubName: string) => void;
}

const SettingsTab: FC<SettingsTabProps> = ({ club, onDeleted }) => {
  const haptic = useHaptic();
  const updateMutation = useUpdateClubMutation();
  const deleteMutation = useDeleteClubMutation();

  const [name, setName] = useState(club.name);
  const [city, setCity] = useState(club.city);
  const [district, setDistrict] = useState(club.district ?? '');
  const [memberLimit, setMemberLimit] = useState(String(club.memberLimit));
  const [subscriptionPrice, setSubscriptionPrice] = useState(String(club.subscriptionPrice));
  const [description, setDescription] = useState(club.description);
  const [rules, setRules] = useState(club.rules ?? '');
  const [applicationQuestion, setApplicationQuestion] = useState(club.applicationQuestion ?? '');
  const [paymentLink, setPaymentLink] = useState(club.paymentLink ?? '');
  const [paymentMethodNote, setPaymentMethodNote] = useState(club.paymentMethodNote ?? '');
  const [avatarUrl, setAvatarUrl] = useState<string | null>(club.avatarUrl ?? null);

  const [error, setError] = useState<string | null>(null);
  // Хранит, какой Input рендерить в состоянии ошибки. Тот же паттерн, что RHF
  // `formState.errors.<field>` в CreateClubModal — красная обводка именно на
  // проблемном поле, а не только inline-сообщение.
  type SettingsField = 'name' | 'city' | 'memberLimit' | 'subscriptionPrice' | 'description' | 'paymentLink';
  const [errorField, setErrorField] = useState<SettingsField | null>(null);
  const [savedToast, setSavedToast] = useState<string | null>(null);
  const [showDeleteModal, setShowDeleteModal] = useState(false);

  const saving = updateMutation.isPending;
  const deleting = deleteMutation.isPending;

  const dirty =
    name !== club.name ||
    city !== club.city ||
    district !== (club.district ?? '') ||
    memberLimit !== String(club.memberLimit) ||
    subscriptionPrice !== String(club.subscriptionPrice) ||
    description !== club.description ||
    rules !== (club.rules ?? '') ||
    applicationQuestion !== (club.applicationQuestion ?? '') ||
    paymentLink !== (club.paymentLink ?? '') ||
    paymentMethodNote !== (club.paymentMethodNote ?? '') ||
    avatarUrl !== (club.avatarUrl ?? null);

  const handleSave = () => {
    setError(null);
    setErrorField(null);

    // Все провалы валидации выглядят одинаково: красная обводка поля +
    // inline-сообщение об ошибке + error-хаптик.
    const fail = (field: SettingsField, msg: string) => {
      setError(msg);
      setErrorField(field);
      haptic.notify('error');
    };

    // Базовая валидация (сервер делает полную Bean Validation)
    if (name.trim().length < 1 || name.trim().length > 60) {
      fail('name', 'Название: 1–60 символов');
      return;
    }
    if (!city.trim()) {
      fail('city', 'Укажите город');
      return;
    }
    const limit = Number(memberLimit);
    if (!Number.isInteger(limit) || limit < 10 || limit > 80) {
      fail('memberLimit', 'Лимит участников: 10–80');
      return;
    }
    const price = Number(subscriptionPrice);
    if (!Number.isInteger(price) || price < 0) {
      fail('subscriptionPrice', 'Цена: целое число >= 0');
      return;
    }
    // У платного клуба обязательно должны быть реквизиты СБП (зеркалит backend-инвариант). Блокирует
    // сохранение платного клуба с пустой ссылкой — включая переключение бесплатный→платный и
    // legacy-клуб, у которого реквизитов никогда не было.
    if (price > 0 && !paymentLink.trim()) {
      fail('paymentLink', 'Для платного клуба укажите реквизиты для взноса');
      return;
    }
    if (description.trim().length < 1 || description.trim().length > 500) {
      fail('description', 'Описание: 1–500 символов');
      return;
    }

    // Контракт бэкенда: для nullable-в-БД полей (district, avatarUrl, rules, applicationQuestion)
    // отсутствие ключа означает «оставить как есть», пустая строка — «очистить в NULL».
    // Поэтому когда пользователь очищает поле, мы отправляем '' (а не null/undefined).
    const payload: UpdateClubBody = {};
    if (name !== club.name) payload.name = name.trim();
    if (city !== club.city) payload.city = city.trim();
    if (district !== (club.district ?? '')) payload.district = district.trim();
    if (limit !== club.memberLimit) payload.memberLimit = limit;
    if (price !== club.subscriptionPrice) payload.subscriptionPrice = price;
    if (description !== club.description) payload.description = description.trim();
    if (rules !== (club.rules ?? '')) payload.rules = rules.trim();
    if (applicationQuestion !== (club.applicationQuestion ?? '')) {
      payload.applicationQuestion = applicationQuestion.trim();
    }
    if (paymentLink !== (club.paymentLink ?? '')) payload.paymentLink = paymentLink.trim();
    if (paymentMethodNote !== (club.paymentMethodNote ?? '')) payload.paymentMethodNote = paymentMethodNote.trim();
    if (avatarUrl !== (club.avatarUrl ?? null)) payload.avatarUrl = avatarUrl ?? '';

    haptic.impact('medium');
    updateMutation.mutate(
      { id: club.id, body: payload },
      {
        onSuccess: () => {
          haptic.notify('success');
          setSavedToast('Изменения сохранены');
        },
        onError: (e) => {
          setError(e.message);
          haptic.notify('error');
        },
      },
    );
  };

  const handleDelete = () => {
    haptic.impact('heavy');
    deleteMutation.mutate(club.id, {
      onSuccess: () => {
        haptic.notify('success');
        onDeleted(club.name);
      },
      onError: (e) => {
        setError(e.message);
        haptic.notify('error');
      },
    });
  };

  return (
    <>
      <div className="rd-section-sub-h">Аватар</div>
      <div className="rd-glass" style={{ padding: 16, marginBottom: 14, display: 'flex', justifyContent: 'center' }}>
        <AvatarUpload value={avatarUrl} onChange={setAvatarUrl} disabled={saving || deleting} />
      </div>

      <div className="rd-section-sub-h">Основное</div>
      <div className="rd-form" style={{ marginBottom: 14 }}>
        <label className="rd-field">
          <span className="rd-label">Название</span>
          <input
            className={`rd-input${errorField === 'name' ? ' rd-invalid' : ''}`}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <label className="rd-field">
          <span className="rd-label">Город</span>
          <input
            className={`rd-input${errorField === 'city' ? ' rd-invalid' : ''}`}
            value={city}
            onChange={(e) => setCity(e.target.value)}
          />
        </label>
        <label className="rd-field">
          <span className="rd-label">Район (опционально)</span>
          <input className="rd-input" value={district} onChange={(e) => setDistrict(e.target.value)} />
        </label>
        <label className="rd-field">
          <span className="rd-label">Лимит участников (10–80)</span>
          <input
            className={`rd-input${errorField === 'memberLimit' ? ' rd-invalid' : ''}`}
            type="number"
            value={memberLimit}
            onChange={(e) => setMemberLimit(e.target.value)}
          />
        </label>
        <label className="rd-field">
          <span className="rd-label">Цена подписки (₽/мес, 0 = бесплатно)</span>
          <input
            className={`rd-input${errorField === 'subscriptionPrice' ? ' rd-invalid' : ''}`}
            type="number"
            value={subscriptionPrice}
            onChange={(e) => setSubscriptionPrice(e.target.value)}
          />
        </label>
      </div>

      <div className="rd-section-sub-h">Описание и правила</div>
      <div className="rd-form" style={{ marginBottom: 14 }}>
        <label className="rd-field">
          <span className="rd-label">Описание</span>
          <textarea
            className={`rd-textarea${errorField === 'description' ? ' rd-invalid' : ''}`}
            rows={4}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </label>
        <label className="rd-field">
          <span className="rd-label">Правила клуба (опционально)</span>
          <textarea
            className="rd-textarea"
            rows={3}
            value={rules}
            onChange={(e) => setRules(e.target.value)}
          />
        </label>
        {club.accessType === 'closed' && (
          <label className="rd-field">
            <span className="rd-label">Вопрос для заявки (опционально)</span>
            <input
              className="rd-input"
              value={applicationQuestion}
              onChange={(e) => setApplicationQuestion(e.target.value)}
            />
          </label>
        )}
      </div>

      {Number(subscriptionPrice) > 0 && (
        <>
          <div className="rd-section-sub-h">Реквизиты для взноса (СБП)</div>
          <div className="rd-form" style={{ marginBottom: 14 }}>
            <label className="rd-field">
              <span className="rd-label">Ссылка / номер для оплаты <span className="rd-req">*</span></span>
              <input
                className={`rd-input${errorField === 'paymentLink' ? ' rd-invalid' : ''}`}
                value={paymentLink}
                onChange={(e) => setPaymentLink(e.target.value)}
                placeholder="Ссылка СБП / банка или номер телефона"
              />
              <span className="rd-hint">Видят только участники, кому нужно оплатить взнос. Деньги идут напрямую вам — доступ открываете вы кнопкой «Взнос получен».</span>
            </label>
            <label className="rd-field">
              <span className="rd-label">Подсказка к оплате (опционально)</span>
              <input
                className="rd-input"
                value={paymentMethodNote}
                onChange={(e) => setPaymentMethodNote(e.target.value)}
                placeholder="Например: Тинькофф, СБП по номеру…"
              />
            </label>
          </div>
        </>
      )}

      <div className="rd-section-sub-h">Нельзя изменить</div>
      <div className="rd-glass rd-rep-panel">
        <div className="rd-kv">
          <span>Категория</span>
          <span className="rd-v">{CATEGORY_LABELS_RU[club.category] ?? club.category}</span>
        </div>
        <div className="rd-kv">
          <span>Тип доступа</span>
          <span className="rd-v">{ACCESS_LABELS_RU[club.accessType] ?? club.accessType}</span>
        </div>
      </div>
      <div className="rd-cta-hint" style={{ textAlign: 'left', marginTop: 8, marginBottom: 4 }}>
        Смена категории или типа доступа не поддерживается.
      </div>

      {error && <div className="rd-error">{error}</div>}

      <div className="rd-cta-wrap">
        <button type="button" className="rd-btn-primary" onClick={handleSave} disabled={!dirty || saving || deleting}>
          {saving ? <Spinner size="s" /> : 'Сохранить'}
        </button>
      </div>

      {savedToast && <Toast message={savedToast} onClose={() => setSavedToast(null)} />}

      <div className="rd-section-sub-h">Опасная зона</div>
      <button
        type="button"
        className="rd-btn-outline"
        onClick={() => { haptic.impact('light'); setShowDeleteModal(true); }}
        disabled={saving || deleting}
        style={{ color: 'var(--danger)' }}
      >
        Удалить клуб
      </button>

      <Modal open={showDeleteModal} onOpenChange={(v) => !deleting && setShowDeleteModal(v)}>
        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <Text weight="2">Удалить клуб «{club.name}»?</Text>
          <Text>
            Клуб скроется из каталога и «Моих клубов». {club.memberCount} активных участников потеряют доступ.
            Это действие необратимо.
          </Text>
          <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
            <Button
              size="m"
              stretched
              mode="outline"
              onClick={() => setShowDeleteModal(false)}
              disabled={deleting}
            >
              Отмена
            </Button>
            <Button size="m" stretched onClick={handleDelete} disabled={deleting}>
              {deleting ? <Spinner size="s" /> : 'Удалить'}
            </Button>
          </div>
        </div>
      </Modal>
    </>
  );
};

// ---- Основная страница ----

export const OrganizerClubManage: FC = () => {
  useBackButton(true);
  const haptic = useHaptic();
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  useSetClubContext(id);

  const initialTab = resolveInitialTab(searchParams.get('tab'));
  const [activeTab, setActiveTab] = useState<TabKey>(initialTab);

  // Читаем flash-тост из navigation state (например, его выставляет CreateEventPage при успехе).
  const flashToast =
    typeof location.state === 'object' &&
    location.state !== null &&
    'toast' in location.state &&
    typeof (location.state as { toast?: unknown }).toast === 'string'
      ? (location.state as { toast: string }).toast
      : null;
  const [toast, setToast] = useState<string | null>(flashToast);

  // Очищаем navigation state после использования, чтобы возврат назад не показал тост повторно.
  useEffect(() => {
    if (flashToast) {
      window.history.replaceState({}, document.title);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Сбрасываем legacy deep-link'и `?tab=activities|events|skladchina` на дефолтную
  // вкладку, чтобы refresh / шаринг не сохраняли мёртвое значение в URL.
  useEffect(() => {
    const raw = searchParams.get('tab');
    if (raw && LEGACY_TAB_KEYS.has(raw)) {
      const next = new URLSearchParams(searchParams);
      next.delete('tab');
      setSearchParams(next, { replace: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const clubId = id ?? '';
  const clubQuery = useClubQuery(clubId || undefined);
  const club = clubQuery.data;
  // Красная точка на «Участники»: у участников скоро истекает доступ ИЛИ есть frozen-участники,
  // ожидающие подтверждения взноса.
  const memberAttentionQuery = useMemberAttentionQuery(clubId || undefined);
  const showMembersDot =
    (memberAttentionQuery.data?.expiringSoon ?? 0) > 0
    || (memberAttentionQuery.data?.awaitingDues ?? 0) > 0;

  const handleTabChange = (key: TabKey) => {
    if (key === activeTab) return;
    haptic.select();
    setActiveTab(key);
  };

  if (!clubId) {
    return (
      <div className="rd-page">
        <Placeholder description="Клуб не найден" />
      </div>
    );
  }

  if (!club) {
    return (
      <div className="rd-page">
        <div className="rd-spinner-row" style={{ paddingTop: 60 }}>
          <Spinner size="l" />
        </div>
      </div>
    );
  }

  const renderTab = () => {
    switch (activeTab) {
      case 'members':
        return <ClubMembersTab clubId={clubId} isOrganizer managementView />;
      case 'stats':
        return <ClubStatsTab clubId={clubId} />;
      case 'finances':
        return <FinancesTab clubId={clubId} />;
      case 'settings':
        return (
          <SettingsTab
            club={club}
            onDeleted={(name) =>
              navigate('/my-clubs', {
                replace: true,
                state: { toast: `Клуб «${name}» удалён` },
              })
            }
          />
        );
    }
  };

  return (
    <div className="rd-page">
      <ManageHeader
        club={club}
        onBack={() => { haptic.impact('light'); navigate(`/clubs/${clubId}`); }}
      />

      <div className="rd-tabs" role="tablist">
        {TABS.map((tab) => (
          <button
            key={tab.key}
            type="button"
            role="tab"
            aria-selected={tab.key === activeTab}
            className={`rd-tab-link${tab.key === activeTab ? ' rd-active' : ''}`}
            onClick={() => handleTabChange(tab.key)}
          >
            {tab.label}
            {tab.key === 'members' && showMembersDot && (
              <span className="rd-tab-dot" aria-hidden="true" />
            )}
          </button>
        ))}
      </div>

      {renderTab()}

      {toast && <Toast message={toast} onClose={() => setToast(null)} />}
    </div>
  );
};

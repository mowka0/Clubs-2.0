import { FC, useEffect, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  Section,
  Cell,
  Spinner,
  Placeholder,
  Avatar,
  Text,
  Button,
  Badge,
  Modal,
  Input,
  Textarea,
} from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { AvatarUpload } from '../components/AvatarUpload';
import { Toast } from '../components/Toast';
import { BrandBackdrop } from '../components/BrandBackdrop';
import { ManageHeader } from '../components/manage/ManageHeader';
import { ManageTabs } from '../components/manage/ManageTabs';
import { useClubQuery, useDeleteClubMutation, useUpdateClubMutation } from '../queries/clubs';
import { useClubMembersQuery } from '../queries/members';
import { MemberProfileModal } from '../components/club/MemberProfileModal';
import { useClubFinancesQuery } from '../queries/finances';
import type { UpdateClubBody } from '../api/clubs';
import type {
  MemberListItemDto,
  ClubDetailDto,
} from '../types/api';

type TabKey = 'members' | 'finances' | 'settings';

const TABS: ReadonlyArray<{ key: TabKey; label: string }> = [
  { key: 'members', label: 'Участники' },
  { key: 'finances', label: 'Финансы' },
  { key: 'settings', label: 'Настройки' },
];

const VALID_TABS = new Set<string>(TABS.map((t) => t.key));

// Activity creation/browsing moved out of Manage (now on the global "Активности"
// tab). Applications moved to the cross-club inbox on MyClubsPage — see
// docs/modules/applications-inbox.md. Legacy deep-links to removed tabs fall
// back to "Участники" so old shares/refreshes don't 404.
const LEGACY_TAB_KEYS = new Set<string>(['activities', 'applications', 'events', 'skladchina']);

function resolveInitialTab(raw: string | null): TabKey {
  if (raw && VALID_TABS.has(raw)) return raw as TabKey;
  return 'members';
}

// MemberProfileModal extracted to components/club/MemberProfileModal.tsx
// (shared with ClubMembersTab inside unified ClubPage).

// ---- Members Tab ----

const MembersTab: FC<{ clubId: string }> = ({ clubId }) => {
  const haptic = useHaptic();
  const membersQuery = useClubMembersQuery(clubId);
  const [selectedMember, setSelectedMember] = useState<MemberListItemDto | null>(null);

  const members = membersQuery.data ?? [];

  if (membersQuery.isPending) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: 32 }}>
        <Spinner size="m" />
      </div>
    );
  }

  if (members.length === 0) {
    return <Placeholder description="В клубе пока нет участников" />;
  }

  return (
    <>
      <Section>
        {members.map((m) => (
          <Cell
            key={m.userId}
            onClick={() => { haptic.impact('light'); setSelectedMember(m); }}
            before={
              <Avatar
                size={40}
                src={m.avatarUrl ?? undefined}
                acronym={`${m.firstName.charAt(0)}${m.lastName?.charAt(0) ?? ''}`}
              />
            }
            subtitle={`Надёжность: ${m.reliabilityIndex} · Обещания: ${m.promiseFulfillmentPct}%`}
            after={
              m.role === 'organizer' ? (
                <Badge type="number" mode="primary">Орг</Badge>
              ) : undefined
            }
          >
            {m.firstName} {m.lastName ?? ''}
          </Cell>
        ))}
      </Section>

      {selectedMember && (
        <MemberProfileModal
          member={selectedMember}
          clubId={clubId}
          onClose={() => setSelectedMember(null)}
        />
      )}
    </>
  );
};

// ---- Finances Tab ----

const FinancesTab: FC<{ clubId: string }> = ({ clubId }) => {
  const financesQuery = useClubFinancesQuery(clubId);
  const finances = financesQuery.data;

  if (financesQuery.isPending) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: 32 }}>
        <Spinner size="m" />
      </div>
    );
  }

  if (!finances) {
    return <Placeholder description="Не удалось загрузить финансы" />;
  }

  return (
    <Section header="Финансовая сводка">
      <Cell subtitle="Активных участников">
        {finances.activeMembers}
      </Cell>
      <Cell subtitle="Выручка за месяц">
        {finances.monthlyRevenue} Stars
      </Cell>
      <Cell subtitle={`Доля организатора (${finances.organizerSharePct}%)`}>
        {finances.organizerShare} Stars
      </Cell>
      <Cell subtitle={`Комиссия платформы (${finances.platformFeePct}%)`}>
        {finances.platformFee} Stars
      </Cell>
    </Section>
  );
};

// ---- Settings Tab ----

const CATEGORY_LABELS_RU: Record<string, string> = {
  sport: 'Спорт', creative: 'Творчество', food: 'Еда',
  board_games: 'Настолки', cinema: 'Кино', education: 'Образование',
  travel: 'Путешествия', other: 'Другое',
};
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
  const [avatarUrl, setAvatarUrl] = useState<string | null>(club.avatarUrl ?? null);

  const [error, setError] = useState<string | null>(null);
  // Tracks which Input should render in the error state. Same pattern as RHF
  // `formState.errors.<field>` in CreateClubModal — gives a red outline on the
  // offending field, not just an inline message.
  type SettingsField = 'name' | 'city' | 'memberLimit' | 'subscriptionPrice' | 'description';
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
    avatarUrl !== (club.avatarUrl ?? null);

  const handleSave = () => {
    setError(null);
    setErrorField(null);

    // Validation failures share the same UX: red outline on the field +
    // inline error message + error haptic.
    const fail = (field: SettingsField, msg: string) => {
      setError(msg);
      setErrorField(field);
      haptic.notify('error');
    };

    // Basic validation (server does full Bean Validation)
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
    if (description.trim().length < 1 || description.trim().length > 500) {
      fail('description', 'Описание: 1–500 символов');
      return;
    }

    // Backend contract: for nullable-in-DB fields (district, avatarUrl, rules, applicationQuestion)
    // an omitted key means "leave as is", a blank string means "clear to NULL".
    // That's why we send '' (not null/undefined) when the user clears the field.
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
      <Section header="Аватар">
        <div style={{ padding: 16 }}>
          <AvatarUpload value={avatarUrl} onChange={setAvatarUrl} disabled={saving || deleting} />
        </div>
      </Section>

      <Section header="Основное">
        <Input header="Название" value={name} onChange={(e) => setName(e.target.value)} status={errorField === 'name' ? 'error' : undefined} />
        <Input header="Город" value={city} onChange={(e) => setCity(e.target.value)} status={errorField === 'city' ? 'error' : undefined} />
        <Input header="Район (опционально)" value={district} onChange={(e) => setDistrict(e.target.value)} />
        <Input
          header="Лимит участников (10–80)"
          type="number"
          value={memberLimit}
          onChange={(e) => setMemberLimit(e.target.value)}
          status={errorField === 'memberLimit' ? 'error' : undefined}
        />
        <Input
          header="Цена подписки (Stars/мес, 0 = бесплатно)"
          type="number"
          value={subscriptionPrice}
          onChange={(e) => setSubscriptionPrice(e.target.value)}
          status={errorField === 'subscriptionPrice' ? 'error' : undefined}
        />
      </Section>

      <Section header="Описание и правила">
        <Textarea
          header="Описание"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          status={errorField === 'description' ? 'error' : undefined}
        />
        <Textarea
          header="Правила клуба (опционально)"
          value={rules}
          onChange={(e) => setRules(e.target.value)}
        />
        {club.accessType === 'closed' && (
          <Input
            header="Вопрос для заявки (опционально)"
            value={applicationQuestion}
            onChange={(e) => setApplicationQuestion(e.target.value)}
          />
        )}
      </Section>

      <Section header="Нельзя изменить">
        <Cell subtitle="Категория">{CATEGORY_LABELS_RU[club.category] ?? club.category}</Cell>
        <Cell subtitle="Тип доступа">{ACCESS_LABELS_RU[club.accessType] ?? club.accessType}</Cell>
        <div style={{ padding: '0 16px 12px', fontSize: 12, color: 'var(--tgui--hint_color)' }}>
          Смена категории или типа доступа не поддерживается.
        </div>
      </Section>

      {error && (
        <div style={{ padding: '0 16px 8px', color: 'var(--tgui--destructive_text_color, #d00)', fontSize: 13 }}>
          {error}
        </div>
      )}

      <div style={{ padding: '0 16px 16px', display: 'flex', flexDirection: 'column', gap: 8 }}>
        <Button size="l" stretched onClick={handleSave} disabled={!dirty || saving || deleting}>
          {saving ? <Spinner size="s" /> : 'Сохранить'}
        </Button>
      </div>

      {savedToast && <Toast message={savedToast} onClose={() => setSavedToast(null)} />}

      <Section header="Опасная зона">
        <div style={{ padding: 16 }}>
          <Button
            size="l"
            stretched
            mode="outline"
            onClick={() => { haptic.impact('light'); setShowDeleteModal(true); }}
            disabled={saving || deleting}
          >
            &#x1F5D1; Удалить клуб
          </Button>
        </div>
      </Section>

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

// ---- Main Page ----

export const OrganizerClubManage: FC = () => {
  useBackButton(true);
  const haptic = useHaptic();
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();

  const initialTab = resolveInitialTab(searchParams.get('tab'));
  const [activeTab, setActiveTab] = useState<TabKey>(initialTab);

  // Read flash toast from navigation state (e.g. set by CreateEventPage on success).
  const flashToast =
    typeof location.state === 'object' &&
    location.state !== null &&
    'toast' in location.state &&
    typeof (location.state as { toast?: unknown }).toast === 'string'
      ? (location.state as { toast: string }).toast
      : null;
  const [toast, setToast] = useState<string | null>(flashToast);

  // Clear navigation state once consumed so back-nav doesn't re-trigger the toast.
  useEffect(() => {
    if (flashToast) {
      window.history.replaceState({}, document.title);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Drop legacy `?tab=activities|events|skladchina` deep-links to the default
  // tab so refresh / share doesn't keep a dead value in the URL.
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
  const clubLoading = clubQuery.isPending;

  if (!clubId) {
    return (
      <div className="brand-page">
        <BrandBackdrop />
        <Placeholder description="Клуб не найден" />
      </div>
    );
  }

  const renderTab = () => {
    switch (activeTab) {
      case 'members':
        return <MembersTab clubId={clubId} />;
      case 'finances':
        return <FinancesTab clubId={clubId} />;
      case 'settings':
        if (!club) return <div style={{ padding: 16 }}><Spinner size="m" /></div>;
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
    <div className="brand-page">
      <BrandBackdrop />

      {/* Brand hero — clickable, navigates back to public ClubPage */}
      {!clubLoading && club && (
        <ManageHeader
          club={club}
          onOpenClub={() => { haptic.impact('light'); navigate(`/clubs/${clubId}`); }}
        />
      )}

      {/* Pill-tabs (scrollable, full labels) */}
      <ManageTabs tabs={TABS} active={activeTab} onChange={setActiveTab} />

      {/* Tab content */}
      <div className="manage-tab-content">{renderTab()}</div>

      {toast && <Toast message={toast} onClose={() => setToast(null)} />}
    </div>
  );
};

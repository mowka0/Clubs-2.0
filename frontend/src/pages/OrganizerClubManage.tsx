import { FC, useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  List,
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
  Checkbox,
  TabsList,
} from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { AvatarUpload } from '../components/AvatarUpload';
import { getClubMembers, getMemberProfile, getClubApplications, approveApplication, rejectApplication } from '../api/membership';
import { getClubEvents, createEvent, markAttendance, getFinances, getEvent } from '../api/events';
import { getClub, updateClub, deleteClub } from '../api/clubs';
import type { UpdateClubBody } from '../api/clubs';
import type { CreateEventBody } from '../api/events';
import type {
  MemberListItemDto,
  ClubApplicationDto,
  EventListItemDto,
  EventDetailDto,
  FinancesDto,
  ClubDetailDto,
} from '../types/api';
import { formatDatetime } from '../utils/formatters';

type TabKey = 'members' | 'applications' | 'events' | 'finances' | 'settings';

const TAB_LABELS: Record<TabKey, string> = {
  members: 'Участники',
  applications: 'Заявки',
  events: 'События',
  finances: 'Финансы',
  settings: 'Настройки',
};

function hoursRemaining(createdAt: string | null): number | null {
  if (!createdAt) return null;
  const created = new Date(createdAt).getTime();
  const deadline = created + 48 * 60 * 60 * 1000;
  const now = Date.now();
  const diff = deadline - now;
  if (diff <= 0) return 0;
  return Math.floor(diff / (60 * 60 * 1000));
}

// ---- Member Profile Modal ----

const MemberProfileModal: FC<{
  member: MemberListItemDto;
  clubId: string;
  onClose: () => void;
}> = ({ member, clubId, onClose }) => {
  const [profile, setProfile] = useState<import('../types/api').MemberProfileDto | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getMemberProfile(clubId, member.userId)
      .then(setProfile)
      .finally(() => setLoading(false));
  }, [clubId, member.userId]);

  const joinedAt = member.joinedAt
    ? new Date(member.joinedAt).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' })
    : '\u2014';

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <div style={{ padding: 16 }}>
        {/* Header */}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
          <Text weight="2" style={{ fontSize: 18 }}>Профиль участника</Text>
          <button onClick={onClose} style={{ background: 'none', border: 'none', fontSize: 20, cursor: 'pointer', color: 'var(--tgui--text_color)' }}>&#x2715;</button>
        </div>

        {/* Avatar + name */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
          <Avatar
            size={48}
            src={member.avatarUrl ?? undefined}
            acronym={`${member.firstName.charAt(0)}${member.lastName?.charAt(0) ?? ''}`}
          />
          <div>
            <Text weight="1" style={{ fontSize: 17, display: 'block' }}>
              {member.firstName} {member.lastName ?? ''}
            </Text>
            {profile?.username && (
              <Text style={{ fontSize: 13, color: 'var(--tgui--hint_color)', display: 'block' }}>
                @{profile.username}
              </Text>
            )}
          </div>
        </div>

        {/* Status in club */}
        <Section header="Статус в клубе">
          <Cell subtitle="Роль">
            {member.role === 'organizer' ? 'Организатор' : 'Участник'}
          </Cell>
          <Cell subtitle="В клубе с">{joinedAt}</Cell>
        </Section>

        {/* Reputation */}
        {loading ? (
          <div style={{ display: 'flex', justifyContent: 'center', padding: 16 }}>
            <Spinner size="s" />
          </div>
        ) : profile ? (
          <Section header="Репутация">
            <Cell subtitle="Индекс надёжности">{profile.reliabilityIndex}</Cell>
            <Cell subtitle="Выполнение обещаний">{profile.promiseFulfillmentPct}%</Cell>
            <Cell subtitle="Подтверждений участия">{profile.totalConfirmations}</Cell>
            <Cell subtitle="Посещений событий">{profile.totalAttendances}</Cell>
          </Section>
        ) : null}
      </div>
    </Modal>
  );
};

// ---- Members Tab ----

const MembersTab: FC<{ clubId: string }> = ({ clubId }) => {
  const [members, setMembers] = useState<MemberListItemDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedMember, setSelectedMember] = useState<MemberListItemDto | null>(null);

  useEffect(() => {
    setLoading(true);
    getClubMembers(clubId)
      .then(setMembers)
      .finally(() => setLoading(false));
  }, [clubId]);

  if (loading) {
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
            onClick={() => setSelectedMember(m)}
            before={
              <Avatar
                size={40}
                src={m.avatarUrl ?? undefined}
                acronym={`${m.firstName.charAt(0)}${m.lastName?.charAt(0) ?? ''}`}
              />
            }
            subtitle={`Надёжность: ${m.reliabilityIndex} \u00B7 Обещания: ${m.promiseFulfillmentPct}%`}
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

// ---- Applications Tab ----

const ApplicationsTab: FC<{ clubId: string }> = ({ clubId }) => {
  const [applications, setApplications] = useState<ClubApplicationDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [processing, setProcessing] = useState<string | null>(null);

  const fetchApps = useCallback(async () => {
    setLoading(true);
    try {
      const result = await getClubApplications(clubId, 'pending');
      setApplications(result);
    } finally {
      setLoading(false);
    }
  }, [clubId]);

  useEffect(() => {
    fetchApps();
  }, [fetchApps]);

  const handleApprove = async (appId: string) => {
    setProcessing(appId);
    try {
      await approveApplication(appId);
      await fetchApps();
    } finally {
      setProcessing(null);
    }
  };

  const handleReject = async (appId: string) => {
    setProcessing(appId);
    try {
      await rejectApplication(appId);
      await fetchApps();
    } finally {
      setProcessing(null);
    }
  };

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: 32 }}>
        <Spinner size="m" />
      </div>
    );
  }

  if (applications.length === 0) {
    return <Placeholder description="Нет активных заявок" />;
  }

  return (
    <Section>
      {applications.map((app) => {
        const hrs = hoursRemaining(app.createdAt);
        const isProcessing = processing === app.id;

        return (
          <div key={app.id} style={{ padding: '12px 16px', borderBottom: '1px solid var(--tgui--divider)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <Text weight="2" style={{ display: 'block' }}>
                Пользователь {app.userId.slice(0, 8)}...
              </Text>
              {hrs !== null && (
                <span style={{ fontSize: 12, color: hrs <= 6 ? 'var(--tgui--destructive_text_color)' : 'var(--tgui--hint_color)' }}>
                  {hrs > 0 ? `${hrs}\u0447 \u0434\u043E \u0430\u0432\u0442\u043E\u043E\u0442\u043A\u043B\u043E\u043D\u0435\u043D\u0438\u044F` : '\u0412\u0440\u0435\u043C\u044F \u0438\u0441\u0442\u0435\u043A\u043B\u043E'}
                </span>
              )}
            </div>

            {app.answerText && (
              <div style={{ marginTop: 6, fontSize: 14, color: 'var(--tgui--hint_color)', lineHeight: 1.4 }}>
                {app.answerText}
              </div>
            )}

            <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
              <Button
                size="s"
                onClick={() => handleApprove(app.id)}
                disabled={isProcessing}
                stretched
              >
                {isProcessing ? <Spinner size="s" /> : 'Принять'}
              </Button>
              <Button
                size="s"
                mode="outline"
                onClick={() => handleReject(app.id)}
                disabled={isProcessing}
                stretched
              >
                Отклонить
              </Button>
            </div>
          </div>
        );
      })}
    </Section>
  );
};

// ---- Event Detail Modal ----

const EVENT_STATUS_LABELS: Record<string, string> = {
  upcoming: 'Запланировано',
  stage_1: 'Голосование',
  stage_2: 'Подтверждение',
  completed: 'Завершено',
  cancelled: 'Отменено',
};

const EventDetailModal: FC<{ eventId: string; onClose: () => void }> = ({ eventId, onClose }) => {
  const [detail, setDetail] = useState<EventDetailDto | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getEvent(eventId)
      .then(setDetail)
      .finally(() => setLoading(false));
  }, [eventId]);

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <div style={{ padding: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
          <Text weight="2" style={{ fontSize: 18 }}>Детали события</Text>
          <button onClick={onClose} style={{ background: 'none', border: 'none', fontSize: 20, cursor: 'pointer', color: 'var(--tgui--text_color)' }}>&#x2715;</button>
        </div>

        {loading ? (
          <div style={{ display: 'flex', justifyContent: 'center', padding: 24 }}>
            <Spinner size="m" />
          </div>
        ) : detail ? (
          <>
            <Section>
              <Cell subtitle="Название">{detail.title}</Cell>
              <Cell subtitle="Дата и время">{formatDatetime(detail.eventDatetime)}</Cell>
              <Cell subtitle="Место">{detail.locationText}</Cell>
              <Cell subtitle="Статус">{EVENT_STATUS_LABELS[detail.status] ?? detail.status}</Cell>
            </Section>

            <Section header="Участники">
              <Cell subtitle="Пойдут / лимит">{detail.goingCount} / {detail.participantLimit}</Cell>
              <Cell subtitle="Может быть">{detail.maybeCount}</Cell>
              <Cell subtitle="Не пойдут">{detail.notGoingCount}</Cell>
              <Cell subtitle="Подтверждено">{detail.confirmedCount}</Cell>
            </Section>

            {detail.description && (
              <Section header="Описание">
                <div style={{ padding: '12px 16px', lineHeight: 1.5, color: 'var(--tgui--text_color)' }}>
                  {detail.description}
                </div>
              </Section>
            )}

            {detail.status === 'completed' && (
              <Section header="Посещаемость">
                <Cell subtitle="Явка отмечена">{detail.attendanceMarked ? 'Да' : 'Нет'}</Cell>
                <Cell subtitle="Явка финализирована">{detail.attendanceFinalized ? 'Да' : 'Нет'}</Cell>
              </Section>
            )}
          </>
        ) : (
          <Placeholder description="Не удалось загрузить событие" />
        )}
      </div>
    </Modal>
  );
};

// ---- Events Tab ----

interface EventFormState {
  title: string;
  locationText: string;
  eventDatetime: string;
  participantLimit: string;
}

const INITIAL_EVENT_FORM: EventFormState = {
  title: '',
  locationText: '',
  eventDatetime: '',
  participantLimit: '20',
};

const UPCOMING_STATUSES = ['upcoming', 'stage_1', 'stage_2'];

const EventsTab: FC<{ clubId: string }> = ({ clubId }) => {
  const [events, setEvents] = useState<EventListItemDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState<EventFormState>(INITIAL_EVENT_FORM);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [attendanceEventId, setAttendanceEventId] = useState<string | null>(null);
  const [attendanceList, setAttendanceList] = useState<{ userId: string; attended: boolean }[]>([]);
  const [markingAttendance, setMarkingAttendance] = useState(false);
  const [selectedEventId, setSelectedEventId] = useState<string | null>(null);

  const fetchEvents = useCallback(() => {
    setLoading(true);
    getClubEvents(clubId, { size: '50' })
      .then((res) => setEvents(res.content))
      .finally(() => setLoading(false));
  }, [clubId]);

  useEffect(() => {
    fetchEvents();
  }, [fetchEvents]);

  const update = (field: keyof EventFormState, value: string) =>
    setForm((f) => ({ ...f, [field]: value }));

  const handleCreateEvent = async () => {
    setFormError(null);
    if (!form.title.trim()) { setFormError('Укажите название'); return; }
    if (!form.locationText.trim()) { setFormError('Укажите место'); return; }
    if (!form.eventDatetime) { setFormError('Укажите дату и время'); return; }
    const limit = Number(form.participantLimit);
    if (!limit || limit < 1) { setFormError('Укажите лимит участников'); return; }

    setSubmitting(true);
    try {
      const body: CreateEventBody = {
        title: form.title.trim(),
        locationText: form.locationText.trim(),
        eventDatetime: new Date(form.eventDatetime).toISOString(),
        participantLimit: limit,
      };
      await createEvent(clubId, body);
      setForm(INITIAL_EVENT_FORM);
      setShowForm(false);
      fetchEvents();
    } catch (e) {
      setFormError((e as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  const openAttendanceModal = (event: EventListItemDto) => {
    setAttendanceEventId(event.id);
    setAttendanceList([]);
  };

  const handleMarkAttendance = async () => {
    if (!attendanceEventId) return;
    setMarkingAttendance(true);
    try {
      await markAttendance(attendanceEventId, attendanceList.filter((a) => a.attended));
      setAttendanceEventId(null);
      setAttendanceList([]);
      fetchEvents();
    } catch {
      // Silently handle
    } finally {
      setMarkingAttendance(false);
    }
  };

  const upcomingEvents = events.filter((e) => UPCOMING_STATUSES.includes(e.status));
  const completedEvents = events.filter((e) => e.status === 'completed');

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: 32 }}>
        <Spinner size="m" />
      </div>
    );
  }

  return (
    <>
      <Section>
        <Button size="l" stretched onClick={() => setShowForm((s) => !s)}>
          {showForm ? 'Отменить' : '+ Создать событие'}
        </Button>
      </Section>

      {showForm && (
        <Section header="Новое событие">
          <Input
            header="Название *"
            placeholder="Например: Йога в парке"
            value={form.title}
            onChange={(e) => update('title', e.target.value)}
          />
          <Input
            header="Место *"
            placeholder="Адрес или описание"
            value={form.locationText}
            onChange={(e) => update('locationText', e.target.value)}
          />
          <div style={{ padding: '0 16px' }}>
            <div style={{ fontSize: 14, color: 'var(--tgui--hint_color)', marginBottom: 4, marginTop: 12 }}>
              Дата и время *
            </div>
            <input
              type="datetime-local"
              value={form.eventDatetime}
              onChange={(e) => update('eventDatetime', e.target.value)}
              style={{
                width: '100%',
                padding: '10px 12px',
                fontSize: 16,
                border: '1px solid var(--tgui--divider)',
                borderRadius: 10,
                background: 'var(--tgui--secondary_bg_color)',
                color: 'var(--tgui--text_color)',
                boxSizing: 'border-box',
              }}
            />
          </div>
          <Input
            header="Лимит участников *"
            type="number"
            placeholder="20"
            value={form.participantLimit}
            onChange={(e) => update('participantLimit', e.target.value)}
          />

          {formError && (
            <div style={{ padding: '8px 16px', color: 'var(--tgui--destructive_text_color)', fontSize: 14 }}>
              {formError}
            </div>
          )}

          <div style={{ padding: '8px 16px 16px' }}>
            <Button size="m" onClick={handleCreateEvent} disabled={submitting} stretched>
              {submitting ? <Spinner size="s" /> : 'Создать'}
            </Button>
          </div>
        </Section>
      )}

      {upcomingEvents.length > 0 && (
        <Section header="Предстоящие">
          {upcomingEvents.map((evt) => (
            <Cell
              key={evt.id}
              onClick={() => setSelectedEventId(evt.id)}
              subtitle={`${formatDatetime(evt.eventDatetime)} | ${evt.locationText}`}
              after={
                <Badge type="number" mode="secondary">
                  {evt.goingCount}/{evt.participantLimit}
                </Badge>
              }
            >
              {evt.title}
            </Cell>
          ))}
        </Section>
      )}

      {completedEvents.length > 0 && (
        <Section header="Завершённые">
          {completedEvents.map((evt) => (
            <Cell
              key={evt.id}
              onClick={() => setSelectedEventId(evt.id)}
              subtitle={formatDatetime(evt.eventDatetime)}
              after={
                <Button size="s" mode="outline" onClick={(e) => { e.stopPropagation(); openAttendanceModal(evt); }}>
                  Присутствие
                </Button>
              }
            >
              {evt.title}
            </Cell>
          ))}
        </Section>
      )}

      {upcomingEvents.length === 0 && completedEvents.length === 0 && !showForm && (
        <Placeholder description="Событий пока нет. Создайте первое!" />
      )}

      {/* Event Detail Modal */}
      {selectedEventId && (
        <EventDetailModal eventId={selectedEventId} onClose={() => setSelectedEventId(null)} />
      )}

      {/* Attendance Modal */}
      {attendanceEventId !== null && (
        <Modal open onOpenChange={(open) => { if (!open) setAttendanceEventId(null); }}>
          <div style={{ padding: 16 }}>
            <Text weight="2" style={{ fontSize: 18, display: 'block', marginBottom: 12 }}>
              Отметить присутствие
            </Text>

            {attendanceList.length === 0 && (
              <div style={{ padding: '12px 0', color: 'var(--tgui--hint_color)', fontSize: 14, lineHeight: 1.5 }}>
                Список подтверждённых участников загружается из данных события.
                Добавьте участников вручную или отметьте присутствие через API.
              </div>
            )}

            {attendanceList.map((item, idx) => (
              <Cell
                key={item.userId}
                Component="label"
                before={
                  <Checkbox
                    checked={item.attended}
                    onChange={(e) => {
                      const next = [...attendanceList];
                      next[idx] = { ...next[idx], attended: (e.target as HTMLInputElement).checked };
                      setAttendanceList(next);
                    }}
                  />
                }
              >
                {item.userId.slice(0, 8)}...
              </Cell>
            ))}

            <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
              <Button
                size="m"
                mode="outline"
                onClick={() => setAttendanceEventId(null)}
                stretched
              >
                Закрыть
              </Button>
              {attendanceList.length > 0 && (
                <Button
                  size="m"
                  onClick={handleMarkAttendance}
                  disabled={markingAttendance}
                  stretched
                >
                  {markingAttendance ? <Spinner size="s" /> : 'Сохранить'}
                </Button>
              )}
            </div>
          </div>
        </Modal>
      )}
    </>
  );
};

// ---- Finances Tab ----

const FinancesTab: FC<{ clubId: string }> = ({ clubId }) => {
  const [finances, setFinances] = useState<FinancesDto | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    getFinances(clubId)
      .then(setFinances)
      .finally(() => setLoading(false));
  }, [clubId]);

  if (loading) {
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
  onUpdated: (club: ClubDetailDto) => void;
  onDeleted: (clubName: string) => void;
}

const SettingsTab: FC<SettingsTabProps> = ({ club, onUpdated, onDeleted }) => {
  const [name, setName] = useState(club.name);
  const [city, setCity] = useState(club.city);
  const [district, setDistrict] = useState(club.district ?? '');
  const [memberLimit, setMemberLimit] = useState(String(club.memberLimit));
  const [subscriptionPrice, setSubscriptionPrice] = useState(String(club.subscriptionPrice));
  const [description, setDescription] = useState(club.description);
  const [rules, setRules] = useState(club.rules ?? '');
  const [applicationQuestion, setApplicationQuestion] = useState(club.applicationQuestion ?? '');
  const [avatarUrl, setAvatarUrl] = useState<string | null>(club.avatarUrl ?? null);

  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [deleting, setDeleting] = useState(false);

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

  const handleSave = async () => {
    setError(null);

    // Basic validation (server does full Bean Validation)
    if (name.trim().length < 1 || name.trim().length > 60) {
      setError('Название: 1–60 символов');
      return;
    }
    if (!city.trim()) {
      setError('Укажите город');
      return;
    }
    const limit = Number(memberLimit);
    if (!Number.isInteger(limit) || limit < 10 || limit > 80) {
      setError('Лимит участников: 10–80');
      return;
    }
    const price = Number(subscriptionPrice);
    if (!Number.isInteger(price) || price < 0) {
      setError('Цена: целое число >= 0');
      return;
    }
    if (description.trim().length < 1 || description.trim().length > 500) {
      setError('Описание: 1–500 символов');
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

    setSaving(true);
    try {
      const updated = await updateClub(club.id, payload);
      onUpdated(updated);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    setDeleting(true);
    try {
      await deleteClub(club.id);
      onDeleted(club.name);
    } catch (e) {
      setError((e as Error).message);
      setDeleting(false);
    }
  };

  return (
    <>
      <Section header="Аватар">
        <div style={{ padding: 16 }}>
          <AvatarUpload value={avatarUrl} onChange={setAvatarUrl} disabled={saving || deleting} />
        </div>
      </Section>

      <Section header="Основное">
        <Input header="Название" value={name} onChange={(e) => setName(e.target.value)} />
        <Input header="Город" value={city} onChange={(e) => setCity(e.target.value)} />
        <Input header="Район (опционально)" value={district} onChange={(e) => setDistrict(e.target.value)} />
        <Input
          header="Лимит участников (10–80)"
          type="number"
          value={memberLimit}
          onChange={(e) => setMemberLimit(e.target.value)}
        />
        <Input
          header="Цена подписки (Stars/мес, 0 = бесплатно)"
          type="number"
          value={subscriptionPrice}
          onChange={(e) => setSubscriptionPrice(e.target.value)}
        />
      </Section>

      <Section header="Описание и правила">
        <Textarea
          header="Описание"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
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

      <Section header="Опасная зона">
        <div style={{ padding: 16 }}>
          <Button
            size="l"
            stretched
            mode="outline"
            onClick={() => setShowDeleteModal(true)}
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
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState<TabKey>('members');
  const [club, setClub] = useState<ClubDetailDto | null>(null);
  const [clubLoading, setClubLoading] = useState(true);

  const clubId = id ?? '';

  useEffect(() => {
    if (!clubId) return;
    setClubLoading(true);
    getClub(clubId)
      .then(setClub)
      .finally(() => setClubLoading(false));
  }, [clubId]);

  if (!clubId) {
    return <Placeholder description="Клуб не найден" />;
  }

  const renderTab = () => {
    switch (activeTab) {
      case 'members':
        return <MembersTab clubId={clubId} />;
      case 'applications':
        return <ApplicationsTab clubId={clubId} />;
      case 'events':
        return <EventsTab clubId={clubId} />;
      case 'finances':
        return <FinancesTab clubId={clubId} />;
      case 'settings':
        if (!club) return <div style={{ padding: 16 }}><Spinner size="m" /></div>;
        return (
          <SettingsTab
            club={club}
            onUpdated={setClub}
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
    <List>
      {/* Club header */}
      {!clubLoading && club && (
        <Section>
          <Cell subtitle={`${club.memberCount} / ${club.memberLimit} участников | ${club.city}`}>
            {club.name}
          </Cell>
        </Section>
      )}

      {/* Tabs */}
      <div style={{ padding: '0 16px', marginBottom: 8 }}>
        <TabsList>
          {(Object.keys(TAB_LABELS) as TabKey[]).map((key) => (
            <TabsList.Item
              key={key}
              selected={activeTab === key}
              onClick={() => setActiveTab(key)}
            >
              {TAB_LABELS[key]}
            </TabsList.Item>
          ))}
        </TabsList>
      </div>

      {/* Tab content */}
      {renderTab()}
    </List>
  );
};

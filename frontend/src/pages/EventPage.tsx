import { FC, ReactElement, useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { useParams, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/apiClient';
import { Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useAuthStore } from '../store/useAuthStore';
import { useClubQuery, useMyClubsQuery } from '../queries/clubs';
import { useMyReputationQuery } from '../queries/members';
import { isActiveManagerMembership } from '../utils/membershipRole';
import { formatLeadInterval, toDatetimeLocalValue } from '../utils/formatters';
import { eventToTemplateBody } from '../utils/eventTemplate';
import { openTmeLink } from '../utils/telegramLinks';
import { useSaveEventTemplateMutation } from '../queries/eventTemplates';
import { useEventSplitStateQuery } from '../queries/skladchina';
import { useSetClubContext } from '../store/useClubContextStore';
import { Toast } from '../components/Toast';
import { EventPlaceCard } from '../components/event/EventPlaceCard';
import { LocationPickerSheet } from '../components/event/LocationPickerSheet';
import {
  useCastVoteMutation,
  useConfirmParticipationMutation,
  useDeclineParticipationMutation,
  useDisputeAttendanceMutation,
  useEventQuery,
  useEventRespondersQuery,
  useMarkAttendanceMutation,
  useMyAttendanceQuery,
  useMyVoteQuery,
  useCancelEventMutation,
  useUpdateEventMutation,
  useResolveDisputeMutation,
  useRemindToConfirmMutation,
  useEventPendingQuery,
} from '../queries/events';

function getInitials(name: string): string {
  return name.replace(/[«»"']/g, '').split(/\s+/).filter(Boolean).slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase()).join('');
}


/** Маппит статус откликнувшегося в класс цвета точки (go / maybe / expired / no). */
function statusDotClass(status: string): string {
  if (status === 'going' || status === 'confirmed') return 'rd-d-go';
  if (status === 'maybe' || status === 'waitlisted') return 'rd-d-maybe';
  if (status === 'expired_no_confirm') return 'rd-d-expired';
  return 'rd-d-no';
}

/**
 * Метка-иконка голоса в кнопке: цветной кружок с ✓ / ? / ✕. Цвет берётся от кнопки
 * (`currentColor`), сама иконка рисуется цветом фона страницы.
 */
const VOTE_ICONS: Record<'going' | 'maybe' | 'not_going', ReactElement> = {
  going: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 6 9 17l-5-5" />
    </svg>
  ),
  maybe: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
      <path d="M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3" />
      <path d="M12 17h.01" />
    </svg>
  ),
  not_going: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.2" strokeLinecap="round">
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  ),
};

/**
 * Метка плитки «В очереди» (Этап 2+) — часы: участник уже подтвердил, но упёрся в лимит мест
 * и ждёт освободившийся слот. Галочка и знак вопроса заняты подтверждёнными и молчунами.
 */
const QUEUE_ICON: ReactElement = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7v5l3 2" />
  </svg>
);

/**
 * Табы секции «Кто откликнулся» (Этап 1). Ключ — значение `status` в ростере: бэкенд отдаёт
 * `stage_1_vote` как есть, включая `not_going`, поэтому фильтрация чисто клиентская.
 */
const RESPONDER_TABS = [
  { key: 'going', label: 'Идут' },
  { key: 'maybe', label: 'Возможно' },
  { key: 'not_going', label: 'Не идут' },
] as const;

type ResponderTab = (typeof RESPONDER_TABS)[number]['key'];

/** Колокольчик «напомнить» и галочка «уже напомнили» — иконки кнопки в строке молчуна. */
const BELL_ICON: ReactElement = (
  <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
    <path d="M13.7 21a2 2 0 0 1-3.4 0" />
  </svg>
);
const CHECK_ICON: ReactElement = (
  <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round">
    <path d="M20 6 9 17l-5-5" />
  </svg>
);

/** Сколько строк ростера показываем до нажатия «Показать всех». */
const ROSTER_PREVIEW_SIZE = 6;

/**
 * Формат username в Telegram: латиница, цифры и подчёркивание, до 32 символов. Значение приходит
 * из БД и подставляется в URL личного чата, поэтому формат проверяем перед подстановкой — иначе
 * посторонние символы (`/`, `?`, `#`) увели бы ссылку на чужой адрес или дописали к ней параметры.
 * Не прошедшее проверку значение трактуем как «username нет»: строка просто не будет кликабельной.
 */
const TELEGRAM_USERNAME_RE = /^[A-Za-z0-9_]{1,32}$/;

function telegramChatUsername(raw: string | null | undefined): string | null {
  return raw && TELEGRAM_USERNAME_RE.test(raw) ? raw : null;
}

/**
 * Момент отправки напоминания в строке молчуна. Только время: напоминать можно, лишь пока
 * открыто окно подтверждения, поэтому дата всегда «сегодня-завтра» и лишь удлиняла бы строку.
 */
function formatRemindedAt(iso: string): string {
  return new Date(iso).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
}

/**
 * Хвост длинного ростера: одна и та же кнопка нужна списку Этапа 1 и обоим табам состава
 * Этапа 2, поэтому живёт отдельным компонентом, а не тремя копиями разметки.
 */
const RosterMoreButton: FC<{ total: number; onExpand: () => void }> = ({ total, onExpand }) => (
  <button type="button" className="rd-resp-more" onClick={onExpand}>
    Показать всех · {total}
  </button>
);

// Лимит имени шаблона встречи — зеркалит VARCHAR(60) и @Size(max=60) бэкенда.
const TEMPLATE_NAME_MAX = 60;

/** Радиус дуги кольца занятости в координатах viewBox (128×128 при обводке 11). */
const DONUT_RADIUS = 57;
/** Длина окружности кольца — знаменатель stroke-dasharray, которым отмеряется дуга. */
const DONUT_CIRCUMFERENCE = 2 * Math.PI * DONUT_RADIUS;

// Русские подписи статусов голоса/участия — для бейджей и строки «Ваш голос».
const VOTE_LABELS: Record<string, string> = {
  going: 'Пойду',
  maybe: 'Возможно',
  not_going: 'Не пойду',
  confirmed: 'Подтверждён',
  waitlisted: 'В очереди',
  declined: 'Отказался',
  expired_no_confirm: 'Не подтвердил',
};

function formatEventDate(iso: string): string {
  return new Date(iso).toLocaleString('ru-RU', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export const EventPage: FC = () => {
  useBackButton(true);

  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const haptic = useHaptic();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const userId = useAuthStore((s) => s.user?.id);

  const eventQuery = useEventQuery(isAuthenticated ? id : undefined);
  const myVoteQuery = useMyVoteQuery(isAuthenticated ? id : undefined);
  // «Путь назад» вариант C: репутация вызывающего по клубам (общий кэш с Профилем/«Моими клубами»).
  const myReputationQuery = useMyReputationQuery();
  // Роль вызывающего в клубе события (co-organizers): организаторские контролы (посещаемость,
  // споры, отмена) доступны и активному со-организатору. Общий кэш «Моих клубов».
  const myClubsQuery = useMyClubsQuery();
  const hostClubQuery = useClubQuery(eventQuery.data?.clubId);
  const respondersQuery = useEventRespondersQuery(isAuthenticated ? id : undefined);
  // Существующий сплит этого события — кнопка «Разделить счёт» открывает его / блокирует пересоздание.
  const eventSplitQuery = useEventSplitStateQuery(isAuthenticated ? id : undefined);
  // F5-04: собственная явка вызывающего — управляет контролами спора даже у участника, вышедшего
  // из клуба (member-gated запрос responders отдаёт ему 403). Нужна только пока открыто окно спора;
  // 404 (организатор / не-участник) ожидаем и трактуется как «UI спора не показываем».
  const myAttendanceQuery = useMyAttendanceQuery(
    isAuthenticated && eventQuery.data?.attendanceMarked && !eventQuery.data?.attendanceFinalized
      ? id
      : undefined,
  );
  useSetClubContext(eventQuery.data?.clubId);

  // Не-участник на странице БУДУЩЕГО события (кикнутый/вышедший, пришёл по старой ссылке или
  // кнопке из чата): голосовать нельзя, ростер закрыт (403) — уводим на страницу клуба, где есть
  // CTA вступления/оплаты (PO 2026-07-08). ПРОШЕДШИЕ события не редиректим: экс-участнику может
  // быть нужно окно спора явки (F5-04 — myAttendance намеренно не гейтится членством).
  const respondersForbidden =
    respondersQuery.error instanceof ApiError && respondersQuery.error.status === 403;
  const redirectClubId =
    respondersForbidden &&
    eventQuery.data &&
    new Date(eventQuery.data.eventDatetime).getTime() > Date.now()
      ? eventQuery.data.clubId
      : null;
  useEffect(() => {
    if (redirectClubId) navigate(`/clubs/${redirectClubId}`, { replace: true });
  }, [redirectClubId, navigate]);

  const castVoteMutation = useCastVoteMutation();
  const confirmMutation = useConfirmParticipationMutation();
  const declineMutation = useDeclineParticipationMutation();
  const markAttendanceMutation = useMarkAttendanceMutation();
  const disputeMutation = useDisputeAttendanceMutation();
  const resolveMutation = useResolveDisputeMutation();
  const remindMutation = useRemindToConfirmMutation();
  // Менеджер клуба события: владелец ИЛИ активный со-организатор (fail-close — роль со-орга
  // действует только при активном членстве, зеркалит серверный гейт AttendanceService/EventService).
  // Считается до ранних return'ов: от него зависит enabled запроса «Без ответа».
  const myHostMembership = myClubsQuery.data?.find((m) => m.clubId === eventQuery.data?.clubId);
  const isManager =
    (!!hostClubQuery.data && hostClubQuery.data.ownerId === userId)
    || isActiveManagerMembership(myHostMembership);
  // Поимённый список молчунов — менеджерский эндпоинт, участнику он вернёт 403.
  const pendingQuery = useEventPendingQuery(
    isAuthenticated ? id : undefined,
    isManager && eventQuery.data?.status === 'stage_2',
  );
  const cancelMutation = useCancelEventMutation();
  const updateMutation = useUpdateEventMutation();

  // Два отдельных канала ошибок: actionError — для голоса/подтверждения/отказа, attendanceError —
  // для отметки явки. actionError рендерится ровно в одном слоте на фазу — блок голосования Этапа 1
  // (за гейтом showVoting) ЛИБО блок подтверждения Этапа 2 — никогда оба сразу (F5-23).
  // Каждый обработчик сбрасывает свой канал перед запуском, так что каналы тоже не сталкиваются.
  const [actionError, setActionError] = useState<string | null>(null);
  const [attendanceError, setAttendanceError] = useState<string | null>(null);
  // Только явные переопределения; отсутствие записи означает «пришёл» (attended[id] ?? true).
  const [attended, setAttended] = useState<Record<string, boolean>>({});
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  // Секция «Кто откликнулся» (Этап 1): выбранный таб статуса и раскрытие длинного списка.
  const [responderTab, setResponderTab] = useState<ResponderTab>('going');
  const [rosterExpanded, setRosterExpanded] = useState(false);
  // Секция состава (Этап 2+): менеджеру доступен второй таб — имена тех, кто ещё не подтвердил.
  // Открыт «Идут» по умолчанию, чтобы привычный вид оставался первым.
  const [stage2Tab, setStage2Tab] = useState<'confirmed' | 'pending'>('confirmed');
  // Ошибка отправки напоминания — своим слотом под панелью, чтобы не смешиваться с actionError
  // подтверждения/отказа (тот живёт в блоке «Подтверждение участия» ниже).
  const [remindError, setRemindError] = useState<string | null>(null);
  // Необязательный комментарий, который участник прикладывает, оспаривая отметку «не пришёл».
  const [disputeNote, setDisputeNote] = useState('');
  // F5-14 шторка отмены события: флаг открытия, необязательная причина и собственный слот ошибки.
  const [cancelOpen, setCancelOpen] = useState(false);
  const [cancelReason, setCancelReason] = useState('');
  const [cancelError, setCancelError] = useState<string | null>(null);
  // Шторка «Сохранить как шаблон»: одно поле — имя (предзаполняется названием встречи).
  // Содержимое шаблона целиком берётся из уже загруженной встречи, поэтому формы полей тут нет.
  const [templateOpen, setTemplateOpen] = useState(false);
  const [templateName, setTemplateName] = useState('');
  const [templateError, setTemplateError] = useState<string | null>(null);
  const [templateSaved, setTemplateSaved] = useState(false);
  const saveTemplateMutation = useSaveEventTemplateMutation();
  // Шторка редактирования встречи (только Этап 1). Поля держим отдельными строками, а не
  // копией DTO: форма правит текст, а собирается payload уже при сохранении.
  const [editOpen, setEditOpen] = useState(false);
  const [editTitle, setEditTitle] = useState('');
  const [editDescription, setEditDescription] = useState('');
  const [editLocation, setEditLocation] = useState<{ text: string | null; lat: number | null; lon: number | null }>(
    { text: null, lat: null, lon: null },
  );
  const [editHint, setEditHint] = useState('');
  const [editDatetime, setEditDatetime] = useState('');
  const [editLimit, setEditLimit] = useState('');
  const [editError, setEditError] = useState<string | null>(null);
  const [editPickerOpen, setEditPickerOpen] = useState(false);
  // Инлайн-подтверждение отказа от подтверждённого места (защита от случайного клика).
  const [confirmingDecline, setConfirmingDecline] = useState(false);

  const event = eventQuery.data;
  const myVote = myVoteQuery.data?.vote ?? null;
  const loading = eventQuery.isPending || myVoteQuery.isPending;
  const voting =
    castVoteMutation.isPending || confirmMutation.isPending || declineMutation.isPending;
  const loadError = eventQuery.error?.message;

  const handleVote = (vote: 'going' | 'maybe' | 'not_going') => {
    if (!id || voting) return;
    haptic.select();
    setActionError(null);
    castVoteMutation.mutate(
      { eventId: id, vote },
      {
        onSuccess: () => haptic.notify('success'),
        onError: (e) => {
          setActionError(e.message);
          haptic.notify('error');
        },
      },
    );
  };

  const handleConfirm = () => {
    if (!id || voting) return;
    haptic.impact('medium');
    setActionError(null);
    confirmMutation.mutate(id, {
      onSuccess: () => haptic.notify('success'),
      onError: (e) => {
        setActionError(e.message);
        haptic.notify('error');
      },
    });
  };

  const handleDecline = () => {
    if (!id || voting) return;
    haptic.impact('medium');
    setActionError(null);
    declineMutation.mutate(id, {
      onSuccess: () => haptic.notify('warning'),
      onError: (e) => {
        setActionError(e.message);
        haptic.notify('error');
      },
    });
  };

  const toggleAttended = (uid: string) => {
    haptic.select();
    // По умолчанию «пришёл»: отсутствующие — меньшинство, поэтому организатор снимает галочку
    // только с тех, кто не явился (решение 2026-06-11, ред. 2).
    setAttended((prev) => ({ ...prev, [uid]: !(prev[uid] ?? true) }));
  };

  const handleMarkAttendance = (candidates: { userId: string }[]) => {
    if (!id || markAttendanceMutation.isPending) return;
    haptic.impact('medium');
    setAttendanceError(null);
    const attendance = candidates.map((c) => ({
      userId: c.userId,
      attended: attended[c.userId] ?? true,
    }));
    markAttendanceMutation.mutate(
      { eventId: id, attendance },
      {
        onSuccess: () => {
          haptic.notify('success');
          setToastMessage('Посещаемость отмечена');
        },
        onError: (e) => {
          setAttendanceError(e.message);
          haptic.notify('error');
        },
      },
    );
  };

  // ATT-3: участник, отмеченный отсутствующим, оспаривает отметку (absent → disputed). Доступно
  // только пока открыто окно спора (marked && !finalized) — см. гейтинг ниже.
  /**
   * Напоминание подтвердить участие: `targetUserId` — конкретный молчун, `undefined` — все,
   * кому ещё можно. Набор целей для «всем» считает сервер, поэтому сюда список не передаём.
   * Ответ несёт число реально отправленных: сервер молча пропускает уже напомненных, и тост
   * должен говорить правду, а не «отправлено» на пустую рассылку.
   */
  const handleRemind = (targetUserId: string | undefined) => {
    if (!id || remindMutation.isPending) return;
    haptic.impact('medium');
    setRemindError(null);
    remindMutation.mutate(
      { eventId: id, userId: targetUserId },
      {
        onSuccess: ({ remindedCount }) => {
          haptic.notify(remindedCount > 0 ? 'success' : 'warning');
          setToastMessage(
            remindedCount > 0
              ? `Напоминание отправлено · ${remindedCount}`
              : 'Всем, кому можно, уже напомнили',
          );
        },
        onError: (e) => {
          setRemindError(e.message);
          haptic.notify('error');
        },
      },
    );
  };

  const handleDispute = () => {
    if (!id || disputeMutation.isPending) return;
    haptic.impact('medium');
    setAttendanceError(null);
    disputeMutation.mutate(
      { eventId: id, note: disputeNote.trim() || undefined },
      {
        onSuccess: () => {
          haptic.notify('success');
          setDisputeNote('');
          setToastMessage('Отметка оспорена — организатор примет решение');
        },
        onError: (e) => {
          setAttendanceError(e.message);
          haptic.notify('error');
        },
      },
    );
  };

  // Организатор разрешает оспоренную отметку в «пришёл»/«не пришёл» до закрытия окна.
  const handleResolve = (targetUserId: string, attendedResult: boolean) => {
    if (!id || resolveMutation.isPending) return;
    haptic.impact('medium');
    setAttendanceError(null);
    resolveMutation.mutate(
      { eventId: id, userId: targetUserId, attended: attendedResult },
      {
        onSuccess: () => {
          haptic.notify('success');
          setToastMessage(attendedResult ? 'Засчитано «Пришёл»' : 'Засчитано «Не пришёл»');
        },
        onError: (e) => {
          setAttendanceError(e.message);
          haptic.notify('error');
        },
      },
    );
  };

  // F5-14: организатор отменяет событие (причина опциональна). При успехе закрывает шторку и тостит.
  const handleCancelEvent = () => {
    if (!id || !event || cancelMutation.isPending) return;
    haptic.impact('medium');
    setCancelError(null);
    cancelMutation.mutate(
      { eventId: id, clubId: event.clubId, reason: cancelReason.trim() || undefined },
      {
        onSuccess: () => {
          haptic.notify('success');
          setCancelOpen(false);
          setCancelReason('');
          setToastMessage('Событие отменено');
        },
        onError: (e) => {
          setCancelError(e.message);
          haptic.notify('error');
        },
      },
    );
  };

  /**
   * Сохранение встречи как шаблона клуба. Содержимое собирается из уже загруженного DTO —
   * спрашиваем только имя, предзаполняя его названием встречи. Дата не переносится: вместо
   * неё выводятся день недели и время (docs/modules/event-templates.md § 4).
   */
  const openSaveTemplate = () => {
    if (!event) return;
    haptic.impact('medium');
    setTemplateError(null);
    setTemplateSaved(false);
    setTemplateName(event.title.slice(0, TEMPLATE_NAME_MAX));
    setTemplateOpen(true);
  };

  const handleSaveTemplate = async () => {
    if (!event || saveTemplateMutation.isPending) return;
    const name = templateName.trim();
    if (!name) { setTemplateError('Укажите имя шаблона'); haptic.notify('error'); return; }
    setTemplateError(null);
    try {
      await saveTemplateMutation.mutateAsync({
        clubId: event.clubId,
        body: eventToTemplateBody(event, name),
      });
      haptic.notify('success');
      setTemplateSaved(true);
      setTemplateOpen(false);
    } catch (e) {
      haptic.notify('error');
      setTemplateError(e instanceof Error ? e.message : 'Не удалось сохранить шаблон');
    }
  };

  // Редактирование встречи (только Этап 1). Открытие шторки предзаполняет ВСЕ поля текущими
  // значениями: у PUT нет частичных правок, клиент присылает полный набор.
  const openEdit = () => {
    if (!event) return;
    haptic.impact('medium');
    setEditError(null);
    setEditTitle(event.title);
    setEditDescription(event.description ?? '');
    setEditLocation({
      text: event.locationText ?? null,
      lat: event.locationLat ?? null,
      lon: event.locationLon ?? null,
    });
    setEditHint(event.locationHint ?? '');
    setEditDatetime(toDatetimeLocalValue(new Date(event.eventDatetime)));
    setEditLimit(event.participantLimit != null ? String(event.participantLimit) : '');
    setEditOpen(true);
  };

  const handleUpdateEvent = () => {
    if (!id || !event || updateMutation.isPending) return;
    setEditError(null);

    const title = editTitle.trim();
    if (!title) { setEditError('Укажите название'); haptic.notify('error'); return; }
    if (!editDatetime) { setEditError('Укажите дату и время'); haptic.notify('error'); return; }
    const newDate = new Date(editDatetime);
    if (Number.isNaN(newDate.getTime())) { setEditError('Некорректная дата'); haptic.notify('error'); return; }
    if (newDate.getTime() <= Date.now()) { setEditError('Дата события должна быть в будущем'); haptic.notify('error'); return; }

    const hint = editHint.trim();
    // Тот же инвариант, что на бэкенде: у встречи должно остаться хоть какое-то указание места.
    const hasPoint = editLocation.lat != null && editLocation.lon != null;
    if (!hasPoint && !hint) {
      setEditError('Укажите место на карте или добавьте уточнение');
      haptic.notify('error');
      return;
    }

    // Формат неизменяем: лимит правим только у встречи с местами, у открытой его нет вовсе.
    let participantLimit: number | null = null;
    if (!isOpenEvent) {
      const parsed = Number(editLimit);
      if (!Number.isInteger(parsed) || parsed < 1) {
        setEditError('Лимит участников — целое число от 1');
        haptic.notify('error');
        return;
      }
      participantLimit = parsed;
    }

    haptic.impact('medium');
    updateMutation.mutate(
      {
        eventId: id,
        clubId: event.clubId,
        body: {
          title,
          description: editDescription.trim() || null,
          locationText: editLocation.text,
          locationLat: editLocation.lat,
          locationLon: editLocation.lon,
          locationHint: hint || null,
          eventDatetime: newDate.toISOString(),
          participantLimit,
          // Именно override, а не эффективное значение: иначе подставленный бэком дефолт
          // стал бы собственным интервалом события.
          stage2LeadMinutes: event.stage2LeadMinutesOverride ?? null,
          photoUrl: event.photoUrl ?? null,
        },
      },
      {
        onSuccess: () => {
          haptic.notify('success');
          setEditOpen(false);
          setToastMessage('Изменения сохранены');
        },
        onError: (e: Error) => {
          setEditError(e.message);
          haptic.notify('error');
        },
      },
    );
  };

  if (loading) {
    return (
      <div className="rd-page" style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
        <Spinner size="l" />
      </div>
    );
  }

  if (loadError || !event) {
    return (
      <div className="rd-page">
        <Placeholder header="Ошибка" description={loadError ?? 'Событие не найдено'} />
      </div>
    );
  }

  // Разделение фаз: Этап 1 (upcoming) замеряет интерес (раскладка голосов going/maybe); с Этапа 2
  // состав — это список подтвердивших, поэтому заголовок/пончик/счётчики переключаются на подтверждения.
  // Закрывает баг «отказавшийся всё ещё числится идущим» — declined/expired выпадают из «идут».
  const finalComposition = event.status === 'stage_2' || event.status === 'completed';
  // F5-14: у отменённого события показывается только баннер — набор/состав/явка скрываются.
  const isCancelled = event.status === 'cancelled';
  // Открытая встреча (V62): лимита нет — счётчики без знаменателя, отказ свободен (без штрафа
  // и порога), лист ожидания недостижим. См. events.md § «Открытая встреча».
  const isOpenEvent = event.participantLimit == null;

  // Кольцо считает ТОЛЬКО занятость мест — на обеих фазах: Этап 1 = going/лимит, Этап 2+ =
  // confirmed/лимит. Прежде на Этапе 1 дуга показывала доли голосов, а число внутри неё —
  // заполненность мест: два разных смысла в одном элементе читались как один (event-vote-block.md).
  // Открытая встреча: «занято/свободно» не существует — кольцо целиком закрашено при первом
  // отклике и пустое, пока откликов нет.
  const donutCount = finalComposition ? event.confirmedCount : event.goingCount;
  const donutRatio = isOpenEvent
    ? (donutCount > 0 ? 1 : 0)
    : Math.min(donutCount / (event.participantLimit || 1), 1);

  // Знаменатель счётчиков состава/набора; у открытой встречи его нет (лимит отсутствует).
  const limitSuffix = isOpenEvent ? '' : ` / ${event.participantLimit}`;

  const eventHappened = new Date(event.eventDatetime).getTime() <= Date.now();
  // Подтверждённый может отказаться (освободить место) только пока не прошёл дедлайн отказа. Дедлайн
  // считает бэкенд из своего env-порога и отдаёт в confirmedDeclineDeadline — фронт не хранит копию
  // порога. Бэкенд остаётся источником истины: declineParticipation всё равно отклонит поздний отказ.
  const confirmedCanDecline =
    myVote === 'confirmed' && new Date(event.confirmedDeclineDeadline).getTime() > Date.now();

  // Backend (`VoteService.castVote`) принимает голос ТОЛЬКО при status='upcoming'.
  const showVoting = event.status === 'upcoming';
  // Баг B: confirm/decline закрываются в момент старта события. Статус остаётся 'stage_2'
  // до часового completion-прохода, поэтому гейтим ещё и по !eventHappened — зеркалит
  // бэкенд-гард `event_datetime > now` в Stage2Service. См. events.md.
  const showStage2 = event.status === 'stage_2' && !eventHappened;

  // Перенос даты: новая дата ближе интервала Этапа 2 — не блокируем (паритет с созданием),
  // но предупреждаем, что подтверждение мест начнётся сразу. stage2LeadMinutes с бэка уже
  // эффективный (свой или дефолт); null = открытая встреча — предупреждение не нужно.
  const editTimeMs = editDatetime ? new Date(editDatetime).getTime() : null;
  const editStage2Immediate =
    event.stage2LeadMinutes != null &&
    editTimeMs !== null && !Number.isNaN(editTimeMs) &&
    editTimeMs > Date.now() &&
    editTimeMs - Date.now() <= event.stage2LeadMinutes * 60_000;
  const editLeadLabel =
    event.stage2LeadMinutes != null ? formatLeadInterval(event.stage2LeadMinutes) : null;

  // «Путь назад», вариант C (reputation-path-back.md AC-8): строка-мотиватор «придёте — надёжность
  // вырастет» при просадке Trust в клубе события. Скрыта у терминальных статусов: confirmed уже
  // пообещал, declined/expired звать бессмысленно. Данные — из общего кэша /users/me/reputation.
  const myClubRep = myReputationQuery.data?.activeClubs.find((r) => r.clubId === event.clubId);
  const nudgeTerminal = myVote === 'confirmed' || myVote === 'declined' || myVote === 'expired_no_confirm';
  // Открытая встреча репутацию за посещение НЕ начисляет (V62) — обещание «придёте — надёжность
  // вырастет» здесь было бы ложным, нудж скрыт.
  const showPathBackNudge =
    !isOpenEvent && myClubRep?.projectedNext1 != null && myClubRep?.trust != null && !nudgeTerminal;
  const pathBackNudge = showPathBackNudge ? (
    <div className="rd-pb-nudge">
      <span className="rd-pb-up" aria-hidden="true">↗</span>
      <span>
        Придёте на эту встречу — надёжность вырастет: <b>{myClubRep!.trust}</b> → <b>{myClubRep!.projectedNext1}</b>
      </span>
    </div>
  ) : null;

  // Отметка явки — только организатор и только после того, как событие состоялось. Бэкенд
  // (AttendanceService) гейтит по event_datetime <= now + флагу attendance_marked,
  // никогда по status (см. events.md § attendance flow). Кандидаты = ФИНАЛЬНЫЙ состав:
  // только подтвердившие на Этапе 2 (PRD §4.4.3 «список финальных участников,
  // кто подтвердил на Этапе 2»). Голосовавшие going/maybe, но не подтвердившие («забыли
  // подтвердить» → expired_no_confirm) в составе НЕ значатся — здесь они исключены,
  // и репутация их игнорирует (она читает только final_status=confirmed).
  const attendanceCandidates = (respondersQuery.data ?? []).filter(
    (r) => r.status === 'confirmed',
  );
  // Окно спора = явка отмечена, но ещё не закреплена (48 часов до фиксации репутации).
  const disputeWindowOpen = event.attendanceMarked && !event.attendanceFinalized;
  // EXP-2: нейтрально авто-закрытое событие finalized, но так и не marked. UI отметки в этом
  // состоянии обязан скрыться (бэкенд отклонит позднюю отметку с "Attendance has been finalized").
  const showAttendanceMarking =
    isManager && eventHappened && !event.attendanceMarked && !event.attendanceFinalized && !isCancelled;
  const showAttendanceDone = isManager && eventHappened && event.attendanceMarked;
  const showAttendanceExpired =
    isManager && eventHappened && !event.attendanceMarked && event.attendanceFinalized;
  // Список на разрешение у организатора: подтверждённые участники с оспоренной сейчас отметкой.
  const disputedCandidates = attendanceCandidates.filter((r) => r.attendance === 'disputed');
  // F5-04: собственная явка участника берётся из /my-attendance (доступен и без членства),
  // а не из member-gated списка responders. canDispute считается на сервере
  // (окно открыто И absent И ещё не terminal — F5-16), так что кнопка не «пинг-понгует».
  const myAttendance = myAttendanceQuery.data;
  // Дополнительно AND с живым disputeWindowOpen: query отключается после финализации события,
  // и его закешированный canDispute иначе мог бы остаться true при свежих данных о финализации.
  const canDispute = disputeWindowOpen && !!myAttendance?.canDispute;
  const myDisputePending = disputeWindowOpen && myAttendance?.attendance === 'disputed';
  // F5-16: организатор отклонил спор (resolve='не пришёл') — терминально, повторный спор невозможен.
  const myDisputeRejected =
    disputeWindowOpen && myAttendance?.attendance === 'absent' && !!myAttendance?.disputeTerminal;

  // «Кто идёт» с учётом фазы. Этап 1: все откликнувшиеся (список интереса, как раньше). Этап 2+:
  // только подтверждённый состав — pending (всё ещё going/maybe, без подтверждения), waitlisted,
  // declined и expired сводятся в счётчики и не показываются как «идут».
  const responders = respondersQuery.data ?? [];
  // «Без ответа» — все участники клуба, кроме сказавших «не пойду» и уже ответивших (решение PO
  // 2026-08-16). Счётчик приходит с бэка (виден всем), поимённый список — отдельным менеджерским
  // запросом. Это НЕ «В очереди»: там как раз ответили, но упёрлись в лимит мест.
  const pendingCount = event.noAnswerCount;
  const waitlistedCount = responders.filter((r) => r.status === 'waitlisted').length;
  const comingList = finalComposition ? responders.filter((r) => r.status === 'confirmed') : responders;
  // Лист ожидания (только Этап 2+): waitlisted в порядке приоритета. Бэкенд отдаёт респондеров по
  // stage_1_timestamp ASC — тому же ключу, по которому продвигается очередь (findFirstWaitlisted),
  // поэтому фильтр сохраняет реальный порядок продвижения.
  const waitlist = finalComposition ? responders.filter((r) => r.status === 'waitlisted') : [];
  // W3-09: свежее событие Этапа 1 без единого отклика — строка-намёк под блоком голосования вместо
  // немой пустоты. Гейт isSuccess (урок F5-20/F5-22): при загрузке/ошибке responders строку НЕ
  // показываем — ложная пустота недопустима. Исчезает сама после первого голоса: голосующий (в т.ч.
  // сам вызывающий) появляется в responders → comingList становится непустым.
  const showVoteRosterHint =
    !isCancelled && showVoting && respondersQuery.isSuccess && comingList.length === 0;

  // Переключатель составов Этапа 2 — только менеджеру (владелец или активный со-орг) и только
  // когда есть кого догонять: все подтвердились → лишнего элемента на экране не появляется.
  // Переключатель у менеджера есть всегда: иначе о самой возможности догнать молчунов он
  // узнавал бы только при удачном стечении обстоятельств.
  const showStage2Tabs = finalComposition && !isCancelled && isManager;
  const pendingResponders = pendingQuery.data ?? [];
  // Активный список секции состава. Таб «Без ответа» существует только при showStage2Tabs,
  // поэтому потеря менеджерства (или обнуление списка) сама возвращает экран к «Идут».
  const stage2List = showStage2Tabs && stage2Tab === 'pending' ? pendingResponders : comingList;
  const visibleStage2 = rosterExpanded ? stage2List : stage2List.slice(0, ROSTER_PREVIEW_SIZE);
  // Сколько молчунов ещё не получали напоминания — счётчик «Напомнить всем». Считаем по ВСЕМУ
  // списку, а не по видимой части: свёрнутый ростер не должен занижать число адресатов.
  const remindableCount = pendingResponders.filter((r) => !r.remindedAt).length;

  // Секция «Кто откликнулся» (Этап 1): те же ярлыки формата, что на карточках лент, но список
  // разложен по статусу — прежде «возможно» и «не иду» отличались только цветом точки в общей сетке.
  const respondersInTab = responders.filter((r) => r.status === responderTab);
  const visibleResponders = rosterExpanded
    ? respondersInTab
    : respondersInTab.slice(0, ROSTER_PREVIEW_SIZE);
  // Счётчики табов считаем ПО РОСТЕРУ, а не по счётчикам detail-запроса: иначе «Идут (12)»
  // могло разойтись со списком под ним (два разных запроса, каждый со своим моментом времени).
  const tabCounts: Record<ResponderTab, number> = {
    going: responders.filter((r) => r.status === 'going').length,
    maybe: responders.filter((r) => r.status === 'maybe').length,
    not_going: responders.filter((r) => r.status === 'not_going').length,
  };

  // Формат встречи в бейдже хиро (PO 2026-08-01): вместо родового «СОБЫТИЕ» — конкретный тип,
  // ярлыки и эмодзи те же, что на карточках лент (feed/EventCard, ярлыки PO 2026-07-23).
  const formatBadge = event.isUrgent
    ? '⚡ СРОЧНАЯ ВСТРЕЧА'
    : isOpenEvent
      ? '🌊 ОТКРЫТАЯ ВСТРЕЧА'
      : '🎟 ВСТРЕЧА С МЕСТАМИ';

  // Фон хиро: фото события (решение PO 2026-07-11 — прежде нигде не показывалось),
  // фолбэк — аватар клуба, как раньше.
  const heroImage = event.photoUrl ?? hostClubQuery.data?.avatarUrl ?? null;

  return (
    <div className="rd-page">
      {/* Хиро — фото события как фон (решение PO 2026-07-11: фото прежде нигде не
          показывалось); фолбэк — аватар клуба, как раньше. */}
      <div className="rd-hero rd-compact">
        <div
          className="rd-hero-bg"
          data-cat={hostClubQuery.data?.category ?? 'sport'}
          style={heroImage ? { backgroundImage: `url(${heroImage})` } : undefined}
        />
        <div className="rd-hero-meta">
          <div className="rd-hero-type-badge">{formatBadge}</div>
          <div className="rd-hero-ttl">{event.title}</div>
          <div className="rd-hero-eyebrow" style={{ marginTop: 6 }}>
            {formatEventDate(event.eventDatetime)}
          </div>
        </div>
      </div>

      {/* Клуб-организатор */}
      {hostClubQuery.data && (
        <button
          type="button"
          className="rd-glass"
          style={{ display: 'block', width: '100%', textAlign: 'left', padding: 0, marginBottom: 14, cursor: 'pointer' }}
          onClick={() => { haptic.impact('light'); navigate(`/clubs/${event.clubId}`); }}
        >
          <div className="rd-host-row">
            <span className="rd-ico">
              {hostClubQuery.data.avatarUrl
                ? <img src={hostClubQuery.data.avatarUrl} alt="" />
                : getInitials(hostClubQuery.data.name)}
            </span>
            <div className="rd-info">
              <div className="rd-ttl">{hostClubQuery.data.name}</div>
              <div className="rd-met">организатор</div>
            </div>
          </div>
        </button>
      )}

      {/* Место проведения: с гео-точкой — мини-карта + маршрут (event-geo, кадр C);
          без координат — текстом: адрес (легаси) и/или уточнение организатора (V58). */}
      {event.locationLat != null && event.locationLon != null ? (
        <EventPlaceCard
          locationText={event.locationText ?? 'Место на карте'}
          locationHint={event.locationHint}
          point={{ lat: event.locationLat, lon: event.locationLon }}
        />
      ) : (event.locationText || event.locationHint) ? (
        <div className="rd-glass" style={{ marginBottom: 14, overflow: 'hidden' }}>
          {event.locationText && <div className="rd-mini-map" />}
          <div className="rd-addr-body">
            <div className="rd-a-ttl">{event.locationText ?? event.locationHint}</div>
            {event.locationText && event.locationHint && (
              <div className="rd-a-met">{event.locationHint}</div>
            )}
          </div>
        </div>
      ) : null}

      {/* Описание */}
      {event.description && (
        <>
          <div className="rd-section-sub-h">Описание</div>
          <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
            <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{event.description}</div>
          </div>
        </>
      )}

      {/* Отменено (F5-14): баннер заменяет блоки набора/состава и действий. */}
      {isCancelled && (
        <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14, borderLeft: '3px solid var(--danger)' }}>
          <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>
            ❌ <b>Событие отменено</b>{event.cancellationReason ? `: ${event.cancellationReason}` : '.'}
          </div>
        </div>
      )}

      {!isCancelled && (
      <>
      {/* Набор (Этап 1) / состав (Этап 2+) — пончик + голосование либо счётчики без действий */}
      <div className="rd-section-sub-h">
        {finalComposition
          ? `Состав · ${event.confirmedCount}${limitSuffix}`
          : `Набор · ${event.goingCount}${limitSuffix}`}
      </div>
      {/* Только ошибки голосования Этапа 1; ошибки confirm/decline Этапа 2 рендерятся в своём
          блоке ниже, так что actionError никогда не показывается дважды на этапе 2 (F5-23). */}
      {showVoting && actionError && <div className="rd-error">{actionError}</div>}
      <div className="rd-vote-layout">
        <div className="rd-vote-stack">
          {showVoting ? (
            <>
              <button type="button" className={`rd-vote-btn rd-vb-go${myVote === 'going' ? ' rd-active' : ''}`} onClick={() => handleVote('going')} disabled={voting}>
                <span className="rd-vm">{VOTE_ICONS.going}</span>
                <span className="rd-vl">Пойду</span>
                <span className="rd-vc">{event.goingCount}</span>
              </button>
              <button type="button" className={`rd-vote-btn rd-vb-maybe${myVote === 'maybe' ? ' rd-active' : ''}`} onClick={() => handleVote('maybe')} disabled={voting}>
                <span className="rd-vm">{VOTE_ICONS.maybe}</span>
                <span className="rd-vl">Возможно</span>
                <span className="rd-vc">{event.maybeCount}</span>
              </button>
              <button type="button" className={`rd-vote-btn rd-vb-no${myVote === 'not_going' ? ' rd-active' : ''}`} onClick={() => handleVote('not_going')} disabled={voting}>
                <span className="rd-vm">{VOTE_ICONS.not_going}</span>
                <span className="rd-vl">Не пойду</span>
                <span className="rd-vc">{event.notGoingCount}</span>
              </button>
            </>
          ) : finalComposition ? (
            /* Те же плитки, что кнопки голоса Этапа 1, но БЕЗ интерактива: на Этапе 2 счётчики
               никуда не ведут, поэтому это div, а не button (event-stage2-composition.md § 1). */
            <>
              <div className="rd-stat-tile rd-st-confirmed">
                <span className="rd-vm">{VOTE_ICONS.going}</span>
                <span className="rd-vl">Подтвердили</span>
                <span className="rd-vc">{event.confirmedCount}</span>
              </div>
              <div className="rd-stat-tile rd-st-pending">
                <span className="rd-vm">{VOTE_ICONS.maybe}</span>
                <span className="rd-vl">Без ответа</span>
                <span className="rd-vc">{pendingCount}</span>
              </div>
              {waitlistedCount > 0 && (
                <div className="rd-stat-tile rd-st-waitlist">
                  <span className="rd-vm">{QUEUE_ICON}</span>
                  <span className="rd-vl">В очереди</span>
                  <span className="rd-vc">{waitlistedCount}</span>
                </div>
              )}
            </>
          ) : (
            <div className="rd-glass" style={{ padding: '4px 4px' }}>
              <div className="rd-kv">Пойдут <span className="rd-v">{event.goingCount}</span></div>
              <div className="rd-kv">Возможно <span className="rd-v">{event.maybeCount}</span></div>
              <div className="rd-kv">Не пойдут <span className="rd-v">{event.notGoingCount}</span></div>
            </div>
          )}
        </div>
        {/* Кольцо занятости мест. SVG, а не conic-gradient: нужен скруглённый конец дуги.
            Диаметр 140px задан в CSS и равен высоте стопки кнопок (3 × 42 + 2 × 7). */}
        <div className="rd-donut" aria-hidden="true">
          <svg viewBox="0 0 128 128">
            <defs>
              {/* Объём дуги: блик сверху-слева → тёмный тон снизу-справа, как у акцентных кнопок. */}
              <linearGradient id="rd-donut-arc-grad" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stopColor="var(--donut-arc-hi)" />
                <stop offset="100%" stopColor="var(--donut-arc-lo)" />
              </linearGradient>
            </defs>
            <circle cx="64" cy="64" r={DONUT_RADIUS} fill="none" stroke="var(--ring-track)" strokeWidth="11" />
            {donutRatio > 0 && (
              <circle
                className="rd-donut-arc"
                cx="64" cy="64" r={DONUT_RADIUS} fill="none"
                stroke="url(#rd-donut-arc-grad)" strokeWidth="11" strokeLinecap="round"
                strokeDasharray={`${DONUT_CIRCUMFERENCE * donutRatio} ${DONUT_CIRCUMFERENCE}`}
              />
            )}
          </svg>
          <div className="rd-donut-center">
            <span className="rd-donut-num">
              {donutCount}
              {/* Открытая встреча: знаменателя нет — только счёт. */}
              {!isOpenEvent && <small> / {event.participantLimit}</small>}
            </span>
            <span className="rd-donut-cap">{isOpenEvent ? 'идут' : 'мест занято'}</span>
          </div>
        </div>
      </div>
      {showVoting && myVote && (
        <div style={{ marginBottom: 14 }}>
          <span className="rd-badge rd-going">Ваш голос: {VOTE_LABELS[myVote] ?? myVote}</span>
        </div>
      )}
      {/* Интервал Этапа 2 (V67): когда откроется подтверждение мест — свой у события или дефолт. */}
      {showVoting && !isOpenEvent && event.stage2LeadMinutes != null && (
        <div className="rd-hint" style={{ marginBottom: 14 }}>
          Подтверждение мест откроется за {formatLeadInterval(event.stage2LeadMinutes)} до начала
        </div>
      )}
      {showVoting && pathBackNudge}
      </>
      )}

      {/* Этап 1: отклики разложены по статусу — «возможно» и «не иду» прежде отличались от идущих
          только цветом точки в общей сетке, а их счётчики в кнопках вели в никуда. */}
      {!isCancelled && !finalComposition && comingList.length > 0 && (
        <>
          <div className="rd-section-sub-h">Кто откликнулся</div>
          <div className="rd-seg rd-seg-flush" style={{ marginBottom: 10 }}>
            {RESPONDER_TABS.map((tab) => (
              <button
                key={tab.key}
                type="button"
                className={`rd-seg-btn${responderTab === tab.key ? ' rd-active' : ''}`}
                aria-pressed={responderTab === tab.key}
                onClick={() => { haptic.impact('light'); setResponderTab(tab.key); setRosterExpanded(false); }}
              >
                {tab.label} ({tabCounts[tab.key]})
              </button>
            ))}
          </div>
          <div className="rd-glass rd-resp-panel">
            {visibleResponders.length === 0 ? (
              <div className="rd-resp-empty">Здесь пока пусто.</div>
            ) : (
              <>
                {visibleResponders.map((r) => {
                  const name = `${r.firstName}${r.lastName ? ` ${r.lastName[0]}.` : ''}`;
                  return (
                    <div className="rd-resp-row" key={r.userId}>
                      <div className="rd-voter">
                        <span className="rd-av">
                          {r.avatarUrl ? <img src={r.avatarUrl} alt="" /> : getInitials(name)}
                        </span>
                        <span className="rd-vn">{name}</span>
                        <span className={`rd-vdot ${statusDotClass(r.status)}`} title={r.status} />
                      </div>
                    </div>
                  );
                })}
                {respondersInTab.length > visibleResponders.length && (
                  <RosterMoreButton
                    total={respondersInTab.length}
                    onExpand={() => { haptic.impact('light'); setRosterExpanded(true); }}
                  />
                )}
              </>
            )}
          </div>
        </>
      )}

      {/* Этап 2+: состав той же панелью-стеклом, что «Кто откликнулся» на Этапе 1 — прежде здесь
          была голая сетка без подложки и сворачивания. Менеджеру добавляется второй таб с именами
          не ответивших: до встречи часы, и ему нужно с кем-то из них связаться. Waitlisted и
          отказавшиеся по-прежнему живут своими блоками (отказавшиеся — только счётчиком). */}
      {!isCancelled && finalComposition && (comingList.length > 0 || showStage2Tabs) && (
        <>
          <div className="rd-section-sub-h">
            {showStage2Tabs
              ? 'Состав'
              : <>Кто идёт <span className="rd-count">· {comingList.length}</span></>}
          </div>
          {showStage2Tabs && (
            <div className="rd-seg rd-seg-flush" style={{ marginBottom: 10 }}>
              <button
                type="button"
                className={`rd-seg-btn${stage2Tab === 'confirmed' ? ' rd-active' : ''}`}
                aria-pressed={stage2Tab === 'confirmed'}
                onClick={() => { haptic.impact('light'); setStage2Tab('confirmed'); setRosterExpanded(false); }}
              >
                Идут ({comingList.length})
              </button>
              <button
                type="button"
                className={`rd-seg-btn${stage2Tab === 'pending' ? ' rd-active' : ''}`}
                aria-pressed={stage2Tab === 'pending'}
                onClick={() => { haptic.impact('light'); setStage2Tab('pending'); setRosterExpanded(false); }}
              >
                Без ответа ({pendingCount})
              </button>
            </div>
          )}
          {showStage2Tabs && stage2Tab === 'pending' ? (
            <>
              <div className="rd-glass rd-pend-panel">
                {visibleStage2.length === 0 && (
                  <div className="rd-resp-empty">Все ответили — напоминать некому.</div>
                )}
                {visibleStage2.map((r) => {
                  const name = `${r.firstName}${r.lastName ? ` ${r.lastName[0]}.` : ''}`;
                  // Личного чата без username не существует: Telegram разрешает его не задавать,
                  // а открыть диалог по telegram_id из Mini App нельзя. Такая строка — не кнопка.
                  const username = telegramChatUsername(r.telegramUsername);
                  return (
                    <div className="rd-pend-row" key={r.userId}>
                      <button
                        type="button"
                        className="rd-pend-main"
                        disabled={!username}
                        aria-label={username ? `Написать ${name}` : undefined}
                        onClick={() => {
                          if (!username) return;
                          haptic.impact('light');
                          openTmeLink(`https://t.me/${username}`);
                        }}
                      >
                        <span className="rd-pend-av">
                          {r.avatarUrl ? <img src={r.avatarUrl} alt="" /> : getInitials(name)}
                        </span>
                        <span className="rd-pend-txt">
                          <span className="rd-pend-name">
                            {name}
                            <span className={`rd-vdot ${statusDotClass(r.status)}`} title={r.status} />
                          </span>
                          <span className={`rd-pend-met${r.remindedAt ? ' rd-done-met' : ''}`}>
                            {username ? `@${username}` : 'без username'}
                            {' · '}
                            {r.remindedAt
                              ? `напомнили в ${formatRemindedAt(r.remindedAt)}`
                              : (VOTE_LABELS[r.status] ?? r.status).toLowerCase()}
                          </span>
                        </span>
                        {username && <span className="rd-pend-chev" aria-hidden="true">›</span>}
                      </button>
                      {/* Отдельная цель нажатия: промах по строке не должен слать человеку DM. */}
                      <button
                        type="button"
                        className="rd-remind-btn"
                        disabled={!!r.remindedAt || remindMutation.isPending}
                        aria-label={r.remindedAt ? `Напоминание отправлено: ${name}` : `Напомнить ${name}`}
                        title={r.remindedAt ? 'Напоминание уже отправлено' : 'Напомнить'}
                        onClick={() => handleRemind(r.userId)}
                      >
                        {r.remindedAt ? CHECK_ICON : BELL_ICON}
                      </button>
                    </div>
                  );
                })}
                {stage2List.length > visibleStage2.length && (
                  <RosterMoreButton
                    total={stage2List.length}
                    onExpand={() => { haptic.impact('light'); setRosterExpanded(true); }}
                  />
                )}
                {/* Массовое напоминание считает только тех, кому ещё не напоминали: повторный
                    тап никому ничего не отправит, и счётчик это показывает заранее. */}
                {remindableCount > 0 && (
                  <button
                    type="button"
                    className="rd-remind-all"
                    disabled={remindMutation.isPending}
                    onClick={() => handleRemind(undefined)}
                  >
                    {remindMutation.isPending ? <Spinner size="s" /> : `🔔 Напомнить всем · ${remindableCount}`}
                  </button>
                )}
              </div>
              {remindError && <div className="rd-error">{remindError}</div>}
              <div className="rd-hint" style={{ marginBottom: 18 }}>
                Тап по имени открывает личный чат. Колокольчик отправляет напоминание от бота —
                по одному на участника.
              </div>
            </>
          ) : (
            <div className="rd-glass rd-resp-panel">
              {visibleStage2.length === 0 ? (
                <div className="rd-resp-empty">Пока никто не подтвердил участие.</div>
              ) : (
                <>
                  {visibleStage2.map((r) => {
                    const name = `${r.firstName}${r.lastName ? ` ${r.lastName[0]}.` : ''}`;
                    return (
                      <div className="rd-resp-row" key={r.userId}>
                        <div className="rd-voter">
                          <span className="rd-av">
                            {r.avatarUrl ? <img src={r.avatarUrl} alt="" /> : getInitials(name)}
                          </span>
                          <span className="rd-vn">{name}</span>
                          <span className={`rd-vdot ${statusDotClass(r.status)}`} title={r.status} />
                        </div>
                      </div>
                    );
                  })}
                  {stage2List.length > visibleStage2.length && (
                    <RosterMoreButton
                      total={stage2List.length}
                      onExpand={() => { haptic.impact('light'); setRosterExpanded(true); }}
                    />
                  )}
                </>
              )}
            </div>
          )}
        </>
      )}

      {/* W3-09: Этап 1, ни одного отклика — role-aware строка-намёк в слоте ростера, под
          голосованием. Без кнопок и без заголовка секции. Гейты: !isCancelled + showVoting +
          responders.isSuccess (см. showVoteRosterHint). */}
      {showVoteRosterHint && (
        <div className="rd-cta-hint" style={{ textAlign: 'left', marginBottom: 14 }}>
          {isManager
            ? 'Голосов пока нет. Поделись событием в чате клуба — первые отклики появятся здесь.'
            : 'Пока никто не откликнулся. Проголосуй первым — остальным будет проще решиться.'}
        </div>
      )}

      {/* Лист ожидания (Этап 2+): в порядке приоритета — освободится слот, войдёт первый в очереди. */}
      {!isCancelled && finalComposition && waitlist.length > 0 && (
        <>
          <div className="rd-section-sub-h">В очереди <span className="rd-count">· {waitlist.length}</span></div>
          <div className="rd-attn-hint">Если участник откажется, место получит первый в очереди.</div>
          <div className="rd-glass rd-wl-panel">
            {waitlist.map((r, i) => {
              const name = `${r.firstName}${r.lastName ? ` ${r.lastName[0]}.` : ''}`;
              return (
                <div className="rd-wl-row" key={r.userId}>
                  <span className="rd-wl-pos">{i + 1}</span>
                  <div className="rd-voter">
                    <span className="rd-av">
                      {r.avatarUrl ? <img src={r.avatarUrl} alt="" /> : getInitials(name)}
                    </span>
                    <span className="rd-vn">{name}</span>
                  </div>
                </div>
              );
            })}
          </div>
        </>
      )}

      {/* Явка — организатор отмечает пришедших после события */}
      {showAttendanceMarking && (
        <>
          <div className="rd-section-sub-h">Отметить посещаемость</div>
          {respondersQuery.isPending ? (
            // Состав питает attendanceCandidates; пока он грузится, «нет подтверждённых участников» —
            // ложный empty-state, из-за которого организатор может закрыть страницу → EXP-2 (F5-22).
            <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
              <div className="rd-spinner-row" style={{ padding: 0 }}><Spinner size="s" /></div>
            </div>
          ) : respondersQuery.error ? (
            <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
              <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>
                Не удалось загрузить список участников.
              </div>
              <button
                type="button"
                className="rd-btn-outline"
                style={{ marginTop: 10 }}
                onClick={() => { haptic.impact('light'); respondersQuery.refetch(); }}
              >
                Повторить
              </button>
            </div>
          ) : attendanceCandidates.length === 0 ? (
            <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
              <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>
                Отмечать некого — никто не подтвердил участие в этом событии.
              </div>
            </div>
          ) : (
            <>
              <div className="rd-glass" style={{ padding: 8, marginBottom: 10 }}>
                {attendanceCandidates.map((r) => {
                  const present = attended[r.userId] ?? true;
                  const name = `${r.firstName}${r.lastName ? ` ${r.lastName[0]}.` : ''}`;
                  return (
                    <div className="rd-pick-row" key={r.userId}>
                      <button
                        type="button"
                        className={`rd-pick-toggle${present ? ' rd-selected' : ''}`}
                        aria-pressed={present}
                        aria-label={`${name}: ${present ? 'пришёл' : 'не пришёл'}`}
                        onClick={() => toggleAttended(r.userId)}
                      >
                        <span className="rd-check-box">{present ? '✓' : ''}</span>
                        <span className="rd-pick-name">{name}</span>
                        <span className="rd-pick-note">{present ? 'пришёл' : 'не пришёл'}</span>
                      </button>
                    </div>
                  );
                })}
              </div>
              <div className="rd-hint" style={{ marginBottom: 10 }}>
                По умолчанию все отмечены как пришедшие — снимите галочку с тех, кто не пришёл.
              </div>
              {/* Открытая встреча целиком вне репутации (PO 2026-07-21) — оргу важно понимать,
                  что отметка ни на что не влияет, кроме истории посещений и статистики клуба. */}
              {isOpenEvent && (
                <div className="rd-hint" style={{ marginBottom: 10 }}>
                  Это открытая встреча: отметка нужна только для истории посещений и статистики
                  клуба — репутация участников не меняется ни в какую сторону.
                </div>
              )}
              {attendanceError && <div className="rd-error">{attendanceError}</div>}
              <div className="rd-cta-wrap">
                <button
                  type="button"
                  className="rd-btn-primary"
                  onClick={() => handleMarkAttendance(attendanceCandidates)}
                  disabled={markAttendanceMutation.isPending}
                >
                  {markAttendanceMutation.isPending ? <Spinner size="s" /> : 'Сохранить посещаемость'}
                </button>
              </div>
            </>
          )}
        </>
      )}

      {/* Явка — зафиксирована. Read-only сводка + (пока открыто окно спора) контролы
          организатора для разрешения оспоренных отметок (ATT-2/ATT-3). */}
      {showAttendanceDone && (
        <>
          <div className="rd-section-sub-h">Посещаемость</div>
          <div
            className="rd-glass"
            style={{ padding: '14px 16px', marginBottom: disputeWindowOpen && disputedCandidates.length > 0 ? 10 : 14 }}
          >
            <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>
              ✓ Посещаемость отмечена{event.attendanceFinalized ? ' и закреплена' : ''}.
            </div>
          </div>
          {/* Вход в split_bill. Один сплит на событие: активный — открываем, успешно закрытый —
              показываем («счёт уже собран»); иначе кнопка создаёт новый сплит. */}
          {(() => {
            const split = eventSplitQuery.data;
            const openExisting = () => {
              haptic.impact('medium');
              navigate(`/skladchina/${split!.skladchinaId}`);
            };
            if (split?.skladchinaId && split.status === 'active') {
              return (
                <button type="button" className="rd-btn-outline" style={{ marginBottom: 14 }} onClick={openExisting}>
                  🧾 Открыть сбор по счёту ›
                </button>
              );
            }
            if (split?.skladchinaId && split.status === 'closed_success') {
              return (
                <button type="button" className="rd-btn-outline" style={{ marginBottom: 14 }} onClick={openExisting}>
                  🧾 Счёт уже собран ›
                </button>
              );
            }
            return (
              <button
                type="button"
                className="rd-btn-outline"
                style={{ marginBottom: 14 }}
                onClick={() => {
                  haptic.impact('medium');
                  navigate(`/clubs/${event.clubId}/skladchina/split?eventId=${event.id}`);
                }}
              >
                🧾 Разделить счёт
              </button>
            );
          })()}
          {disputeWindowOpen && disputedCandidates.length > 0 && (
            <>
              <div className="rd-section-sub-h">Оспоренные отметки</div>
              {attendanceError && <div className="rd-error">{attendanceError}</div>}
              <div className="rd-glass rd-dispute-list">
                {disputedCandidates.map((r) => {
                  const name = `${r.firstName}${r.lastName ? ` ${r.lastName[0]}.` : ''}`;
                  return (
                    <div key={r.userId} className="rd-dispute-item">
                      <div className="rd-dispute-row">
                        <span className="rd-dispute-name">{name}</span>
                        <div className="rd-dispute-actions">
                          <button
                            type="button"
                            className="rd-resolve-btn rd-resolve-yes"
                            aria-label="Пришёл"
                            title="Пришёл"
                            onClick={() => handleResolve(r.userId, true)}
                            disabled={resolveMutation.isPending}
                          >
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                              <polyline points="20 6 9 17 4 12" />
                            </svg>
                          </button>
                          <button
                            type="button"
                            className="rd-resolve-btn rd-resolve-no"
                            aria-label="Не пришёл"
                            title="Не пришёл"
                            onClick={() => handleResolve(r.userId, false)}
                            disabled={resolveMutation.isPending}
                          >
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                              <line x1="18" y1="6" x2="6" y2="18" />
                              <line x1="6" y1="6" x2="18" y2="18" />
                            </svg>
                          </button>
                        </div>
                      </div>
                      {r.disputeNote && <div className="rd-dispute-note">«{r.disputeNote}»</div>}
                    </div>
                  );
                })}
              </div>
            </>
          )}
        </>
      )}

      {/* EXP-2: организатор так и не отметил, дедлайн прошёл → событие закрыто нейтрально (без репутации). */}
      {showAttendanceExpired && (
        <>
          <div className="rd-section-sub-h">Посещаемость</div>
          <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
            <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>
              Окно отметки явки истекло. Событие закрыто без отметки — репутация участникам
              за него не начислена.
            </div>
          </div>
        </>
      )}

      {/* Спор со стороны участника (ATT-3): виден отмеченному отсутствующим, пока окно открыто. */}
      {(canDispute || myDisputePending || myDisputeRejected) && (
        <>
          <div className="rd-section-sub-h">Ваша явка</div>
          <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: canDispute ? 10 : 14 }}>
            <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>
              {myDisputePending
                ? 'Вы оспорили отметку об отсутствии. Организатор примет решение до закрытия окна.'
                : myDisputeRejected
                  ? 'Организатор рассмотрел ваш спор — отметка «не пришёл» осталась.'
                  : 'Организатор отметил вас как отсутствующего. Если это ошибка — оспорьте, и организатор пересмотрит.'}
            </div>
          </div>
          {canDispute && (
            <>
              <textarea
                className="rd-textarea"
                style={{ width: '100%', marginBottom: 10, boxSizing: 'border-box' }}
                placeholder="Комментарий организатору (необязательно)"
                maxLength={500}
                value={disputeNote}
                onChange={(e) => setDisputeNote(e.target.value)}
              />
              {attendanceError && <div className="rd-error">{attendanceError}</div>}
              <div className="rd-cta-wrap">
                <button
                  type="button"
                  className="rd-btn-primary"
                  onClick={handleDispute}
                  disabled={disputeMutation.isPending}
                >
                  {disputeMutation.isPending ? <Spinner size="s" /> : 'Оспорить'}
                </button>
              </div>
            </>
          )}
        </>
      )}

      {/* Этап 2 — подтверждение участия */}
      {showStage2 && (
        <>
          <div className="rd-section-sub-h">Подтверждение участия</div>
          <div style={{ marginBottom: 10 }}>
            {myVote === 'confirmed' && <span className="rd-badge rd-going">Подтверждён</span>}
            {myVote === 'waitlisted' && <span className="rd-badge rd-warn">В очереди</span>}
            {myVote === 'declined' && <span className="rd-badge rd-decline">Отказался</span>}
            {myVote && !['confirmed', 'waitlisted', 'declined'].includes(myVote) && (
              <span className="rd-badge rd-warn">Ваш статус: {VOTE_LABELS[myVote] ?? myVote}</span>
            )}
          </div>
          {actionError && <div className="rd-error">{actionError}</div>}
          {/* Этап 2 открыт всем участникам клуба: «Подтвердить» показываем всем, кроме тех, кто уже
              в терминальном статусе Этапа 2 (подтверждён / лист ожидания / отказался). «Отказаться» —
              только голосовавшим going/maybe (им есть от чего отказываться); not_going и не
              голосовавшим показываем лишь путь внутрь. */}
          {myVote !== 'confirmed' && myVote !== 'waitlisted' && myVote !== 'declined' && (
            <div className="rd-cta-wrap">
              <button type="button" className="rd-btn-primary" onClick={handleConfirm} disabled={voting}>
                {voting ? <Spinner size="s" /> : 'Подтвердить участие'}
              </button>
              {(myVote === 'going' || myVote === 'maybe') && (
                <button type="button" className="rd-btn-outline" style={{ marginTop: 8 }} onClick={handleDecline} disabled={voting}>
                  Отказаться
                </button>
              )}
            </div>
          )}
          {/* Подтверждённый освобождает место — с инлайн-подтверждением (защита). Кнопки нет после
              дедлайна отказа (confirmedDeclineDeadline с бэка; бэк тоже отклонит). Если замены в очереди
              нет — предупреждаем про штраф репутации; если есть — что место сразу займёт первый из очереди. */}
          {confirmedCanDecline && (
            confirmingDecline ? (
              <div className="rd-reject-confirm">
                <div className="rd-reject-q">
                  {isOpenEvent
                    // Открытая встреча: мест нет — отказ свободный, штрафа и очереди не существует.
                    ? 'Отказаться от участия? Это открытая встреча — репутация не пострадает.'
                    : <>Освободить место?{' '}
                      {waitlistedCount > 0
                        ? 'Его сразу займёт первый из очереди.'
                        : `Замены пока нет — с вашей репутации спишется ${event.abandonedSlotPenaltyPoints} очков.`}</>}
                </div>
                <div className="rd-org-gate-acts">
                  <button type="button" className="rd-btn-outline" disabled={voting} onClick={() => setConfirmingDecline(false)}>
                    Нет
                  </button>
                  <button
                    type="button"
                    className="rd-btn-primary rd-btn-danger"
                    disabled={voting}
                    onClick={() => { setConfirmingDecline(false); handleDecline(); }}
                  >
                    {voting ? <Spinner size="s" /> : (isOpenEvent ? 'Отказаться' : 'Освободить')}
                  </button>
                </div>
              </div>
            ) : (
              <div className="rd-cta-wrap">
                <button type="button" className="rd-btn-outline" onClick={() => { setActionError(null); setConfirmingDecline(true); }}>
                  Отказаться
                </button>
              </div>
            )
          )}
          {/* Waitlisted выходит из очереди свободно (никого не держит, порога и штрафа нет). */}
          {myVote === 'waitlisted' && (
            <div className="rd-cta-wrap">
              <button type="button" className="rd-btn-outline" onClick={handleDecline} disabled={voting}>
                Отказаться
              </button>
            </div>
          )}
          {pathBackNudge}
        </>
      )}

      {/* Организаторские действия. Раскладка PO 2026-08-12: правка и отмена — одной строкой
          в равных долях, сохранение шаблона — строкой ниже, прижатое вправо и помельче.

          Условия у строк разные, поэтому они и остаются разными строками:
          - правка (только Этап 1, гейт зеркалит бэкенд-гард updateEvent) и отмена (F5-14)
            живут до старта встречи;
          - сохранение шаблона доступно и у ПРОШЕДШЕЙ встречи — это основной случай: клуб
            провёл серию одинаковых встреч и заводит из последней заготовку на следующие. */}
      {isManager && !isCancelled && (
        <div className="rd-ev-actions" style={{ marginTop: 8 }}>
          {!eventHappened && (
            <div className="rd-ev-actions-row">
              {showVoting && (
                <button type="button" className="rd-btn-outline" onClick={openEdit}>
                  Редактировать
                </button>
              )}
              <button
                type="button"
                className="rd-btn-outline rd-ev-danger"
                onClick={() => { haptic.impact('medium'); setCancelError(null); setCancelOpen(true); }}
              >
                Отменить
              </button>
            </div>
          )}
          <button
            type="button"
            className="rd-btn-outline rd-ev-tpl-btn"
            onClick={openSaveTemplate}
          >
            {templateSaved ? '📋 Шаблон сохранён' : '📋 Сохранить как шаблон'}
          </button>
        </div>
      )}

      {editOpen && createPortal(
        <>
          <div className="rd-sheet-overlay" onClick={() => setEditOpen(false)} aria-hidden="true" />
          <div className="rd-sheet" role="dialog" aria-modal="true" aria-label="Редактирование встречи">
            <div className="rd-sheet-grabber" aria-hidden="true" />
            <div className="rd-sheet-head">
              <h2>Редактировать встречу</h2>
              <button type="button" className="rd-sheet-close" onClick={() => setEditOpen(false)}>Закрыть</button>
            </div>
            <div className="rd-sheet-body">
              <div className="rd-body-text" style={{ marginTop: 0 }}>
                Участники получат уведомление, только если поменяется место или время. Правки
                возможны до начала подтверждения мест.
              </div>

              <label className="rd-field">
                <span className="rd-label">Название</span>
                <input
                  className="rd-input"
                  type="text"
                  maxLength={255}
                  value={editTitle}
                  onChange={(e) => setEditTitle(e.target.value)}
                />
              </label>

              <label className="rd-field">
                <span className="rd-label">Описание</span>
                <textarea
                  className="rd-input"
                  rows={3}
                  value={editDescription}
                  onChange={(e) => setEditDescription(e.target.value)}
                />
              </label>

              <div className="rd-field">
                <span className="rd-label">Место</span>
                <button
                  type="button"
                  className="rd-btn-outline"
                  onClick={() => { haptic.impact('light'); setEditPickerOpen(true); }}
                >
                  {editLocation.text ?? (editLocation.lat != null ? 'Точка на карте' : 'Выбрать на карте')}
                </button>
              </div>

              <label className="rd-field">
                <span className="rd-label">Уточнение к месту</span>
                <input
                  className="rd-input"
                  type="text"
                  maxLength={200}
                  placeholder="Вход со двора, домофон 12"
                  value={editHint}
                  onChange={(e) => setEditHint(e.target.value)}
                />
              </label>

              <label className="rd-field">
                <span className="rd-label">Дата и время</span>
                <div className="rd-datetime">
                  <input
                    className="rd-input"
                    type="datetime-local"
                    value={editDatetime}
                    onChange={(e) => setEditDatetime(e.target.value)}
                  />
                </div>
              </label>

              {/* У открытой встречи лимита нет вовсе — формат неизменяем, поле не показываем. */}
              {!isOpenEvent && (
                <label className="rd-field">
                  <span className="rd-label">Лимит участников</span>
                  <input
                    className="rd-input"
                    type="number"
                    min={1}
                    value={editLimit}
                    onChange={(e) => setEditLimit(e.target.value)}
                  />
                </label>
              )}

              {editStage2Immediate && editLeadLabel && (
                <div className="rd-body-text">
                  ⚡️ До встречи меньше интервала подтверждения (за {editLeadLabel}) —
                  подтверждение мест начнётся сразу после сохранения.
                </div>
              )}
              {editError && <div className="rd-error">{editError}</div>}
              <div className="rd-cta-wrap">
                <button
                  type="button"
                  className="rd-btn-primary"
                  onClick={handleUpdateEvent}
                  disabled={updateMutation.isPending}
                >
                  {updateMutation.isPending ? <Spinner size="s" /> : 'Сохранить'}
                </button>
                <button type="button" className="rd-btn-outline" style={{ marginTop: 8 }} onClick={() => setEditOpen(false)}>
                  Назад
                </button>
              </div>
            </div>
          </div>
        </>,
        document.body,
      )}

      {editPickerOpen && (
        <LocationPickerSheet
          clubId={event.clubId}
          initial={
            editLocation.lat != null && editLocation.lon != null
              ? { lat: editLocation.lat, lon: editLocation.lon }
              : null
          }
          onSelect={(point, address) => {
            setEditLocation({ text: address || null, lat: point.lat, lon: point.lon });
            setEditPickerOpen(false);
          }}
          onClose={() => setEditPickerOpen(false)}
        />
      )}

      {cancelOpen && createPortal(
        <>
          <div className="rd-sheet-overlay" onClick={() => setCancelOpen(false)} aria-hidden="true" />
          <div className="rd-sheet" role="dialog" aria-modal="true" aria-label="Отмена события">
            <div className="rd-sheet-grabber" aria-hidden="true" />
            <div className="rd-sheet-head">
              <h2>Отменить событие?</h2>
              <button type="button" className="rd-sheet-close" onClick={() => setCancelOpen(false)}>Закрыть</button>
            </div>
            <div className="rd-sheet-body">
              <div className="rd-body-text" style={{ marginTop: 0 }}>
                Участники получат уведомление об отмене. Действие необратимо.
              </div>
              <textarea
                className="rd-textarea"
                style={{ width: '100%', marginBottom: 10, boxSizing: 'border-box' }}
                placeholder="Причина отмены (необязательно)"
                maxLength={500}
                value={cancelReason}
                onChange={(e) => setCancelReason(e.target.value)}
              />
              {cancelError && <div className="rd-error">{cancelError}</div>}
              <div className="rd-cta-wrap">
                <button
                  type="button"
                  className="rd-btn-primary"
                  style={{ background: 'var(--danger)' }}
                  onClick={handleCancelEvent}
                  disabled={cancelMutation.isPending}
                >
                  {cancelMutation.isPending ? <Spinner size="s" /> : 'Отменить событие'}
                </button>
                <button type="button" className="rd-btn-outline" style={{ marginTop: 8 }} onClick={() => setCancelOpen(false)}>
                  Назад
                </button>
              </div>
            </div>
          </div>
        </>,
        document.body,
      )}

      {templateOpen && createPortal(
        <>
          <div className="rd-sheet-overlay" onClick={() => setTemplateOpen(false)} aria-hidden="true" />
          <div className="rd-sheet" role="dialog" aria-modal="true" aria-label="Сохранение шаблона">
            <div className="rd-sheet-grabber" aria-hidden="true" />
            <div className="rd-sheet-head">
              <h2>Сохранить как шаблон</h2>
              <button type="button" className="rd-sheet-close" onClick={() => setTemplateOpen(false)}>Закрыть</button>
            </div>
            <div className="rd-sheet-body">
              <div className="rd-body-text" style={{ marginTop: 0 }}>
                Шаблон запомнит всё, кроме даты: место, описание, фото, лимит и формат. Вместо
                даты — день недели и время, чтобы подставлять ближайшую подходящую.
              </div>
              <input
                className="rd-input"
                type="text"
                style={{ width: '100%', marginBottom: 10, boxSizing: 'border-box' }}
                placeholder="Имя шаблона"
                maxLength={TEMPLATE_NAME_MAX}
                value={templateName}
                onChange={(e) => setTemplateName(e.target.value)}
              />
              {templateError && <div className="rd-error">{templateError}</div>}
              <div className="rd-cta-wrap">
                <button
                  type="button"
                  className="rd-btn-primary"
                  onClick={handleSaveTemplate}
                  disabled={saveTemplateMutation.isPending}
                >
                  {saveTemplateMutation.isPending ? <Spinner size="s" /> : 'Сохранить шаблон'}
                </button>
                <button
                  type="button"
                  className="rd-btn-outline"
                  style={{ marginTop: 8 }}
                  onClick={() => setTemplateOpen(false)}
                >
                  Назад
                </button>
              </div>
            </div>
          </div>
        </>,
        document.body,
      )}

      {/* Подтверждённые участники */}
      {toastMessage && <Toast message={toastMessage} onClose={() => setToastMessage(null)} />}
    </div>
  );
};

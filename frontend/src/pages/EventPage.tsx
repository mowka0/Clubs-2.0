import { FC, useEffect, useState } from 'react';
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
import { formatLeadInterval } from '../utils/formatters';
import { useEventSplitStateQuery } from '../queries/skladchina';
import { useSetClubContext } from '../store/useClubContextStore';
import { Toast } from '../components/Toast';
import { EventPlaceCard } from '../components/event/EventPlaceCard';
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
  useRescheduleEventMutation,
  useResolveDisputeMutation,
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

// Русские подписи статусов голоса/участия — для бейджей и строки «Ваш голос».
const VOTE_LABELS: Record<string, string> = {
  going: 'Пойду',
  maybe: 'Возможно',
  not_going: 'Не пойду',
  confirmed: 'Подтверждён',
  waitlisted: 'Лист ожидания',
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

/**
 * ISO (UTC) → значение для input[type=datetime-local] в ЛОКАЛЬНОМ поясе устройства
 * («YYYY-MM-DDTHH:mm»). toISOString() не подходит: он вернул бы UTC-время, и пикер
 * показал бы организатору сдвинутые часы.
 */
function toDatetimeLocalValue(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
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
  const cancelMutation = useCancelEventMutation();
  const rescheduleMutation = useRescheduleEventMutation();

  // Два отдельных канала ошибок: actionError — для голоса/подтверждения/отказа, attendanceError —
  // для отметки явки. actionError рендерится ровно в одном слоте на фазу — блок голосования Этапа 1
  // (за гейтом showVoting) ЛИБО блок подтверждения Этапа 2 — никогда оба сразу (F5-23).
  // Каждый обработчик сбрасывает свой канал перед запуском, так что каналы тоже не сталкиваются.
  const [actionError, setActionError] = useState<string | null>(null);
  const [attendanceError, setAttendanceError] = useState<string | null>(null);
  // Только явные переопределения; отсутствие записи означает «пришёл» (attended[id] ?? true).
  const [attended, setAttended] = useState<Record<string, boolean>>({});
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  // Необязательный комментарий, который участник прикладывает, оспаривая отметку «не пришёл».
  const [disputeNote, setDisputeNote] = useState('');
  // F5-14 шторка отмены события: флаг открытия, необязательная причина и собственный слот ошибки.
  const [cancelOpen, setCancelOpen] = useState(false);
  const [cancelReason, setCancelReason] = useState('');
  const [cancelError, setCancelError] = useState<string | null>(null);
  // Шторка переноса даты (только Этап 1): значение datetime-local и собственный слот ошибки.
  const [rescheduleOpen, setRescheduleOpen] = useState(false);
  const [rescheduleValue, setRescheduleValue] = useState('');
  const [rescheduleError, setRescheduleError] = useState<string | null>(null);
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

  // Перенос даты (только Этап 1). Открытие шторки предзаполняет пикер текущей датой события.
  const openReschedule = () => {
    if (!event) return;
    haptic.impact('medium');
    setRescheduleError(null);
    setRescheduleValue(toDatetimeLocalValue(event.eventDatetime));
    setRescheduleOpen(true);
  };

  const handleRescheduleEvent = () => {
    if (!id || !event || rescheduleMutation.isPending) return;
    setRescheduleError(null);
    if (!rescheduleValue) { setRescheduleError('Укажите дату и время'); haptic.notify('error'); return; }
    const newDate = new Date(rescheduleValue);
    if (Number.isNaN(newDate.getTime())) { setRescheduleError('Некорректная дата'); haptic.notify('error'); return; }
    if (newDate.getTime() <= Date.now()) { setRescheduleError('Дата события должна быть в будущем'); haptic.notify('error'); return; }
    haptic.impact('medium');
    rescheduleMutation.mutate(
      { eventId: id, clubId: event.clubId, eventDatetime: newDate.toISOString() },
      {
        onSuccess: () => {
          haptic.notify('success');
          setRescheduleOpen(false);
          setToastMessage('Встреча перенесена');
        },
        onError: (e) => {
          setRescheduleError(e.message);
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

  // Пончик-диаграмма: Этап 1 = раскладка голосов (going / maybe / not going); Этап 2+ = занято vs свободно.
  const donutStyle: { background: string } = (() => {
    if (finalComposition) {
      // Открытая встреча: понятия «занято/свободно» нет — кольцо целиком закрашено составом,
      // а при нуле подтвердивших — нейтральное (как stage-1 без голосов), не «заполненное».
      if (isOpenEvent) {
        return event.confirmedCount > 0
          ? { background: 'conic-gradient(var(--vote-go) 0 360deg)' }
          : { background: 'var(--surface-2)' };
      }
      const limit = event.participantLimit || 1;
      const filled = Math.min(event.confirmedCount, limit);
      const c = (filled / limit) * 360;
      return { background: `conic-gradient(var(--vote-go) 0 ${c}deg, var(--vote-no) ${c}deg 360deg)` };
    }
    const voteTotal = event.goingCount + event.maybeCount + event.notGoingCount;
    if (voteTotal === 0) return { background: 'var(--surface-2)' };
    const g = (event.goingCount / voteTotal) * 360;
    const m = (event.maybeCount / voteTotal) * 360;
    return { background: `conic-gradient(var(--vote-go) 0 ${g}deg, var(--vote-maybe) ${g}deg ${g + m}deg, var(--vote-no) ${g + m}deg 360deg)` };
  })();

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
  const rescheduleTimeMs = rescheduleValue ? new Date(rescheduleValue).getTime() : null;
  const rescheduleStage2Immediate =
    event.stage2LeadMinutes != null &&
    rescheduleTimeMs !== null && !Number.isNaN(rescheduleTimeMs) &&
    rescheduleTimeMs > Date.now() &&
    rescheduleTimeMs - Date.now() <= event.stage2LeadMinutes * 60_000;
  const rescheduleLeadLabel =
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
  // Менеджер клуба события: владелец ИЛИ активный со-организатор (fail-close — роль со-орга
  // действует только при активном членстве, зеркалит серверный гейт AttendanceService/EventService).
  const myHostMembership = myClubsQuery.data?.find((m) => m.clubId === event.clubId);
  const isManager =
    (!!hostClubQuery.data && hostClubQuery.data.ownerId === userId)
    || isActiveManagerMembership(myHostMembership);
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
  const pendingCount = responders.filter((r) => r.status === 'going' || r.status === 'maybe').length;
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
          <div className="rd-hero-type-badge">{isOpenEvent ? 'ОТКРЫТАЯ ВСТРЕЧА' : 'СОБЫТИЕ'}</div>
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
                Пойду <span className="rd-vc">{event.goingCount}</span>
              </button>
              <button type="button" className={`rd-vote-btn rd-vb-maybe${myVote === 'maybe' ? ' rd-active' : ''}`} onClick={() => handleVote('maybe')} disabled={voting}>
                Возможно <span className="rd-vc">{event.maybeCount}</span>
              </button>
              <button type="button" className={`rd-vote-btn rd-vb-no${myVote === 'not_going' ? ' rd-active' : ''}`} onClick={() => handleVote('not_going')} disabled={voting}>
                Не пойду <span className="rd-vc">{event.notGoingCount}</span>
              </button>
            </>
          ) : finalComposition ? (
            <div className="rd-glass" style={{ padding: '4px 4px' }}>
              <div className="rd-kv">Подтвердили <span className="rd-v">{event.confirmedCount}</span></div>
              {pendingCount > 0 && <div className="rd-kv">Ждут подтверждения <span className="rd-v">{pendingCount}</span></div>}
              {waitlistedCount > 0 && <div className="rd-kv">Лист ожидания <span className="rd-v">{waitlistedCount}</span></div>}
            </div>
          ) : (
            <div className="rd-glass" style={{ padding: '4px 4px' }}>
              <div className="rd-kv">Пойдут <span className="rd-v">{event.goingCount}</span></div>
              <div className="rd-kv">Возможно <span className="rd-v">{event.maybeCount}</span></div>
              <div className="rd-kv">Не пойдут <span className="rd-v">{event.notGoingCount}</span></div>
            </div>
          )}
        </div>
        <div className="rd-donut" style={donutStyle} aria-hidden="true">
          <div className="rd-donut-center">
            <span className="rd-donut-num">
              <sup>{finalComposition ? event.confirmedCount : event.goingCount}</sup>
              {/* Открытая встреча: знаменателя нет — только счёт. */}
              {!isOpenEvent && <><span className="rd-sl">/</span><sub>{event.participantLimit}</sub></>}
            </span>
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

      {/* Этап 1: предварительные голоса (интерес, мест не резервируют). Этап 2+: подтверждённый состав. */}
      {!isCancelled && comingList.length > 0 && (
        <>
          <div className="rd-section-sub-h">{finalComposition ? 'Кто идёт' : 'Предварительные голоса'} <span className="rd-count">· {comingList.length}</span></div>
          <div className="rd-voters">
            {comingList.map((r) => {
              const name = `${r.firstName}${r.lastName ? ` ${r.lastName[0]}.` : ''}`;
              return (
                <div className="rd-voter" key={r.userId}>
                  <span className="rd-av">
                    {r.avatarUrl ? <img src={r.avatarUrl} alt="" /> : getInitials(name)}
                  </span>
                  <span className="rd-vn">{name}</span>
                  <span className={`rd-vdot ${statusDotClass(r.status)}`} title={r.status} />
                </div>
              );
            })}
          </div>
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
          <div className="rd-section-sub-h">Лист ожидания <span className="rd-count">· {waitlist.length}</span></div>
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
            {myVote === 'waitlisted' && <span className="rd-badge rd-warn">Лист ожидания</span>}
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

      {/* Организаторские действия до старта: перенос даты — только на Этапе 1 (с началом
          подтверждения мест редактирование запрещено, гейт зеркалит бэкенд-гард
          rescheduleEvent) — и отмена события (F5-14). */}
      {isManager && !isCancelled && !eventHappened && (
        <div className="rd-cta-wrap" style={{ marginTop: 8 }}>
          {showVoting && (
            <button
              type="button"
              className="rd-btn-outline"
              style={{ marginBottom: 8 }}
              onClick={openReschedule}
            >
              Перенести встречу
            </button>
          )}
          <button
            type="button"
            className="rd-btn-outline"
            style={{ color: 'var(--danger)', borderColor: 'var(--danger)' }}
            onClick={() => { haptic.impact('medium'); setCancelError(null); setCancelOpen(true); }}
          >
            Отменить событие
          </button>
        </div>
      )}

      {rescheduleOpen && createPortal(
        <>
          <div className="rd-sheet-overlay" onClick={() => setRescheduleOpen(false)} aria-hidden="true" />
          <div className="rd-sheet" role="dialog" aria-modal="true" aria-label="Перенос встречи">
            <div className="rd-sheet-grabber" aria-hidden="true" />
            <div className="rd-sheet-head">
              <h2>Перенести встречу</h2>
              <button type="button" className="rd-sheet-close" onClick={() => setRescheduleOpen(false)}>Закрыть</button>
            </div>
            <div className="rd-sheet-body">
              <div className="rd-body-text" style={{ marginTop: 0 }}>
                Участники получат уведомление о новой дате. Перенос возможен только до начала
                подтверждения мест.
              </div>
              <label className="rd-field">
                <span className="rd-label">Новая дата и время</span>
                <div className="rd-datetime">
                  <input
                    className="rd-input"
                    type="datetime-local"
                    value={rescheduleValue}
                    onChange={(e) => setRescheduleValue(e.target.value)}
                  />
                </div>
              </label>
              {rescheduleStage2Immediate && rescheduleLeadLabel && (
                <div className="rd-body-text">
                  ⚡️ До встречи меньше интервала подтверждения (за {rescheduleLeadLabel}) —
                  подтверждение мест начнётся сразу после переноса.
                </div>
              )}
              {rescheduleError && <div className="rd-error">{rescheduleError}</div>}
              <div className="rd-cta-wrap">
                <button
                  type="button"
                  className="rd-btn-primary"
                  onClick={handleRescheduleEvent}
                  disabled={rescheduleMutation.isPending}
                >
                  {rescheduleMutation.isPending ? <Spinner size="s" /> : 'Перенести'}
                </button>
                <button type="button" className="rd-btn-outline" style={{ marginTop: 8 }} onClick={() => setRescheduleOpen(false)}>
                  Назад
                </button>
              </div>
            </div>
          </div>
        </>,
        document.body,
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

      {/* Подтверждённые участники */}
      {toastMessage && <Toast message={toastMessage} onClose={() => setToastMessage(null)} />}
    </div>
  );
};

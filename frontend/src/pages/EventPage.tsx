import { FC, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useAuthStore } from '../store/useAuthStore';
import { useClubQuery } from '../queries/clubs';
import { useSetClubContext } from '../store/useClubContextStore';
import { Toast } from '../components/Toast';
import {
  useCastVoteMutation,
  useConfirmParticipationMutation,
  useDeclineParticipationMutation,
  useDisputeAttendanceMutation,
  useEventQuery,
  useEventRespondersQuery,
  useMarkAttendanceMutation,
  useMyVoteQuery,
  useResolveDisputeMutation,
} from '../queries/events';

function getInitials(name: string): string {
  return name.replace(/[«»"']/g, '').split(/\s+/).filter(Boolean).slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase()).join('');
}

/** Maps a responder status to its dot color class (go / maybe / expired / no). */
function statusDotClass(status: string): string {
  if (status === 'going' || status === 'confirmed') return 'rd-d-go';
  if (status === 'maybe' || status === 'waitlisted') return 'rd-d-maybe';
  if (status === 'expired_no_confirm') return 'rd-d-expired';
  return 'rd-d-no';
}

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

export const EventPage: FC = () => {
  useBackButton(true);

  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const haptic = useHaptic();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const userId = useAuthStore((s) => s.user?.id);

  const eventQuery = useEventQuery(isAuthenticated ? id : undefined);
  const myVoteQuery = useMyVoteQuery(isAuthenticated ? id : undefined);
  const hostClubQuery = useClubQuery(eventQuery.data?.clubId);
  const respondersQuery = useEventRespondersQuery(isAuthenticated ? id : undefined);
  useSetClubContext(eventQuery.data?.clubId);

  const castVoteMutation = useCastVoteMutation();
  const confirmMutation = useConfirmParticipationMutation();
  const declineMutation = useDeclineParticipationMutation();
  const markAttendanceMutation = useMarkAttendanceMutation();
  const disputeMutation = useDisputeAttendanceMutation();
  const resolveMutation = useResolveDisputeMutation();

  // Two separate error channels: actionError for vote/confirm/decline (rendered in
  // the recruitment + Stage 2 sections), attendanceError for attendance marking.
  // Each handler resets its own before firing, so they never collide in one slot.
  const [actionError, setActionError] = useState<string | null>(null);
  const [attendanceError, setAttendanceError] = useState<string | null>(null);
  // Explicit overrides only; an absent entry means "present" (attended[id] ?? true).
  const [attended, setAttended] = useState<Record<string, boolean>>({});
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  // Optional free-text note the participant attaches when disputing being marked absent.
  const [disputeNote, setDisputeNote] = useState('');

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
    // Default is "пришёл": absentees are the minority, so the organizer unticks only those
    // who did not show up (decision 2026-06-11, rev. 2).
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

  // ATT-3: a participant marked absent contests the mark (absent → disputed). Reachable only
  // while the dispute window is open (marked && !finalized) — see the gating below.
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

  // Organizer resolves a disputed mark into attended/absent before the window closes.
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

  // Phase split: Stage 1 (upcoming) gauges interest (going/maybe vote split); from Stage 2 on
  // the roster is the confirmed list, so the headline/donut/counts switch to confirmations.
  // Resolves the "decliner still counted as going" bug — declined/expired drop out of "идут".
  const finalComposition = event.status === 'stage_2' || event.status === 'completed';

  // Donut chart: Stage 1 = vote split (going / maybe / not going); Stage 2+ = confirmed vs free.
  const donutStyle: { background: string } = (() => {
    if (finalComposition) {
      const limit = event.participantLimit || 1;
      const filled = Math.min(event.confirmedCount, limit);
      const c = (filled / limit) * 360;
      return { background: `conic-gradient(#F47B3C 0 ${c}deg, #6B6B70 ${c}deg 360deg)` };
    }
    const voteTotal = event.goingCount + event.maybeCount + event.notGoingCount;
    if (voteTotal === 0) return { background: 'var(--surface-2)' };
    const g = (event.goingCount / voteTotal) * 360;
    const m = (event.maybeCount / voteTotal) * 360;
    return { background: `conic-gradient(#F47B3C 0 ${g}deg, #8E5DFF ${g}deg ${g + m}deg, #6B6B70 ${g + m}deg 360deg)` };
  })();

  const eventHappened = new Date(event.eventDatetime).getTime() <= Date.now();

  // Backend (`VoteService.castVote`) принимает голос ТОЛЬКО при status='upcoming'.
  const showVoting = event.status === 'upcoming';
  // Bug B: confirm/decline close at event start. Status stays 'stage_2' until the
  // hourly completion sweep, so gate on !eventHappened too — mirrors the backend
  // `event_datetime > now` guard in Stage2Service. See events.md.
  const showStage2 = event.status === 'stage_2' && !eventHappened;

  // Attendance marking — organizer only, once the event has taken place. Backend
  // (AttendanceService) gates on event_datetime <= now + the attendance_marked
  // flag, never on status (see events.md § attendance flow). Candidates = the FINAL
  // roster: only Stage-2-confirmed members (PRD §4.4.3 "список финальных участников,
  // кто подтвердил на Этапе 2"). Going/maybe voters who never confirmed ("забыли
  // подтвердить" → expired_no_confirm) are NOT on the roster — they're excluded here
  // and reputation ignores them (it reads only final_status=confirmed).
  const isOrganizer = !!hostClubQuery.data && hostClubQuery.data.ownerId === userId;
  const attendanceCandidates = (respondersQuery.data ?? []).filter(
    (r) => r.status === 'confirmed',
  );
  // Dispute window = attendance marked but not yet finalized (the 48h before reputation locks).
  const disputeWindowOpen = event.attendanceMarked && !event.attendanceFinalized;
  // EXP-2: a neutrally auto-finalized event is finalized but never marked. The marking UI must
  // hide in that state (backend rejects a late mark with "Attendance has been finalized").
  const showAttendanceMarking =
    isOrganizer && eventHappened && !event.attendanceMarked && !event.attendanceFinalized;
  const showAttendanceDone = isOrganizer && eventHappened && event.attendanceMarked;
  const showAttendanceExpired =
    isOrganizer && eventHappened && !event.attendanceMarked && event.attendanceFinalized;
  // Organizer's resolve list: confirmed participants whose mark is currently disputed.
  const disputedCandidates = attendanceCandidates.filter((r) => r.attendance === 'disputed');
  // Current user's own attendance, for the participant-facing dispute controls.
  const myResponder = (respondersQuery.data ?? []).find((r) => r.userId === userId);
  const canDispute = disputeWindowOpen && myResponder?.attendance === 'absent';
  const myDisputePending = disputeWindowOpen && myResponder?.attendance === 'disputed';

  // Phase-aware "who's coming". Stage 1: every responder (interest list, unchanged). Stage 2+:
  // only the confirmed roster — pending (still going/maybe, not confirmed), waitlisted, declined
  // and expired are summarized as counts, not shown as "идут".
  const responders = respondersQuery.data ?? [];
  const pendingCount = responders.filter((r) => r.status === 'going' || r.status === 'maybe').length;
  const waitlistedCount = responders.filter((r) => r.status === 'waitlisted').length;
  const comingList = finalComposition ? responders.filter((r) => r.status === 'confirmed') : responders;

  return (
    <div className="rd-page">
      {/* Hero — club avatar as backdrop (same as club page) */}
      <div className="rd-hero rd-compact">
        <div
          className="rd-hero-bg"
          data-cat={hostClubQuery.data?.category ?? 'sport'}
          style={hostClubQuery.data?.avatarUrl ? { backgroundImage: `url(${hostClubQuery.data.avatarUrl})` } : undefined}
        />
        <div className="rd-hero-meta">
          <div className="rd-hero-type-badge">СОБЫТИЕ</div>
          <div className="rd-hero-ttl">{event.title}</div>
          <div className="rd-hero-eyebrow" style={{ marginTop: 6 }}>
            {formatEventDate(event.eventDatetime)}
          </div>
        </div>
      </div>

      {/* Host club */}
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

      {/* Location */}
      {event.locationText && (
        <div className="rd-glass" style={{ marginBottom: 14, overflow: 'hidden' }}>
          <div className="rd-mini-map" />
          <div className="rd-addr-body">
            <div className="rd-a-ttl">{event.locationText}</div>
          </div>
        </div>
      )}

      {/* Description */}
      {event.description && (
        <>
          <div className="rd-section-sub-h">Описание</div>
          <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
            <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{event.description}</div>
          </div>
        </>
      )}

      {/* Recruitment (Stage 1) / roster (Stage 2+) — donut + voting or read-only counts */}
      <div className="rd-section-sub-h">
        {finalComposition
          ? `Состав · ${event.confirmedCount} / ${event.participantLimit}`
          : `Набор · ${event.goingCount} / ${event.participantLimit}`}
      </div>
      {actionError && <div className="rd-error">{actionError}</div>}
      <div className="rd-vote-layout">
        <div className="rd-vote-stack">
          {showVoting ? (
            <>
              <button type="button" className={`rd-vote-btn${myVote === 'going' ? ' rd-active' : ''}`} onClick={() => handleVote('going')} disabled={voting}>
                Пойду <span className="rd-vc">{event.goingCount}</span>
              </button>
              <button type="button" className={`rd-vote-btn${myVote === 'maybe' ? ' rd-active' : ''}`} onClick={() => handleVote('maybe')} disabled={voting}>
                Возможно <span className="rd-vc">{event.maybeCount}</span>
              </button>
              <button type="button" className={`rd-vote-btn${myVote === 'not_going' ? ' rd-active' : ''}`} onClick={() => handleVote('not_going')} disabled={voting}>
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
              <sup>{finalComposition ? event.confirmedCount : event.goingCount}</sup><span className="rd-sl">/</span><sub>{event.participantLimit}</sub>
            </span>
          </div>
        </div>
      </div>
      {showVoting && myVote && (
        <div style={{ marginBottom: 14 }}>
          <span className="rd-badge rd-going">Ваш голос: {VOTE_LABELS[myVote] ?? myVote}</span>
        </div>
      )}

      {/* Who's coming. Stage 1: all responders (interest). Stage 2+: confirmed roster only. */}
      {comingList.length > 0 && (
        <>
          <div className="rd-section-sub-h">Кто идёт <span className="rd-count">· {comingList.length}</span></div>
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

      {/* Attendance — organizer marks who showed up, after the event */}
      {showAttendanceMarking && (
        <>
          <div className="rd-section-sub-h">Отметить посещаемость</div>
          {attendanceCandidates.length === 0 ? (
            <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
              <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>
                Нет подтверждённых участников для отметки.
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

      {/* Attendance — recorded. Read-only summary + (while the dispute window is open) the
          organizer's resolve controls for any contested marks (ATT-2/ATT-3). */}
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
          {disputeWindowOpen && disputedCandidates.length > 0 && (
            <>
              <div className="rd-section-sub-h">Оспоренные отметки</div>
              {attendanceError && <div className="rd-error">{attendanceError}</div>}
              <div className="rd-glass" style={{ padding: 8, marginBottom: 14, display: 'flex', flexDirection: 'column', gap: 8 }}>
                {disputedCandidates.map((r) => {
                  const name = `${r.firstName}${r.lastName ? ` ${r.lastName[0]}.` : ''}`;
                  return (
                    <div key={r.userId} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                      <div className="rd-pick-row">
                        <span
                          className="rd-pick-name"
                          style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                        >
                          {name}
                        </span>
                        <button
                          type="button"
                          className="rd-btn-outline"
                          style={{ width: 'auto', padding: '8px 12px' }}
                          onClick={() => handleResolve(r.userId, true)}
                          disabled={resolveMutation.isPending}
                        >
                          Пришёл
                        </button>
                        <button
                          type="button"
                          className="rd-btn-outline"
                          style={{ width: 'auto', padding: '8px 12px' }}
                          onClick={() => handleResolve(r.userId, false)}
                          disabled={resolveMutation.isPending}
                        >
                          Не пришёл
                        </button>
                      </div>
                      {r.disputeNote && (
                        <div className="rd-body-text" style={{ margin: 0, padding: '0 2px', fontSize: 13, color: 'var(--text-dim)' }}>
                          «{r.disputeNote}»
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </>
          )}
        </>
      )}

      {/* EXP-2: organizer never marked, deadline passed → event closed neutrally (no reputation). */}
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

      {/* Participant-facing dispute (ATT-3): shown to whoever was marked absent, while the window is open. */}
      {(canDispute || myDisputePending) && (
        <>
          <div className="rd-section-sub-h">Ваша явка</div>
          <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: canDispute ? 10 : 14 }}>
            <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>
              {myDisputePending
                ? 'Вы оспорили отметку об отсутствии. Организатор примет решение до закрытия окна.'
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

      {/* Stage 2 — confirmation */}
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
          {(myVote === 'going' || myVote === 'maybe') && (
            <div className="rd-cta-wrap">
              <button type="button" className="rd-btn-primary" onClick={handleConfirm} disabled={voting}>
                {voting ? <Spinner size="s" /> : 'Подтвердить участие'}
              </button>
              <button type="button" className="rd-btn-outline" style={{ marginTop: 8 }} onClick={handleDecline} disabled={voting}>
                Отказаться
              </button>
            </div>
          )}
        </>
      )}

      {/* Confirmed participants */}
      {toastMessage && <Toast message={toastMessage} onClose={() => setToastMessage(null)} />}
    </div>
  );
};

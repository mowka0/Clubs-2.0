import { FC, useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import {
  useApproveApplicationMutation,
  useRejectApplicationMutation,
} from '../../queries/applications';
import { formatPeerSignal } from '../../features/applications-inbox/lib/peer-signal-format';
import { countryNameByCode } from '../CityPicker';
import type { PendingApplicationDto } from '../../types/api';

const REASON_MIN = 5;
const REASON_MAX = 500;

interface ApplicationReviewModalProps {
  application: PendingApplicationDto;
  open: boolean;
  onClose: () => void;
}

function getInitials(firstName: string, lastName: string | null): string {
  const a = firstName.charAt(0).toUpperCase();
  const b = lastName?.charAt(0).toUpperCase() ?? '';
  return `${a}${b}` || '·';
}

function formatHoursHint(hours: number): { label: string; urgent: boolean } {
  if (hours <= 0) {
    return { label: 'Время автоотклонения истекло', urgent: true };
  }
  return {
    label: `До автоотклонения: ${hours}ч`,
    urgent: hours <= 6,
  };
}

/**
 * Cross-club application review modal. Mirrors the portal pattern of
 * ProfileEditModal (z-index 150). Reject is two-step: tap «Отклонить» reveals
 * a reason textarea, confirming sends the mutation only when reason ≥5 chars
 * after trim (backend now enforces @Size(min=5, max=500), see
 * docs/modules/applications-inbox.md).
 */
export const ApplicationReviewModal: FC<ApplicationReviewModalProps> = ({
  application,
  open,
  onClose,
}) => {
  const haptic = useHaptic();
  const approveMutation = useApproveApplicationMutation();
  const rejectMutation = useRejectApplicationMutation();

  const [rejectMode, setRejectMode] = useState(false);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);

  // Lock body scroll while modal is open and reset internal state every time
  // it opens — otherwise reopening with the same modal instance leaks stale
  // reject-mode / reason / error.
  useEffect(() => {
    if (!open) return;
    setRejectMode(false);
    setReason('');
    setError(null);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open, application.applicationId]);

  // Close on Escape — same shortcut as other portal modals in the app.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  const submitting = approveMutation.isPending || rejectMutation.isPending;
  const trimmedReason = reason.trim();
  const reasonValid = trimmedReason.length >= REASON_MIN;

  const { applicant, peerStats, club, hoursUntilAutoReject, answerText } = application;
  const fullName = `${applicant.firstName}${applicant.lastName ? ` ${applicant.lastName}` : ''}`;
  const hoursHint = formatHoursHint(hoursUntilAutoReject);

  // City + country: mirror ProfilePage's `pf-identity .location` formatting.
  // Render only when city is set; country alone (without city) is too vague.
  const locationLabel = applicant.city
    ? [applicant.city, countryNameByCode(applicant.country)].filter(Boolean).join(', ')
    : null;
  const bio = applicant.bio?.trim();
  const hasInterests = applicant.interests.length > 0;

  const handleApprove = () => {
    haptic.impact('medium');
    setError(null);
    approveMutation.mutate(
      { applicationId: application.applicationId, clubId: club.id },
      {
        onSuccess: () => {
          haptic.notify('success');
          onClose();
        },
        onError: (e) => {
          setError(e.message);
          haptic.notify('error');
        },
      },
    );
  };

  const handleRejectClick = () => {
    haptic.impact('light');
    setRejectMode(true);
    setError(null);
  };

  const handleRejectCancel = () => {
    haptic.select();
    setRejectMode(false);
    setReason('');
    setError(null);
  };

  const handleRejectConfirm = () => {
    if (!reasonValid) return;
    haptic.impact('medium');
    setError(null);
    rejectMutation.mutate(
      {
        applicationId: application.applicationId,
        clubId: club.id,
        reason: trimmedReason,
      },
      {
        onSuccess: () => {
          haptic.notify('warning');
          onClose();
        },
        onError: (e) => {
          setError(e.message);
          haptic.notify('error');
        },
      },
    );
  };

  return createPortal(
    <>
      <div className="pf-edit-overlay" onClick={submitting ? undefined : onClose} aria-hidden="true" />
      <div
        className="pf-edit-sheet"
        role="dialog"
        aria-modal="true"
        aria-label="Заявка в клуб"
      >
        <div className="city-picker-grabber" aria-hidden="true" />
        <div className="pf-edit-header">
          <h2>Заявка</h2>
          <button
            type="button"
            className="city-picker-close"
            onClick={onClose}
            disabled={submitting}
            aria-label="Закрыть"
          >
            Закрыть
          </button>
        </div>

        <div className="pf-edit-body">
          {/* Hero — avatar + name + username */}
          <div className="app-review-hero">
            <div className="avt">
              {applicant.avatarUrl ? (
                <img src={applicant.avatarUrl} alt="" />
              ) : (
                getInitials(applicant.firstName, applicant.lastName)
              )}
            </div>
            <div className="meta">
              <div className="name">{fullName}</div>
              {applicant.telegramUsername && (
                <div className="handle">@{applicant.telegramUsername}</div>
              )}
            </div>
          </div>

          {/* Location — only when city is present */}
          {locationLabel && (
            <div className="app-review-location">{locationLabel}</div>
          )}

          {/* About — full bio, wrapped */}
          {bio && (
            <>
              <label className="pf-edit-label">О себе</label>
              <div className="app-review-bio">{bio}</div>
            </>
          )}

          {/* Interests — pill chips */}
          {hasInterests && (
            <>
              <label className="pf-edit-label">Интересы</label>
              <div className="app-review-interests">
                {applicant.interests.map((interest) => (
                  <span key={interest} className="pf-tag">{interest}</span>
                ))}
              </div>
            </>
          )}

          {/* Peer-signal — big line under hero/profile */}
          <div className="app-review-peer">{formatPeerSignal(peerStats)}</div>

          {/* Club row */}
          <div className="app-review-club">
            Клуб: <strong>{club.name}</strong>
          </div>

          {/* Q&A — optional */}
          {answerText && (
            <>
              <label className="pf-edit-label">Ответ на вопрос</label>
              <div className="app-review-answer">{answerText}</div>
            </>
          )}

          {/* Auto-reject hint */}
          <div className={`app-review-hint${hoursHint.urgent ? ' urgent' : ''}`}>
            {hoursHint.label}
          </div>

          {error && <div className="pf-edit-error">{error}</div>}

          {/* Reject-mode textarea inline so users see context above */}
          {rejectMode && (
            <>
              <label className="pf-edit-label" htmlFor="reject-reason">
                Причина отказа
              </label>
              <textarea
                id="reject-reason"
                className="pf-edit-textarea"
                value={reason}
                maxLength={REASON_MAX}
                rows={3}
                onChange={(e) => setReason(e.target.value)}
                placeholder={`Объясните причину (минимум ${REASON_MIN} символов)`}
                disabled={submitting}
              />
              <div className="pf-edit-counter">
                {reason.length}/{REASON_MAX}
              </div>
            </>
          )}
        </div>

        <div className="pf-edit-actions">
          {!rejectMode ? (
            <>
              <button
                type="button"
                className="ghost-btn"
                onClick={handleRejectClick}
                disabled={submitting}
              >
                Отклонить
              </button>
              <button
                type="button"
                className="mc-create-btn"
                onClick={handleApprove}
                disabled={submitting}
              >
                {submitting ? <Spinner size="s" /> : 'Принять'}
              </button>
            </>
          ) : (
            <>
              <button
                type="button"
                className="ghost-btn"
                onClick={handleRejectCancel}
                disabled={submitting}
              >
                Отмена
              </button>
              <button
                type="button"
                className="mc-create-btn"
                onClick={handleRejectConfirm}
                disabled={submitting || !reasonValid}
              >
                {submitting ? <Spinner size="s" /> : 'Подтвердить отклонение'}
              </button>
            </>
          )}
        </div>
      </div>
    </>,
    document.body,
  );
};

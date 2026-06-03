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
      <div className="rd-sheet-overlay" onClick={submitting ? undefined : onClose} aria-hidden="true" />
      <div
        className="rd-sheet"
        role="dialog"
        aria-modal="true"
        aria-label="Заявка в клуб"
      >
        <div className="rd-sheet-grabber" aria-hidden="true" />
        <div className="rd-sheet-head">
          <h2>Заявка</h2>
          <button
            type="button"
            className="rd-sheet-close"
            onClick={onClose}
            disabled={submitting}
            aria-label="Закрыть"
          >
            Закрыть
          </button>
        </div>

        <div className="rd-sheet-body">
          {/* Hero — avatar + name + username */}
          <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
            <span className="rd-ico" style={{ width: 48, height: 48, fontSize: 18 }}>
              {applicant.avatarUrl ? (
                <img src={applicant.avatarUrl} alt="" />
              ) : (
                getInitials(applicant.firstName, applicant.lastName)
              )}
            </span>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 17, fontWeight: 700, color: 'var(--text)' }}>{fullName}</div>
              {applicant.telegramUsername && (
                <div style={{ fontSize: 13, color: 'var(--text-dim)' }}>@{applicant.telegramUsername}</div>
              )}
            </div>
          </div>

          {/* Location — only when city is present */}
          {locationLabel && (
            <div style={{ fontSize: 13, color: 'var(--text-dim)' }}>{locationLabel}</div>
          )}

          {/* About — full bio, wrapped */}
          {bio && (
            <div className="rd-field">
              <span className="rd-label">О себе</span>
              <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{bio}</div>
            </div>
          )}

          {/* Interests — pill chips */}
          {hasInterests && (
            <div className="rd-field">
              <span className="rd-label">Интересы</span>
              <div className="rd-tags" style={{ margin: 0 }}>
                {applicant.interests.map((interest) => (
                  <span key={interest} className="rd-tag">{interest}</span>
                ))}
              </div>
            </div>
          )}

          {/* Peer-signal — big line under hero/profile */}
          <div style={{ fontSize: 13, color: 'var(--text-dim)' }}>{formatPeerSignal(peerStats)}</div>

          {/* Club row */}
          <div style={{ fontSize: 14, color: 'var(--text)' }}>
            Клуб: <strong>{club.name}</strong>
          </div>

          {/* Q&A — optional */}
          {answerText && (
            <div className="rd-field">
              <span className="rd-label">Ответ на вопрос</span>
              <div className="rd-glass" style={{ padding: '12px 14px' }}>
                <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{answerText}</div>
              </div>
            </div>
          )}

          {/* Auto-reject hint */}
          <div className="rd-hint" style={hoursHint.urgent ? { color: 'var(--danger)' } : undefined}>
            {hoursHint.label}
          </div>

          {error && <div className="rd-error" style={{ textAlign: 'left' }}>{error}</div>}

          {/* Reject-mode textarea inline so users see context above */}
          {rejectMode && (
            <div className="rd-field">
              <span className="rd-label">Причина отказа</span>
              <textarea
                id="reject-reason"
                className="rd-textarea"
                value={reason}
                maxLength={REASON_MAX}
                rows={3}
                onChange={(e) => setReason(e.target.value)}
                placeholder={`Объясните причину (минимум ${REASON_MIN} символов)`}
                disabled={submitting}
              />
              <div className="rd-hint" style={{ textAlign: 'right' }}>
                {reason.length}/{REASON_MAX}
              </div>
            </div>
          )}
        </div>

        <div className="rd-sheet-actions">
          {!rejectMode ? (
            <>
              <button
                type="button"
                className="rd-btn-outline"
                onClick={handleRejectClick}
                disabled={submitting}
              >
                Отклонить
              </button>
              <button
                type="button"
                className="rd-btn-primary"
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
                className="rd-btn-outline"
                onClick={handleRejectCancel}
                disabled={submitting}
              >
                Отмена
              </button>
              <button
                type="button"
                className="rd-btn-primary"
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

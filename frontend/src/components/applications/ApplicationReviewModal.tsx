import { FC, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import {
  useApproveApplicationMutation,
  useRejectApplicationMutation,
} from '../../queries/applications';
import { countryNameByCode } from '../CityPicker';
import { LevelPill } from '../reputation/LevelPill';
import { PlatformActivity } from './PlatformActivity';
import type { PendingApplicationDto } from '../../types/api';

// Минимальная длина причины отказа (символов после trim) — совпадает с @Size(min=5) на бэкенде.
const REASON_MIN = 5;
// Максимальная длина причины отказа — совпадает с @Size(max=500) на бэкенде.
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
 * Кросс-клубовая модалка рассмотрения заявки. Повторяет portal-паттерн
 * ProfileEditModal (z-index 150). Отказ — в два шага: тап «Отклонить» открывает
 * textarea причины, подтверждение шлёт мутацию только когда причина ≥5 символов
 * после trim (бэкенд теперь навязывает @Size(min=5, max=500), см.
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
  const rejectFieldRef = useRef<HTMLDivElement>(null);

  // Reveal-then-reach: при открытии режима отказа поле причины добавляется в конец скроллируемого
  // тела — за пределами экрана. Скроллим к нему + фокусируем, чтобы организатор не гадал, где печатать.
  useEffect(() => {
    if (!rejectMode) return;
    rejectFieldRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    document.getElementById('reject-reason')?.focus({ preventScroll: true });
  }, [rejectMode]);

  // Блокируем скролл body, пока модалка открыта, и сбрасываем внутреннее состояние
  // при каждом открытии — иначе повторное открытие того же инстанса модалки утащит
  // за собой устаревшие reject-mode / reason / error.
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

  // Закрытие по Escape — тот же шорткат, что и у остальных portal-модалок приложения.
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

  // Город + страна: повторяем форматирование `pf-identity .location` из ProfilePage.
  // Рендерим только когда указан город; страна без города — слишком расплывчато.
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
          {/* Шапка — аватар + имя + username */}
          <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
            <span className="rd-avatar" style={{ width: 48, height: 48, borderRadius: '50%', fontSize: 18 }}>
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

          {/* Локация — только когда указан город */}
          {locationLabel && (
            <div style={{ fontSize: 13, color: 'var(--text-dim)' }}>{locationLabel}</div>
          )}

          {/* Глобальный геймификационный уровень */}
          <div>
            <LevelPill levelName={peerStats.levelName} tier={peerStats.levelTier} level={peerStats.level} />
          </div>

          {/* О себе — полное bio, с переносами */}
          {bio && (
            <div className="rd-field">
              <span className="rd-label">О себе</span>
              <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{bio}</div>
            </div>
          )}

          {/* Интересы — чипы-пилюли */}
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

          {/* Активность на платформе — донат «надёжен в N из M клубов» + строка участия */}
          <div className="rd-field">
            <span className="rd-label">Активность на платформе</span>
            <PlatformActivity stats={peerStats} />
          </div>

          {/* Строка клуба */}
          <div style={{ fontSize: 14, color: 'var(--text)' }}>
            Клуб: <strong>{club.name}</strong>
          </div>

          {/* Вопрос-ответ — опционально */}
          {answerText && (
            <div className="rd-field">
              <span className="rd-label">Ответ на вопрос</span>
              <div className="rd-glass" style={{ padding: '12px 14px' }}>
                <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{answerText}</div>
              </div>
            </div>
          )}

          {/* Подсказка про автоотклонение */}
          <div className="rd-hint" style={hoursHint.urgent ? { color: 'var(--danger)' } : undefined}>
            {hoursHint.label}
          </div>

          {error && <div className="rd-error" style={{ textAlign: 'left' }}>{error}</div>}

          {/* Textarea режима отказа — инлайн, чтобы контекст заявки оставался виден выше */}
          {rejectMode && (
            <div className="rd-field" ref={rejectFieldRef}>
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

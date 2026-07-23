import { FC, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { Toast } from '../components/Toast';
import { ApiError } from '../api/apiClient';
import { useSubmitFeedbackMutation } from '../queries/feedback';

// Совпадает с @Size(max = 2000) бэкенда — счётчик показывает реальную границу.
const MESSAGE_MAX_LENGTH = 2000;

function submitErrorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 429) return 'Слишком много сообщений подряд. Подождите минуту и попробуйте снова.';
    if (e.status === 503) return 'Не удалось отправить: поддержка временно недоступна. Попробуйте позже.';
  }
  return 'Не удалось отправить сообщение. Попробуйте ещё раз.';
}

/**
 * Форма обратной связи («+» → «Сообщить о проблеме»). Текст уходит DM саппорт-аккаунту
 * (docs/modules/feedback.md); 204 от бэка означает реальную доставку в Telegram, поэтому
 * успех показывается только после ответа.
 */
export const FeedbackPage: FC = () => {
  useBackButton(true);
  const navigate = useNavigate();
  const location = useLocation();
  const haptic = useHaptic();
  const submitMut = useSubmitFeedbackMutation();
  const [message, setMessage] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);

  // Route, с которого открыли шит «+», — передан CreateActivityFlow; уходит в репорт
  // как контекст «где воспроизвелось».
  const fromPage = (location.state as { from?: string } | null)?.from;

  const handleSubmit = async () => {
    if (!message.trim() || submitMut.isPending) return;
    setError(null);
    try {
      haptic.impact('medium');
      await submitMut.mutateAsync({ message: message.trim(), page: fromPage });
      haptic.notify('success');
      setSent(true);
    } catch (e) {
      console.error('submitFeedback failed', e);
      haptic.notify('error');
      setError(submitErrorMessage(e));
    }
  };

  // Прямое открытие /feedback (перезагрузка, deep-link) — истории нет, navigate(-1) был бы
  // no-op и запер бы пользователя на задизейбленной после успеха форме; уходим на главную.
  const goBack = () => {
    if (location.key === 'default') navigate('/', { replace: true });
    else navigate(-1);
  };

  const handleCancel = () => {
    haptic.impact('light');
    goBack();
  };

  return (
    <div className="rd-page">
      <div className="rd-ft-eyebrow">Обратная связь</div>
      <h1 className="rd-page-h" style={{ marginBottom: 18 }}>Сообщить о проблеме</h1>

      <div className="rd-form">
        <label className="rd-field">
          <span className="rd-label">Что случилось? <span className="rd-req">*</span></span>
          <textarea
            className="rd-textarea"
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            rows={6}
            maxLength={MESSAGE_MAX_LENGTH}
            placeholder="Опишите проблему или идею: что делали, что ожидали, что произошло"
            disabled={sent}
          />
          <span className="rd-hint">
            {message.length} / {MESSAGE_MAX_LENGTH} · сообщение уйдёт команде Clubs в Telegram
          </span>
        </label>

        {error && <div className="rd-error">{error}</div>}

        <div className="rd-form-actions">
          <button type="button" className="rd-btn-outline" onClick={handleCancel}>
            Отмена
          </button>
          <button
            type="button"
            className="rd-btn-primary"
            onClick={handleSubmit}
            disabled={!message.trim() || submitMut.isPending || sent}
          >
            {submitMut.isPending ? 'Отправка…' : 'Отправить'}
          </button>
        </div>
      </div>

      {sent && (
        <Toast message="Спасибо! Сообщение отправлено" durationMs={1600} onClose={goBack} />
      )}
    </div>
  );
};

import { FC, useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { createInviteShare } from '../../api/clubs';
import { canShareMessage, shareInviteMessage } from '../../telegram/sdk';
import { useHaptic } from '../../hooks/useHaptic';
import type { InviteShareDto } from '../../types/api';

interface InviteSheetProps {
  clubId: string;
  onClose: () => void;
}

/**
 * Боттом-шит «Пригласить в клуб» (club-invites, кадр B мокапа). Два способа:
 *  - «Отправить в Telegram» — нативный пикер чатов, карточка-приглашение уходит ОТ ИМЕНИ
 *    организатора (prepared message, Mini Apps 8.0+);
 *  - «Скопировать ссылку» — deep-link для поста/другого мессенджера.
 * Prepared message короткоживущий — запрашивается при каждом открытии шита, не кэшируется.
 */
export const InviteSheet: FC<InviteSheetProps> = ({ clubId, onClose }) => {
  const haptic = useHaptic();
  const [share, setShare] = useState<InviteShareDto | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let cancelled = false;
    createInviteShare(clubId)
      .then((dto) => { if (!cancelled) setShare(dto); })
      .catch((e) => {
        if (!cancelled) setLoadError(e instanceof Error ? e.message : 'Не удалось получить ссылку');
      });
    return () => { cancelled = true; };
  }, [clubId]);

  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, []);

  const shareAvailable = !!share?.preparedMessageId && canShareMessage();

  const handleShare = async () => {
    if (!share?.preparedMessageId) return;
    haptic.impact('medium');
    setActionError(null);
    try {
      await shareInviteMessage(share.preparedMessageId);
      haptic.notify('success');
      onClose();
    } catch (_e) {
      // Сюда попадает и отмена пикера пользователем — остаёмся в шите с мягкой подсказкой.
      setActionError('Не получилось отправить — попробуйте ещё раз или скопируйте ссылку.');
    }
  };

  const handleCopy = async () => {
    if (!share) return;
    haptic.impact('light');
    setActionError(null);
    try {
      await navigator.clipboard.writeText(share.inviteUrl);
      setCopied(true);
      haptic.notify('success');
      setTimeout(() => setCopied(false), 2000);
    } catch (_e) {
      // Буфер недоступен (старый webview) — ссылка видна текстом ниже, можно выделить руками.
      setActionError('Не удалось скопировать автоматически — выделите ссылку ниже.');
    }
  };

  return createPortal(
    <>
      <div className="rd-sheet-overlay" onClick={onClose} aria-hidden="true" />
      <div className="rd-sheet" role="dialog" aria-modal="true" aria-label="Пригласить в клуб">
        <div className="rd-sheet-grabber" aria-hidden="true" />
        <div className="rd-sheet-head">
          <h2>Пригласить в клуб</h2>
          <button type="button" className="rd-sheet-close" onClick={onClose}>Закрыть</button>
        </div>

        <div className="rd-sheet-body">
          <div className="rd-cta-hint" style={{ marginTop: 0, textAlign: 'left' }}>
            Приглашение уйдёт от вашего имени — получатель увидит карточку клуба с кнопкой.
          </div>

          {loadError && <div className="rd-error" style={{ textAlign: 'left' }}>{loadError}</div>}

          {!share && !loadError && (
            <div style={{ display: 'flex', justifyContent: 'center', padding: 24 }}>
              <Spinner size="m" />
            </div>
          )}

          {share && (
            <>
              {shareAvailable && (
                <button type="button" className="rd-btn-primary" onClick={handleShare}>
                  ✈️ Отправить в Telegram
                </button>
              )}
              <button
                type="button"
                className="rd-btn-outline"
                style={{ marginTop: shareAvailable ? 8 : 0 }}
                onClick={handleCopy}
              >
                {copied ? '✓ Скопировано' : '🔗 Скопировать ссылку'}
              </button>

              {actionError && <div className="rd-error" style={{ textAlign: 'left' }}>{actionError}</div>}

              <div className="rd-cta-hint" style={{ wordBreak: 'break-all', userSelect: 'all' }}>
                {share.inviteUrl}
              </div>
            </>
          )}
        </div>
      </div>
    </>,
    document.body,
  );
};

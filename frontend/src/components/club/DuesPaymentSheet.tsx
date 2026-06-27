import { ChangeEvent, FC, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useClaimDuesMutation } from '../../queries/members';
import { uploadImage } from '../../api/clubs';
import { useHaptic } from '../../hooks/useHaptic';

interface DuesPaymentSheetProps {
  clubId: string;
  /** Monthly dues amount in ₽ (club.subscriptionPrice). */
  price: number;
  /** Organizer's СБП link/phone — null if not set (then only cash is offered). */
  paymentLink: string | null;
  paymentMethodNote: string | null;
  onClose: () => void;
  /** Called after a claim is submitted, so the page can show «оплата на проверке». */
  onClaimed: () => void;
}

type Method = 'sbp' | 'cash';

/**
 * Member dues-payment flow (de-Stars). The frozen member declares they paid:
 *  - СБП: pay via the organizer's link (amount + emoji + button), then attach a payment screenshot
 *    (mandatory) and confirm.
 *  - Наличные: a plain attestation checkbox, no screenshot.
 * Either way it submits a claim the organizer reviews — access still opens only when the organizer
 * presses «Взнос получен» (honor-system preserved).
 */
export const DuesPaymentSheet: FC<DuesPaymentSheetProps> = ({
  clubId,
  price,
  paymentLink,
  paymentMethodNote,
  onClose,
  onClaimed,
}) => {
  const haptic = useHaptic();
  const claim = useClaimDuesMutation();
  const fileInputRef = useRef<HTMLInputElement>(null);

  // No СБП requisites → cash is the only option.
  const [method, setMethod] = useState<Method>(paymentLink ? 'sbp' : 'cash');
  const [proofUrl, setProofUrl] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [cashConfirmed, setCashConfirmed] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, []);

  const handlePickFile = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // allow re-picking the same file
    if (!file) return;
    if (!['image/jpeg', 'image/png'].includes(file.type)) {
      setError('Только JPEG или PNG');
      return;
    }
    if (file.size > 5 * 1024 * 1024) {
      setError('Файл больше 5 МБ');
      return;
    }
    setError(null);
    setUploading(true);
    try {
      const url = await uploadImage(file);
      setProofUrl(url);
      haptic.impact('light');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Не удалось загрузить скриншот');
    } finally {
      setUploading(false);
    }
  };

  const canConfirm = method === 'sbp' ? !!proofUrl : cashConfirmed;

  const handleConfirm = () => {
    if (!canConfirm || claim.isPending) return;
    setError(null);
    haptic.impact('medium');
    claim.mutate(
      { clubId, method, proofUrl: method === 'sbp' ? proofUrl : null },
      {
        onSuccess: () => { haptic.notify('success'); onClaimed(); },
        onError: (e) => {
          haptic.notify('error');
          setError(e instanceof Error ? e.message : 'Не удалось отправить заявку');
        },
      },
    );
  };

  return createPortal(
    <>
      <div className="rd-sheet-overlay" onClick={onClose} aria-hidden="true" />
      <div className="rd-sheet" role="dialog" aria-modal="true" aria-label="Оплата взноса">
        <div className="rd-sheet-grabber" aria-hidden="true" />
        <div className="rd-sheet-head">
          <h2>Оплата взноса</h2>
          <button type="button" className="rd-sheet-close" onClick={onClose}>Закрыть</button>
        </div>

        <div className="rd-sheet-body">
          {/* Amount */}
          <div className="rd-dues-amount">
            <span className="rd-dues-emoji" aria-hidden="true">💳</span>
            <span className="rd-dues-sum">{price} ₽</span>
            <span className="rd-dues-per">/ мес</span>
          </div>

          {/* Method switch — only when both are available (requisites set). */}
          {paymentLink && (
            <div className="rd-seg-control" role="tablist">
              <button
                type="button"
                role="tab"
                aria-selected={method === 'sbp'}
                className={`rd-seg-item${method === 'sbp' ? ' rd-active' : ''}`}
                onClick={() => { setMethod('sbp'); setError(null); }}
              >
                По СБП
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={method === 'cash'}
                className={`rd-seg-item${method === 'cash' ? ' rd-active' : ''}`}
                onClick={() => { setMethod('cash'); setError(null); }}
              >
                Наличными
              </button>
            </div>
          )}

          {error && <div className="rd-error" style={{ textAlign: 'left' }}>{error}</div>}

          {method === 'sbp' ? (
            <>
              {/* Step 1 — pay */}
              <div className="rd-step">
                <div className="rd-step-h"><span className="rd-step-n">1</span> Оплатите по СБП</div>
                <button
                  type="button"
                  className="rd-btn-primary"
                  onClick={() => { haptic.impact('medium'); window.open(paymentLink!, '_blank', 'noopener,noreferrer'); }}
                >
                  Оплатить по СБП
                </button>
                <div className="rd-cta-hint">
                  {paymentMethodNote
                    ? `${paymentMethodNote}. Перевод идёт напрямую организатору.`
                    : 'Перевод идёт напрямую организатору.'}
                </div>
              </div>

              {/* Step 2 — proof */}
              <div className="rd-step">
                <div className="rd-step-h"><span className="rd-step-n">2</span> Прикрепите скриншот оплаты</div>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/jpeg,image/png"
                  style={{ display: 'none' }}
                  onChange={handlePickFile}
                />
                {proofUrl ? (
                  <div className="rd-proof">
                    <img src={proofUrl} alt="Скриншот оплаты" className="rd-proof-img" />
                    <button type="button" className="rd-btn-outline" onClick={() => fileInputRef.current?.click()}>
                      Заменить скриншот
                    </button>
                  </div>
                ) : (
                  <button
                    type="button"
                    className="rd-btn-outline"
                    disabled={uploading}
                    onClick={() => fileInputRef.current?.click()}
                  >
                    {uploading ? <Spinner size="s" /> : '📎 Прикрепить скриншот'}
                  </button>
                )}
              </div>
            </>
          ) : (
            <div className="rd-step">
              <div className="rd-step-h">Оплата наличными</div>
              <div className="rd-cta-hint" style={{ marginBottom: 10 }}>
                Передайте {price} ₽ организатору наличными, затем подтвердите оплату ниже.
              </div>
              <label className="rd-check">
                <input
                  type="checkbox"
                  checked={cashConfirmed}
                  onChange={(e) => { setCashConfirmed(e.target.checked); setError(null); }}
                />
                <span>Подтверждаю, что передал(а) взнос наличными</span>
              </label>
            </div>
          )}

          <button
            type="button"
            className="rd-btn-primary"
            disabled={!canConfirm || claim.isPending}
            onClick={handleConfirm}
            style={{ marginTop: 8 }}
          >
            {claim.isPending ? <Spinner size="s" /> : 'Подтвердить оплату'}
          </button>
          <div className="rd-cta-hint">После подтверждения организатор проверит оплату и откроет доступ.</div>
        </div>
      </div>
    </>,
    document.body,
  );
};

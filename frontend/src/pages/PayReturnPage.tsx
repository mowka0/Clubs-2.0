import { FC } from 'react';
import { useSearchParams } from 'react-router-dom';

interface PayReturnPageProps {
  kind: 'success' | 'fail';
}

/**
 * Страницы возврата из браузера после оплаты у провайдера (platform-billing.md § 7): обычный
 * веб вне Telegram, JWT нет, к API не ходим — только текст и кнопка обратно в Mini App.
 * Статус оплаты по этим страницам НЕ меняется: подтверждает только ResultURL на бэкенде.
 */
export const PayReturnPage: FC<PayReturnPageProps> = ({ kind }) => {
  const [params] = useSearchParams();
  const clubId = params.get('club');
  const bot = params.get('bot');
  // `t.me/<bot>?startapp=…` открывает главный Mini App бота; DeepLinkHandler разбирает `billing_<clubId>`.
  const backUrl = bot && clubId ? `https://t.me/${bot}?startapp=billing_${clubId}` : bot ? `https://t.me/${bot}` : null;

  return (
    <div className="rd-pay-return">
      <div className="card">
        <div className="logo"><i aria-hidden="true" /> Clubs</div>
        <div className="rd-billing-state">
          <div className="ic" aria-hidden="true">{kind === 'success' ? '✅' : '😕'}</div>
          <p className="t">{kind === 'success' ? 'Оплата принята' : 'Оплата не прошла'}</p>
          <p className="d">
            {kind === 'success'
              ? 'Возвращайтесь в Telegram — подтверждение придёт в личку от бота, а встречу можно будет создать сразу.'
              : 'Деньги не списаны. Вернитесь в Telegram и попробуйте ещё раз — можно другой картой или по СБП.'}
          </p>
        </div>
        {backUrl && <a className="btn" href={backUrl}>Открыть Clubs в Telegram</a>}
        <div className="small">
          {kind === 'success'
            ? 'Если кнопка не сработала — просто откройте Telegram: бот уже написал вам.'
            : 'Списание всё-таки было? Напишите нам через «Сообщить о проблеме» — разберёмся.'}
        </div>
      </div>
    </div>
  );
};

import { FC } from 'react';
import { useSearchParams } from 'react-router-dom';

interface PayReturnPageProps {
  kind: 'success' | 'fail';
}

/**
 * Имя бота для кнопки «Открыть Clubs в Telegram». Берётся из бандла, а НЕ из адреса страницы:
 * `?bot=<чужой>` давал бы брендированную страницу «Оплата принята» с кнопкой в чужого бота —
 * фишинг на нашем домене (ревью 2026-09-07). Значение публичное, задаётся build-аргом
 * VITE_TELEGRAM_BOT_USERNAME; дефолт совпадает с `telegram.bot-username` бэкенда.
 */
const BOT_USERNAME = import.meta.env.VITE_TELEGRAM_BOT_USERNAME || 'clubs_v2_bot';
/** Клуб из адреса подставляется в deep link, поэтому принимается только как UUID. */
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Страницы возврата из браузера после оплаты у провайдера (platform-billing.md § 7): обычный
 * веб вне Telegram, JWT нет, к API не ходим — только текст и кнопка обратно в Mini App.
 * Статус оплаты по этим страницам НЕ меняется: подтверждает только ResultURL на бэкенде.
 */
export const PayReturnPage: FC<PayReturnPageProps> = ({ kind }) => {
  const [params] = useSearchParams();
  const clubParam = params.get('club');
  const clubId = clubParam && UUID_RE.test(clubParam) ? clubParam : null;
  // `t.me/<bot>?startapp=…` открывает главный Mini App бота; DeepLinkHandler разбирает `billing_<clubId>`.
  const backUrl = clubId
    ? `https://t.me/${BOT_USERNAME}?startapp=billing_${clubId}`
    : `https://t.me/${BOT_USERNAME}`;

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
        <a className="btn" href={backUrl}>Открыть Clubs в Telegram</a>
        <div className="small">
          {kind === 'success'
            ? 'Если кнопка не сработала — просто откройте Telegram: бот уже написал вам.'
            : 'Списание всё-таки было? Напишите нам через «Сообщить о проблеме» — разберёмся.'}
        </div>
      </div>
    </div>
  );
};

import { FC } from 'react';
import { CHAT_PRICE_LABEL, CHAT_PRICE_LINE, TRIAL_DAYS_DEFAULT } from '../api/billing';
import { OFFER_TITLE, OFFER_UPDATED, offer } from '../components/billing/offerText';
import { BOT_LINK } from '../utils/telegramLinks';
import { SELLER, SUPPORT } from './landingContent';

const SUPPORT_LINK = `https://t.me/${SUPPORT.telegram}`;

/**
 * Публичная страница сервиса — то, что видит человек не из Telegram: модератор платёжного
 * провайдера, читатель рекламы, кто-то по ссылке из чата. Корень домена показывает её, когда
 * нет initData (см. entry.ts), `/about` — всегда. Без API и без авторизации: описание,
 * цена, условия, продавец и полный текст оферты — ровно то, что требует модерация.
 */
export const LandingPage: FC = () => (
  <div className="rd-landing">
    <header className="hd">
      <div className="logo"><img src="/brand/logo-wordmark.jpg" alt="Clubs" height={44} /></div>
      <a className="btn" href={BOT_LINK}>Открыть в Telegram</a>
    </header>

    <section className="hero">
      <h1>Clubs — бот, который ведёт офлайн-встречи вашего клуба прямо в Telegram-чате</h1>
      <p>
        Беговой клуб, настолки, книжный, походы — любое сообщество, которое собирается вживую.
        Организатор создаёт встречу, бот публикует афишу в чат, собирает «кто идёт», напоминает
        участникам и подводит итог. Рутина уходит боту, людям остаётся встреча.
      </p>
      <p className="promise">{CHAT_PRICE_LINE}</p>
      <a className="btn big" href={BOT_LINK}>Подключить к своему чату</a>
    </section>

    <section>
      <h2>Что делает бот</h2>
      <ul className="feat">
        <li><b>Афиша в чате.</b> Встреча закрепляется в чате с местом, временем и кнопкой «Иду».</li>
        <li><b>Кто идёт.</b> Состав виден всем, места считаются сами, лист ожидания — тоже.</li>
        <li><b>Напоминания.</b> Бот напоминает участникам заранее и спрашивает, кто передумал.</li>
        <li><b>Явка и репутация.</b> Кто пришёл, кто обещал и не пришёл — история копится по клубу.</li>
        <li><b>Сборы.</b> Скинуться на аренду или подарок: бот считает, кто сколько должен, и напоминает.</li>
        <li><b>Приложение внутри Telegram.</b> Ничего ставить не нужно — всё открывается из чата.</li>
      </ul>
    </section>

    <section>
      <h2>Сколько стоит</h2>
      <div className="price">
        <div className="p1">Первые {TRIAL_DAYS_DEFAULT} дней — бесплатно</div>
        <div className="p2">дальше <b>{CHAT_PRICE_LABEL} в месяц</b> за клуб</div>
      </div>
      <ul className="terms">
        <li>Отсчёт бесплатного периода идёт с первой созданной встречи, а не с подключения чата.</li>
        <li>Платит только владелец клуба. Участники не платят ничего.</li>
        <li>Оплата картой или по СБП через платёжный сервис Robokassa. Чек приходит автоматически.</li>
        <li>Автопродление можно включить или выключить в любой момент на странице клуба — оплаченный период при этом сохраняется.</li>
        <li>Если оплата не поступила, ещё 7 дней всё работает; потом нельзя создавать новые встречи, начатые доживают, данные клуба сохраняются.</li>
      </ul>
    </section>

    <section>
      <h2>Возврат</h2>
      {/* Слово в слово с § 3.7 оферты ниже: страницу читает модератор, противоречия недопустимы. */}
      <p>
        Отказаться от доступа можно в любое время. Если оплаченный период ещё не закончился, вернём
        стоимость неиспользованных полных дней тем же способом, которым была оплата, в течение десяти
        рабочих дней с обращения. Напишите в поддержку — ответим в течение трёх рабочих дней.
        Отключить автопродление можно без обращения: ползунок на странице клуба.
      </p>
    </section>

    <section id="offer">
      <h2>{OFFER_TITLE}</h2>
      <div className="offer">
        <p className="muted">Редакция от {OFFER_UPDATED}</p>
        {offer({
          recipientName: SELLER.name, inn: SELLER.inn, priceLabel: CHAT_PRICE_LABEL, trialDays: TRIAL_DAYS_DEFAULT,
          supportTelegram: SUPPORT.telegram, supportEmail: SUPPORT.email,
        }).map((s) => (
          <div key={s.title}>
            <h3>{s.title}</h3>
            {s.paragraphs.map((p) => <p key={p.slice(0, 24)}>{p}</p>)}
          </div>
        ))}
      </div>
    </section>

    <section className="seller">
      <h2>Продавец и контакты</h2>
      <p>
        Самозанятый <b>{SELLER.name}</b>, ИНН {SELLER.inn}. Применяется налог на профессиональный
        доход, НДС не облагается.
      </p>
      <p>
        Мы обрабатываем персональные данные по <a href="/privacy">политике конфиденциальности</a>.
      </p>
      <p>
        Поддержка: <a href={SUPPORT_LINK}>@{SUPPORT.telegram}</a>
        {SUPPORT.email && <> · <a href={`mailto:${SUPPORT.email}`}>{SUPPORT.email}</a></>}
      </p>
    </section>

    <footer>
      <a href="/privacy">Политика обработки персональных данных</a> · © Clubs, {new Date().getFullYear()}
    </footer>
  </div>
);

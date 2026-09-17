import { FC } from 'react';
import { Link } from 'react-router-dom';
import { BOT_LINK } from '../utils/telegramLinks';
import { PRIVACY_TITLE, PRIVACY_UPDATED, privacySections } from './privacyText';

/**
 * Политика обработки персональных данных на отдельном адресе `/privacy`: на неё ссылается
 * лендинг, её же ждёт модерация платёжного провайдера. Как и лендинг — обычный веб вне
 * Telegram, без API и авторизации.
 */
export const PrivacyPage: FC = () => (
  <div className="rd-landing">
    <header className="hd">
      <Link className="logo" to="/"><img src="/brand/logo-wordmark.jpg" alt="Clubs" width={40} height={40} /></Link>
      <a className="btn" href={BOT_LINK}>Открыть в Telegram</a>
    </header>

    <section className="hero">
      <h1>{PRIVACY_TITLE}</h1>
      <p>Обновлено {PRIVACY_UPDATED}</p>
    </section>

    {privacySections().map((section) => (
      <section key={section.title}>
        <h2>{section.title}</h2>
        {section.paragraphs.map((paragraph) => (
          <p key={paragraph.slice(0, 24)} className="pp">{paragraph}</p>
        ))}
      </section>
    ))}

    <footer>
      <Link to="/about">Условия и оферта</Link> · © Clubs, {new Date().getFullYear()}
    </footer>
  </div>
);

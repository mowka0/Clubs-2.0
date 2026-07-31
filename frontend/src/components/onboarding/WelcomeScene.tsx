import { FC, ReactNode } from 'react';

/**
 * Контекст первого вступления: free — бесплатный клуб (кадр A мокапа welcome-scene.html),
 * paid — платный, membership в frozen до взноса (кадр B), applied — мест не было,
 * ушла заявка (кадр C).
 */
export type WelcomeSceneVariant = 'free' | 'paid' | 'applied';

/** «14 участников» — русская плюрализация для чипа клуба. */
export function memberCountCaption(count: number): string {
  const mod10 = count % 10;
  const mod100 = count % 100;
  const word =
    mod10 === 1 && mod100 !== 11
      ? 'участник'
      : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)
        ? 'участника'
        : 'участников';
  return `${count} ${word}`;
}

interface WelcomeStep {
  icon: string;
  title: string;
  text?: string;
}

interface VariantContent {
  art: string;
  title: string;
  subtitle: ReactNode;
  steps: WelcomeStep[];
  fact: ReactNode;
  factTone: 'neutral' | 'pay' | 'wait';
  ctaLabel: string;
  ctaHint?: string;
}

// Тексты утверждены PO 2026-07-24 по мокапу docs/design/onboarding/mockups/welcome-scene.html
// (кадры A–C, «всё так») — не переписывать без нового решения.
const CONTENT: Record<WelcomeSceneVariant, VariantContent> = {
  free: {
    art: '🎉',
    title: 'Ты в клубе!',
    subtitle: (
      <>
        Здесь всё крутится вокруг <b>живых встреч</b>. Вот как это работает:
      </>
    ),
    steps: [
      {
        icon: '1',
        title: 'Организатор создаёт встречи',
        text: 'Они появятся у тебя в «Активностях» — мы позовём.',
      },
      {
        icon: '2',
        title: 'Голосуй — пойдёшь или нет',
        text: 'Так организатор понимает, сколько вас будет.',
      },
      {
        icon: '3',
        title: 'Подтверди место и приходи',
        text: 'Ближе к встрече откроется подтверждение — места разбирают первые.',
      },
    ],
    fact: (
      <>
        <b>Надёжность</b> — твоя репутация в клубе. Приходишь, когда обещал, — она растёт,
        и организаторы это видят.
      </>
    ),
    factTone: 'neutral',
    ctaLabel: 'Перейти в клуб',
  },
  paid: {
    art: '🎉',
    title: 'Ты в клубе!',
    subtitle: (
      <>
        Здесь всё крутится вокруг <b>живых встреч</b>. Вот как это работает:
      </>
    ),
    steps: [
      {
        icon: '1',
        title: 'Организатор создаёт встречи',
        text: 'Афишу клуба ты уже можешь посмотреть.',
      },
      {
        icon: '2',
        title: 'Голосуй и подтверждай место',
        text: 'Откроется после взноса — вместе со всеми активностями.',
      },
      {
        icon: '3',
        title: 'Приходи вживую',
        text: 'Каждая встреча растит твою надёжность в клубе.',
      },
    ],
    fact: (
      <>
        <b>Взнос — напрямую организатору</b> (наличными или по СБП). Как только он подтвердит
        оплату — доступ откроется.
      </>
    ),
    factTone: 'pay',
    ctaLabel: 'Перейти в клуб',
    ctaHint: 'Оплатить взнос можно на странице клуба',
  },
  applied: {
    art: '✉️',
    title: 'Заявка у организатора',
    subtitle: (
      <>
        Организатор может расширить клуб и принять тебя — <b>мы сообщим</b>, как только он решит.
      </>
    ),
    steps: [
      {
        icon: '🙌',
        title: 'А пока — оглядись',
        text: 'Clubs — это клубы по интересам рядом с тобой: спорт, настолки, еда, творчество.',
      },
      {
        icon: '📍',
        title: 'Клубы встречаются вживую',
        text: 'Голосуешь за встречи, подтверждаешь место, приходишь.',
      },
    ],
    fact: (
      <>
        <b>Заявка отправлена.</b> Ответ придёт сюда и в личку от бота.
      </>
    ),
    factTone: 'wait',
    ctaLabel: 'Посмотреть другие клубы',
  },
};

function getClubInitials(name: string): string {
  return name
    .replace(/[«»"']/g, '')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase())
    .join('');
}

interface WelcomeSceneProps {
  variant: WelcomeSceneVariant;
  clubName: string;
  /** Подпись под названием в чипе: «Москва · 14 участников» / «Москва · 900 ₽» / «Москва · мест пока нет». */
  clubCaption: string;
  clubAvatarUrl?: string | null;
  ctaPending: boolean;
  onCta: () => void;
}

/**
 * Велком-сцена новичка в клубе: показывается ОДИН раз за жизнь аккаунта, сразу после самого
 * первого вступления, вместо сухого «Добро пожаловать». Это момент награды, а не подсказки,
 * поэтому у неё свой ключ `WELCOME` — отдельный от тура `CLUB`, который идёт следом уже на
 * странице клуба. Вариант applied тур НЕ помечает: человек остался без клуба, вступления
 * не случилось, и сцену он честно увидит при первом настоящем. Завершение — забота
 * вызывающего (onCta): порядок «сервер → навигация → setUser» обязателен,
 * см. useCompleteTourMutation.
 *
 * Полноэкранная сцена со своими `wsc-*` классами — решение PO 2026-07-24: кадры A–C,
 * альтернатива-шторка (кадр D) отклонена.
 */
export const WelcomeScene: FC<WelcomeSceneProps> = ({
  variant,
  clubName,
  clubCaption,
  clubAvatarUrl,
  ctaPending,
  onCta,
}) => {
  const content = CONTENT[variant];

  return (
    <div className="wsc-root">
      <div className="wsc-body">
        <div className="wsc-art">
          <div className="wsc-ring" aria-hidden="true">{content.art}</div>
        </div>

        <h2 className="wsc-title">{content.title}</h2>

        <div className="wsc-club-chip">
          <span className="wsc-club-ava">
            {clubAvatarUrl ? <img src={clubAvatarUrl} alt="" /> : getClubInitials(clubName)}
          </span>
          <span>
            <span className="wsc-club-nm">{clubName}</span>
            <span className="wsc-club-ct">{clubCaption}</span>
          </span>
        </div>

        <p className="wsc-sub">{content.subtitle}</p>

        <div className="wsc-steps">
          {content.steps.map((step) => (
            <div className="wsc-step" key={step.title}>
              <div className="wsc-step-ic wsc-step-n" aria-hidden="true">{step.icon}</div>
              <div>
                <div className="wsc-step-t">{step.title}</div>
                {step.text && <div className="wsc-step-d">{step.text}</div>}
              </div>
            </div>
          ))}
        </div>

        <div className={`wsc-fact wsc-fact-${content.factTone}`}>{content.fact}</div>
      </div>

      <div className="wsc-cta">
        {/* «Секунду…» вместо TGUI-спиннера — как дверь карусели среза 1 (OnboardingFlow). */}
        <button type="button" className="rd-btn-primary" onClick={onCta} disabled={ctaPending}>
          {ctaPending ? 'Секунду…' : content.ctaLabel}
        </button>
        {content.ctaHint && <div className="wsc-hint">{content.ctaHint}</div>}
      </div>
    </div>
  );
};

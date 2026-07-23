import { FC, useState } from 'react';
import type { ProfileQuestDto } from '../../types/api';

/**
 * Шаги профиль-квеста: порядок, XP и тексты «зачем» залочены мокапом
 * docs/design/profile-completion/mockups/02-quest-card.html (PO 2026-07-22).
 * Числа зеркалят XpPolicy.QUEST_*_XP на бэке; сумма = порог уровня 2 «Свой».
 */
const QUEST_STEPS = [
  { key: 'city', name: 'Город', why: 'Найдём клубы рядом с тобой', xp: 10 },
  { key: 'interests', name: 'Интересы', why: 'По ним подберём клубы, которые твои', xp: 25 },
  { key: 'bio', name: 'О себе', why: 'Организаторы поймут, кто к ним идёт', xp: 15 },
] as const;

type StepKey = (typeof QUEST_STEPS)[number]['key'];

/** Порог уровня 2 «Свой» (кривая 50·n²) — ровно сумма XP трёх шагов. */
const QUEST_TOTAL_XP = 50;

/** localStorage-ключ: карточка свёрнута в пилюлю (переживает перезаходы). */
const FOLDED_KEY = 'profileQuestFolded';

function isDone(quest: ProfileQuestDto, key: StepKey): boolean {
  if (key === 'city') return quest.cityDone;
  if (key === 'interests') return quest.interestsDone;
  return quest.bioDone;
}

const CHECK_ICON = (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" aria-hidden="true">
    <path d="M4 12.5l5 5L20 6.5" />
  </svg>
);

/** Донат прогресса: то же розово-оранжевое кольцо, что заливка XP-бара в GamificationPanel. */
const Donut: FC<{ size: number; stroke: number; fraction: number; label?: string }> = ({ size, stroke, fraction, label }) => {
  const r = (size - stroke) / 2 - 1;
  const c = 2 * Math.PI * r;
  const gradId = `rd-qgrad-${size}`;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
      <defs>
        <linearGradient id={gradId} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#ff8a3d" />
          <stop offset="1" stopColor="#ff5e8a" />
        </linearGradient>
      </defs>
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--surface-2)" strokeWidth={stroke} />
      <circle
        cx={size / 2} cy={size / 2} r={r} fill="none"
        stroke={`url(#${gradId})`} strokeWidth={stroke} strokeLinecap="round"
        strokeDasharray={`${c * fraction} ${c}`}
        transform={`rotate(-90 ${size / 2} ${size / 2})`}
      />
      {label && (
        <text x={size / 2} y={size / 2 + 3.5} textAnchor="middle" style={{ fontSize: 10, fontWeight: 800, fill: 'var(--text)' }}>
          {label}
        </text>
      )}
    </svg>
  );
};

interface ProfileQuestCardProps {
  quest: ProfileQuestDto;
  /** Тап «Заполнить» / следующий шаг — открывает редактор профиля. */
  onFill: () => void;
}

/**
 * Карточка-квест «Прокачай профиль» (между шапкой профиля и контентом): донат прогресса,
 * чеклист трёх шагов (что/зачем/+XP), следующий шаг подсвечен с кнопкой. Сворачивается
 * в пилюлю-прогресс (fold переживает перезаходы). Показывается, пока квест не завершён.
 */
export const ProfileQuestCard: FC<ProfileQuestCardProps> = ({ quest, onFill }) => {
  const [folded, setFolded] = useState(() => localStorage.getItem(FOLDED_KEY) === '1');

  const earnedXp = QUEST_STEPS.reduce((sum, s) => sum + (isDone(quest, s.key) ? s.xp : 0), 0);
  const fraction = earnedXp / QUEST_TOTAL_XP;
  const nextStep = QUEST_STEPS.find((s) => !isDone(quest, s.key));

  const toggleFold = () => {
    // Побочный эффект — ВНЕ setState-updater'а (React требует чистые updater'ы; StrictMode гоняет их дважды)
    const next = !folded;
    localStorage.setItem(FOLDED_KEY, next ? '1' : '0');
    setFolded(next);
  };

  if (folded) {
    return (
      <button type="button" className="rd-quest-pill" onClick={toggleFold} aria-label="Развернуть квест профиля">
        <span className="rd-qp-donut"><Donut size={22} stroke={3} fraction={fraction} /></span>
        <span className="rd-qp-text">{earnedXp} / {QUEST_TOTAL_XP} XP</span>
        {nextStep && (
          <span className="rd-qp-next">Дальше: <b>{nextStep.name.toLowerCase()} +{nextStep.xp}</b></span>
        )}
        <svg className="chev" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
      </button>
    );
  }

  return (
    <div className="rd-quest rd-glass">
      <div className="rd-q-head">
        <span className="rd-q-donut"><Donut size={46} stroke={4.5} fraction={fraction} label={`${Math.round(fraction * 100)}%`} /></span>
        <div className="rd-q-titles">
          <div className="rd-q-title">Прокачай профиль</div>
          <div className="rd-q-sub"><b>{earnedXp} / {QUEST_TOTAL_XP} XP</b> до уровня 2 «Свой»</div>
        </div>
        <button type="button" className="rd-q-fold" onClick={toggleFold} aria-label="Свернуть квест профиля">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" aria-hidden="true"><path d="M6 15l6-6 6 6" /></svg>
        </button>
      </div>

      <div className="rd-q-steps">
        {QUEST_STEPS.map((step, i) => {
          const done = isDone(quest, step.key);
          const isNext = step.key === nextStep?.key;
          return (
            <div key={step.key} className={`rd-q-step${done ? ' rd-done' : ''}${isNext ? ' rd-next' : ''}`}>
              <span className="rd-q-check">{done ? CHECK_ICON : i + 1}</span>
              <div className="rd-q-step-body">
                <div className="rd-q-step-name">{step.name}</div>
                <div className="rd-q-step-why">{step.why}</div>
              </div>
              <span className="rd-q-xp">+{step.xp} XP</span>
              {isNext && (
                <button type="button" className="rd-q-cta" onClick={onFill}>Заполнить</button>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};

/** Цвета конфетти — палитра брендовых градиентов (как в мокапе). */
const CONFETTI = [
  { left: '14%', color: '#F47B3C', delay: '0s' },
  { left: '30%', color: '#E84C9A', delay: '0.5s' },
  { left: '64%', color: '#8E5DFF', delay: '0.9s' },
  { left: '82%', color: '#66D49A', delay: '1.4s' },
  { left: '48%', color: '#FFCDA8', delay: '1.9s' },
] as const;

interface ProfileQuestCongratsProps {
  /** «Уровень 2 — „Свой“!» для новичка; если уровень уже выше (квест добит позже) — нейтральный титул. */
  title: string;
  onAck: () => void;
}

/** Поздравление на месте карточки-квеста: бейдж «Визитка», конфетти, «Отлично» убирает навсегда. */
export const ProfileQuestCongrats: FC<ProfileQuestCongratsProps> = ({ title, onAck }) => (
  <div className="rd-congrats rd-glass" role="status">
    {CONFETTI.map((cf) => (
      <span key={cf.left} className="rd-cf" style={{ left: cf.left, top: 6, background: cf.color, animationDelay: cf.delay }} aria-hidden="true" />
    ))}
    <div className="rd-c-badge" aria-hidden="true">
      <svg width="30" height="30" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l2.9 6.3 6.9.7-5.2 4.6 1.5 6.8L12 16.9 5.9 20.4l1.5-6.8L2.2 9l6.9-.7z" /></svg>
    </div>
    <div className="rd-c-title">{title}</div>
    <div className="rd-c-text">
      Профиль заполнен: город, интересы и пара слов о себе. Теперь клубы и организаторы видят, кто ты.
    </div>
    <div className="rd-c-chips">
      <span className="rd-c-chip-badge">Бейдж «Визитка»</span>
      <span className="rd-c-chip-xp">+{QUEST_TOTAL_XP} XP</span>
    </div>
    <button type="button" className="rd-c-btn" onClick={onAck}>Отлично</button>
  </div>
);

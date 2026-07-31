import { FC } from 'react';
import type { ProfileQuestDto } from '../../types/api';

/** Шаг квеста = поле профиля. */
export type QuestStepKey = 'city' | 'bio' | 'interests';

/**
 * Шаги профиль-квеста — три поля одним списком.
 *
 * Карусель «один экран = один шаг» снята (решение PO 2026-07-31): человек заходит в редактор
 * и заполняет всё за один раз, а не ходит по шагам туда-обратно. Карточка теперь только
 * показывает, что осталось, — теми же тремя чекбоксами, которые он увидит в самом редакторе.
 *
 * XP остаются за полями и зеркалят XpPolicy.QUEST_*_XP на бэке; сумма = порог уровня 2 «Свой».
 * Тексты утверждены построчно — не переписывать.
 */
const QUEST_STEPS = [
  { key: 'city', name: 'Город', why: 'Найдём клубы рядом с тобой', xp: 10 },
  { key: 'bio', name: 'О себе', why: 'Чем увлекаешься?)', xp: 15 },
  { key: 'interests', name: 'Интересы', why: 'Подберём интересные клубы', xp: 25 },
] as const;

/** Порог уровня 2 «Свой» (кривая 50·n²) — ровно сумма XP трёх шагов. */
const QUEST_TOTAL_XP = 50;

/** localStorage-ключ свёрнутости; состоянием владеет ProfilePage (затенение зависит от него). */
export const QUEST_FOLDED_KEY = 'profileQuestFolded';

/** Максимум символов значения заполненного шага («Москва», отрывок био, интересы). */
const DONE_VALUE_MAX = 44;

function isDone(quest: ProfileQuestDto, key: QuestStepKey): boolean {
  if (key === 'city') return quest.cityDone;
  if (key === 'interests') return quest.interestsDone;
  return quest.bioDone;
}

function truncate(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max - 1).trimEnd()}…`;
}

/** Значения заполненных шагов — подписи под названием поля. */
export interface QuestDoneValues {
  city: string | null;
  bio: string | null;
  interests: string[];
}

function doneValueLabel(key: QuestStepKey, values: QuestDoneValues): string | null {
  if (key === 'city') return values.city;
  if (key === 'bio') return values.bio ? truncate(values.bio, DONE_VALUE_MAX) : null;
  return values.interests.length > 0 ? truncate(values.interests.join(', '), DONE_VALUE_MAX) : null;
}

/** Донат прогресса: то же розово-оранжевое кольцо, что заливка XP-бара в GamificationPanel. */
const Donut: FC<{ size: number; stroke: number; fraction: number }> = ({ size, stroke, fraction }) => {
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
    </svg>
  );
};

/**
 * Чекбокс поля: серый кружок → зелёная галочка. Один и тот же вид в карточке-квеста и в
 * редакторе профиля — по нему человек понимает, что засчитано (решение PO 2026-07-31).
 */
export const QuestCheck: FC<{ done: boolean }> = ({ done }) => (
  <span
    className={done ? 'rd-qcheck rd-qcheck-on' : 'rd-qcheck'}
    role="img"
    aria-label={done ? 'заполнено' : 'не заполнено'}
  >
    {done && (
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M20 6L9 17l-5-5" />
      </svg>
    )}
  </span>
);

interface ProfileQuestCardProps {
  quest: ProfileQuestDto;
  /** Значения заполненных шагов — строка показывает, что именно сохранено. */
  doneValues: QuestDoneValues;
  /** Свёрнутость живёт в ProfilePage: от неё же зависит затенение остальных панелей. */
  folded: boolean;
  onToggleFold: () => void;
  /** Кнопка карточки — редактор профиля целиком, без выделенных полей. */
  onFill: () => void;
}

/**
 * Карточка-квест «Прокачай профиль»: три поля одним списком и одна кнопка в редактор.
 * Чекбоксы — общий язык с редактором: здесь видно, что осталось, там галочки загораются
 * по мере заполнения.
 *
 * Карточка подсвечена пульсом — парная к затенению остальных панелей профиля в ProfilePage.
 * Пилюля-сворачивание сохранена.
 */
export const ProfileQuestCard: FC<ProfileQuestCardProps> = ({ quest, doneValues, folded, onToggleFold, onFill }) => {
  const earnedXp = QUEST_STEPS.reduce((sum, s) => sum + (isDone(quest, s.key) ? s.xp : 0), 0);
  const fraction = earnedXp / QUEST_TOTAL_XP;
  const leftCount = QUEST_STEPS.filter((s) => !isDone(quest, s.key)).length;

  if (folded) {
    return (
      <button type="button" className="rd-quest-pill" onClick={onToggleFold} aria-label="Развернуть квест профиля">
        <span className="rd-qp-donut"><Donut size={22} stroke={3} fraction={fraction} /></span>
        <span className="rd-qp-text">{earnedXp} / {QUEST_TOTAL_XP} XP</span>
        {leftCount > 0 && (
          <span className="rd-qp-next">Осталось: <b>{leftCount} из {QUEST_STEPS.length}</b></span>
        )}
        <svg className="chev" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
      </button>
    );
  }

  return (
    <div className="rd-quest rd-glass rd-q-pulse">
      <div className="rd-q-head">
        <span className="rd-q-donut" aria-hidden="true"><Donut size={38} stroke={4} fraction={fraction} /></span>
        <div className="rd-q-titles">
          <div className="rd-q-title">Прокачай профиль</div>
          <div className="rd-q-motiv">чтобы лучше подбирать клубы</div>
        </div>
        <span className="rd-qc-xp">{earnedXp} / {QUEST_TOTAL_XP} XP</span>
        <button type="button" className="rd-q-fold" onClick={onToggleFold} aria-label="Свернуть квест профиля">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" aria-hidden="true"><path d="M6 15l6-6 6 6" /></svg>
        </button>
      </div>

      <ul className="rd-qlist">
        {QUEST_STEPS.map((step) => {
          const done = isDone(quest, step.key);
          const value = done ? doneValueLabel(step.key, doneValues) : null;
          return (
            <li key={step.key} className={done ? 'rd-qrow rd-qrow-done' : 'rd-qrow'}>
              <QuestCheck done={done} />
              <span className="rd-qrow-body">
                <span className="rd-qrow-name">{step.name}</span>
                <span className="rd-qrow-why">{value ?? step.why}</span>
              </span>
              <span className={done ? 'rd-qrow-xp rd-qrow-xp-done' : 'rd-qrow-xp'}>+{step.xp} XP</span>
            </li>
          );
        })}
      </ul>

      <button type="button" className="rd-btn-primary rd-q-cta" onClick={onFill}>
        {earnedXp === 0 ? 'Заполнить профиль' : 'Дозаполнить профиль'}
      </button>
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
    <button type="button" className="rd-c-btn" onClick={onAck}>Забрать!</button>
  </div>
);

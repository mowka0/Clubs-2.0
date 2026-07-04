import { FC } from 'react';
import type { ActivityType } from '../../api/activities';

interface ActivityTypeOptionsProps {
  /** Вызывается с выбранным типом активности. Побочных эффектов здесь нет — шаг/хаптику владеет родительский flow. */
  onPick: (type: ActivityType) => void;
}

interface PickerOption {
  key: ActivityType;
  emoji: string;
  title: string;
  subtitle: string;
}

const OPTIONS: PickerOption[] = [
  {
    key: 'event',
    emoji: '🗓',
    title: 'Событие',
    subtitle: 'Встреча с датой, временем, лимитом',
  },
  {
    key: 'skladchina',
    emoji: '💰',
    title: 'Сбор',
    subtitle: 'Сбор денег на бронь / инвентарь / подарок',
  },
];

const headerStyle: React.CSSProperties = {
  padding: '8px 16px 16px',
  fontSize: 13,
  fontWeight: 600,
  letterSpacing: 0.6,
  textTransform: 'uppercase',
  color: 'var(--tgui--hint_color, rgba(255,255,255,0.55))',
};

const optionStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 14,
  width: '100%',
  padding: '14px 16px',
  background: 'transparent',
  border: 'none',
  borderTop: '1px solid var(--tgui--divider, rgba(255,255,255,0.08))',
  cursor: 'pointer',
  textAlign: 'left',
  color: 'var(--tgui--text_color, #fff)',
};

const emojiStyle: React.CSSProperties = {
  fontSize: 24,
  flex: '0 0 auto',
  width: 32,
  textAlign: 'center',
};

const textStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
};

const titleStyle: React.CSSProperties = {
  fontSize: 16,
  fontWeight: 600,
};

const subtitleStyle: React.CSSProperties = {
  fontSize: 13,
  color: 'var(--tgui--hint_color, rgba(255,255,255,0.6))',
};

/**
 * Список типов активности — только контент (без обёртки Modal). Рендерится внутри
 * единственного Modal, которым владеет CreateActivityFlow — он же управляет переходами
 * между шагами и хаптикой. То, что этот компонент чисто презентационный, избавляет от
 * ситуации, когда каждый шаг владеет своим Modal — именно это вызывало баг с
 * разрушением/схлопыванием оверлея.
 */
export const ActivityTypeOptions: FC<ActivityTypeOptionsProps> = ({ onPick }) => (
  <div style={{ paddingBottom: 8 }}>
    <div style={headerStyle}>Создать активность</div>
    {OPTIONS.map((opt) => (
      <button
        key={opt.key}
        type="button"
        style={optionStyle}
        onClick={() => onPick(opt.key)}
      >
        <span style={emojiStyle} aria-hidden="true">{opt.emoji}</span>
        <span style={textStyle}>
          <span style={titleStyle}>{opt.title}</span>
          <span style={subtitleStyle}>{opt.subtitle}</span>
        </span>
      </button>
    ))}
  </div>
);

// Показываются только уже реализованные шаблоны. По мере появления gear/booking/birthday — добавлять сюда.
export type SkladchinaTemplateKey = 'split_bill' | 'custom';

interface SkladchinaTemplateOptionsProps {
  onPick: (template: SkladchinaTemplateKey) => void;
}

const SKLADCHINA_OPTIONS: { key: SkladchinaTemplateKey; emoji: string; title: string; subtitle: string }[] = [
  {
    key: 'split_bill',
    emoji: '🧾',
    title: 'Разделить счёт',
    subtitle: 'Поделить расходы прошедшего события поровну между пришедшими',
  },
  {
    key: 'custom',
    emoji: '💰',
    title: 'Свой сбор',
    subtitle: 'Сумма, участники и сроки — вручную',
  },
];

/** Выбор шаблона, показывается после «Сбор» в flow создания. Только контент (без обёртки Modal). */
export const SkladchinaTemplateOptions: FC<SkladchinaTemplateOptionsProps> = ({ onPick }) => (
  <div style={{ paddingBottom: 8 }}>
    <div style={headerStyle}>Тип сбора</div>
    {SKLADCHINA_OPTIONS.map((opt) => (
      <button key={opt.key} type="button" style={optionStyle} onClick={() => onPick(opt.key)}>
        <span style={emojiStyle} aria-hidden="true">{opt.emoji}</span>
        <span style={textStyle}>
          <span style={titleStyle}>{opt.title}</span>
          <span style={subtitleStyle}>{opt.subtitle}</span>
        </span>
      </button>
    ))}
  </div>
);

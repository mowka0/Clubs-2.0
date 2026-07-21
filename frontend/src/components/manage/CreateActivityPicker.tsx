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

interface PickerListOption<K extends string> {
  key: K;
  emoji: string;
  title: string;
  subtitle: string;
}

/**
 * Общий шаг-список flow создания — только контент (без обёртки Modal). Рендерится внутри
 * единственного Modal, которым владеет CreateActivityFlow — он же управляет переходами
 * между шагами и хаптикой. То, что шаги чисто презентационные, избавляет от ситуации,
 * когда каждый шаг владеет своим Modal — именно это вызывало баг с разрушением/схлопыванием
 * оверлея. Один компонент на все шаги (тип/шаблон сбора/формат события): правка разметки
 * пункта меняет все шаги синхронно.
 */
function PickerOptionList<K extends string>({ header, options, onPick }: {
  header: string;
  options: PickerListOption<K>[];
  onPick: (key: K) => void;
}) {
  return (
    <div style={{ paddingBottom: 8 }}>
      <div style={headerStyle}>{header}</div>
      {options.map((opt) => (
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
}

export const ActivityTypeOptions: FC<ActivityTypeOptionsProps> = ({ onPick }) => (
  <PickerOptionList header="Создать активность" options={OPTIONS} onPick={onPick} />
);

// Формат события (решение PO 2026-07-21): «с местами» — классика с лимитом, гонкой за места и
// листом ожидания; «открытая встреча» — без лимита (participantLimit = null на бэке), приходят
// все подтвердившие, формат целиком вне репутации. Тот же движок, разный контракт.
export type EventFormatKey = 'limited' | 'open';

const EVENT_FORMAT_OPTIONS: { key: EventFormatKey; emoji: string; title: string; subtitle: string }[] = [
  {
    key: 'limited',
    emoji: '🎟',
    title: 'С местами',
    subtitle: 'Лимит участников, гонка за места и лист ожидания',
  },
  {
    key: 'open',
    emoji: '🌊',
    title: 'Открытая встреча',
    subtitle: 'Без лимита и вне репутации — приходят все желающие',
  },
];

interface EventFormatOptionsProps {
  onPick: (format: EventFormatKey) => void;
}

/** Выбор формата, показывается после «Событие» в flow создания (зеркалит шаг «Тип сбора»). */
export const EventFormatOptions: FC<EventFormatOptionsProps> = ({ onPick }) => (
  <PickerOptionList header="Формат события" options={EVENT_FORMAT_OPTIONS} onPick={onPick} />
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
  <PickerOptionList header="Тип сбора" options={SKLADCHINA_OPTIONS} onPick={onPick} />
);

import { FC, useState } from 'react';
import type { ActivityType } from '../../api/activities';
import type { EventTemplateDto } from '../../api/eventTemplates';

interface ActivityTypeOptionsProps {
  /** Вызывается с выбранным типом активности. Побочных эффектов здесь нет — шаг/хаптику владеет родительский flow. */
  onPick: (type: ActivityType) => void;
  /** Пункт «Сообщить о проблеме» — единственный, доступный и не-организаторам. */
  onPickFeedback: () => void;
  /** Организует ли пользователь хотя бы один клуб — без этого пункты создания скрыты. */
  canCreate: boolean;
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

type TypePickKey = ActivityType | 'feedback';

// Обратная связь живёт в том же шите «+», но это не активность: пункт ведёт на форму
// баг-репорта (/feedback), а не в flow создания. Доступен всем пользователям.
const FEEDBACK_OPTION: PickerListOption<TypePickKey> = {
  key: 'feedback',
  emoji: '🐞',
  title: 'Сообщить о проблеме',
  subtitle: 'Баг или идея — сообщение уйдёт команде Clubs',
};

export const ActivityTypeOptions: FC<ActivityTypeOptionsProps> = ({ onPick, onPickFeedback, canCreate }) => {
  // Не-организатору создание недоступно (роль бывшего тоста-гардрейла теперь у состава
  // пунктов): шит вырождается в один пункт обратной связи с честным заголовком.
  const options: PickerListOption<TypePickKey>[] = canCreate ? [...OPTIONS, FEEDBACK_OPTION] : [FEEDBACK_OPTION];
  return (
    <PickerOptionList
      header={canCreate ? 'Создать активность' : 'Обратная связь'}
      options={options}
      onPick={(key) => (key === 'feedback' ? onPickFeedback() : onPick(key))}
    />
  );
};

// Формат события (решения PO 2026-07-21 и 2026-07-23): «с местами» — классика с лимитом,
// гонкой за места и листом ожидания; «срочная» — то же с местами, но БЕЗ Этапа 1 (рождается
// сразу в подтверждении мест, для встреч в ближайшие часы); «открытая встреча» — без лимита
// (participantLimit = null на бэке), целиком вне репутации. Один движок, разные контракты.
export type EventFormatKey = 'limited' | 'open' | 'urgent';

const EVENT_FORMAT_OPTIONS: { key: EventFormatKey; emoji: string; title: string; subtitle: string }[] = [
  {
    key: 'limited',
    emoji: '🎟',
    title: 'С местами',
    subtitle: 'Лимит участников, репутация и лист ожидания',
  },
  {
    key: 'urgent',
    emoji: '⚡️',
    title: 'Срочная встреча',
    subtitle: 'В ближайшие часы: без голосования — сразу подтверждение мест',
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
  /**
   * Сколько сохранённых шаблонов доступно вызывающему. 0 — пункт «Готовые шаблоны» не
   * показывается вовсе: пустой список хуже отсутствующего пункта.
   */
  templateCount: number;
  onPickTemplates: () => void;
}

// Ключ пункта «Готовые шаблоны» на шаге формата. Формат встречи шаблон несёт сам, поэтому
// пункт не выбирает формат, а уводит на список — отсюда отдельный ключ вне EventFormatKey.
type FormatStepKey = EventFormatKey | 'templates';

/** Выбор формата, показывается после «Событие» в flow создания (зеркалит шаг «Тип сбора»). */
export const EventFormatOptions: FC<EventFormatOptionsProps> = ({
  onPick,
  templateCount,
  onPickTemplates,
}) => {
  const options: PickerListOption<FormatStepKey>[] =
    templateCount > 0
      ? [
          {
            key: 'templates',
            emoji: '📋',
            title: `Готовые шаблоны · ${templateCount}`,
            subtitle: 'Сохранённые встречи — заполнят форму, останется поправить',
          },
          ...EVENT_FORMAT_OPTIONS,
        ]
      : EVENT_FORMAT_OPTIONS;

  return (
    <PickerOptionList
      header="Формат события"
      options={options}
      onPick={(key) => (key === 'templates' ? onPickTemplates() : onPick(key))}
    />
  );
};

interface EventTemplateOptionsProps {
  templates: EventTemplateDto[];
  onPick: (template: EventTemplateDto) => void;
  onRename: (template: EventTemplateDto) => void;
  onDelete: (template: EventTemplateDto) => void;
  isDeleting: boolean;
}

// Подпись формата в строке шаблона — те же ярлыки, что у карточек лент и страницы встречи.
function formatLabel(template: EventTemplateDto): string {
  if (template.isOpenEvent) return '🌊 открытая';
  if (template.isUrgentEvent) return '⚡️ срочная';
  return `🎟 ${template.participantLimit} мест`;
}

const WEEKDAY_SHORT = ['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс'];

/** «вт 19:00» — что именно подставится в дату; пусто, если день недели у шаблона не задан. */
function scheduleLabel(template: EventTemplateDto): string {
  if (template.defaultWeekday === null) return '';
  const day = WEEKDAY_SHORT[template.defaultWeekday - 1] ?? '';
  const time = template.defaultTime?.slice(0, 5) ?? '';
  return time ? `${day} ${time}` : day;
}

const templateRowStyle: React.CSSProperties = {
  ...optionStyle,
  gap: 10,
};

const templateActionStyle: React.CSSProperties = {
  flex: '0 0 auto',
  padding: '6px 8px',
  background: 'transparent',
  border: 'none',
  cursor: 'pointer',
  fontSize: 18,
  lineHeight: 1,
};

/**
 * Список сохранённых шаблонов с режимом правки. Переключатель «Изменить» в шапке вместо
 * «⋯» на строке: любое всплывающее меню здесь означало бы ВЛОЖЕННЫЙ Modal, а закрывающийся
 * вложенный сносит общий portal/scroll-lock оверлей (см. CreateActivityFlow).
 *
 * Подтверждение удаления тоже инлайновое, по той же причине.
 */
export const EventTemplateOptions: FC<EventTemplateOptionsProps> = ({
  templates,
  onPick,
  onRename,
  onDelete,
  isDeleting,
}) => {
  const [editing, setEditing] = useState(false);
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null);

  return (
    <div style={{ paddingBottom: 8 }}>
      <div style={{ ...headerStyle, display: 'flex', justifyContent: 'space-between', gap: 12 }}>
        <span>Готовые шаблоны</span>
        <button
          type="button"
          style={{
            background: 'transparent',
            border: 'none',
            padding: 0,
            font: 'inherit',
            textTransform: 'none',
            letterSpacing: 'normal',
            color: 'var(--tgui--link_color, #6ab3f3)',
            cursor: 'pointer',
          }}
          onClick={() => {
            setPendingDeleteId(null);
            setEditing((v) => !v);
          }}
        >
          {editing ? 'Готово' : 'Изменить'}
        </button>
      </div>

      {templates.map((template) => {
        const schedule = scheduleLabel(template);
        const isPendingDelete = pendingDeleteId === template.id;

        if (isPendingDelete) {
          return (
            <div key={template.id} style={templateRowStyle}>
              <span style={textStyle}>
                <span style={titleStyle}>Удалить «{template.name}»?</span>
                <span style={subtitleStyle}>Встречи, созданные по нему, останутся на месте</span>
              </span>
              <button
                type="button"
                style={{ ...templateActionStyle, fontSize: 14, color: 'var(--tgui--destructive_text_color, #e53935)' }}
                disabled={isDeleting}
                onClick={() => onDelete(template)}
              >
                Удалить
              </button>
              <button
                type="button"
                style={{ ...templateActionStyle, fontSize: 14 }}
                onClick={() => setPendingDeleteId(null)}
              >
                Отмена
              </button>
            </div>
          );
        }

        return (
          <div key={template.id} style={templateRowStyle}>
            <button
              type="button"
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 14,
                flex: '1 1 auto',
                minWidth: 0,
                padding: 0,
                background: 'transparent',
                border: 'none',
                cursor: 'pointer',
                textAlign: 'left',
                color: 'inherit',
              }}
              onClick={() => (editing ? onRename(template) : onPick(template))}
            >
              <span style={emojiStyle} aria-hidden="true">📋</span>
              <span style={{ ...textStyle, minWidth: 0 }}>
                <span style={titleStyle}>{template.name}</span>
                <span style={subtitleStyle}>
                  {[template.clubName, formatLabel(template), schedule].filter(Boolean).join(' · ')}
                </span>
              </span>
            </button>
            {editing && (
              <>
                <button
                  type="button"
                  style={templateActionStyle}
                  aria-label={`Переименовать ${template.name}`}
                  onClick={() => onRename(template)}
                >
                  ✏️
                </button>
                <button
                  type="button"
                  style={templateActionStyle}
                  aria-label={`Удалить ${template.name}`}
                  onClick={() => setPendingDeleteId(template.id)}
                >
                  🗑
                </button>
              </>
            )}
          </div>
        );
      })}
    </div>
  );
};

interface EventTemplateRenameStepProps {
  template: EventTemplateDto;
  onSubmit: (name: string) => void;
  onCancel: () => void;
  isSaving: boolean;
  error: string | null;
}

/**
 * Переименование шаблона — подшаг внутри того же Modal (вложенных Modal здесь быть не может,
 * см. EventTemplateOptions). Содержимое шаблона правится не тут, а через «Обновить шаблон»
 * в форме создания: отдельная форма правки означала бы третью копию полей встречи.
 */
export const EventTemplateRenameStep: FC<EventTemplateRenameStepProps> = ({
  template,
  onSubmit,
  onCancel,
  isSaving,
  error,
}) => {
  const [name, setName] = useState(template.name);
  const trimmed = name.trim();

  return (
    <div style={{ padding: '8px 16px 16px' }}>
      <div style={{ ...headerStyle, padding: '0 0 12px' }}>Название шаблона</div>
      <input
        className="rd-input"
        value={name}
        maxLength={60}
        autoFocus
        onChange={(e) => setName(e.target.value)}
      />
      {error && <div className="rd-error" style={{ marginTop: 10 }}>{error}</div>}
      <div style={{ display: 'flex', gap: 10, marginTop: 14 }}>
        <button type="button" className="rd-btn-outline" style={{ flex: 1 }} onClick={onCancel}>
          Отмена
        </button>
        <button
          type="button"
          className="rd-btn-primary"
          style={{ flex: 1 }}
          disabled={isSaving || trimmed.length === 0 || trimmed === template.name}
          onClick={() => onSubmit(trimmed)}
        >
          {isSaving ? 'Сохраняем…' : 'Сохранить'}
        </button>
      </div>
    </div>
  );
};

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

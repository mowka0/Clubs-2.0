import { FC, ReactNode, useState } from 'react';
import type { ActivityType } from '../../api/activities';
import type { EventTemplateDto } from '../../api/eventTemplates';
import type { EventFormat } from '../../types/api';

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

// Штриховые иконки вместо цветных эмодзи там, где символ — управляющий элемент, а не содержание:
// эмодзи ✏️/🗑 рисуются шрифтом платформы, из-за чего в тёмном шите выглядели инородными пятнами
// (правка PO 2026-08-11). `currentColor` даёт им цвет строки — в том числе красный у удаления.
const ChevronLeftIcon: FC = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M15 18l-6-6 6-6" />
  </svg>
);

const PencilIcon: FC = () => (
  <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M12 20h9" />
    <path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z" />
  </svg>
);

const TrashIcon: FC = () => (
  <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6" />
    <path d="M10 11v6M14 11v6" />
  </svg>
);

const TemplateIcon: FC = () => (
  <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="4" y="3" width="16" height="18" rx="2.5" />
    <path d="M9 3h6v3H9z" />
    <path d="M8 12h8M8 16h5" />
  </svg>
);

interface PickerStepHeaderProps {
  /** Не задан — шаг первый, возвращаться некуда. */
  onBack?: () => void;
  /** Правый слот: у списка шаблонов — переключатель режима правки. */
  action?: ReactNode;
}

/**
 * Шапка шага: «Назад» слева, необязательное действие справа. Кнопка «Назад» есть у каждого
 * шага, кроме первого — до неё выйти из подшага можно было только закрыв весь шит.
 *
 * Заголовка шага («Формат события», «Готовые шаблоны») тут НЕТ намеренно — решение PO
 * 2026-08-11: подписи дублировали то, что и так читается по самим пунктам. Когда управлять
 * нечем (первый шаг), шапка не рендерится вовсе и список начинается сразу.
 */
export const PickerStepHeader: FC<PickerStepHeaderProps> = ({ onBack, action }) => {
  if (!onBack && !action) return null;
  return (
    <div className="rd-pick-head">
      {onBack && (
        <button type="button" className="rd-pick-back" onClick={onBack}>
          <ChevronLeftIcon />
          Назад
        </button>
      )}
      {action}
    </div>
  );
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
function PickerOptionList<K extends string>({ options, onPick, onBack }: {
  options: PickerListOption<K>[];
  onPick: (key: K) => void;
  onBack?: () => void;
}) {
  return (
    <div className="rd-pick">
      <PickerStepHeader onBack={onBack} />
      {options.map((opt) => (
        <div className="rd-pick-item" key={opt.key}>
          <button type="button" className="rd-pick-row" onClick={() => onPick(opt.key)}>
            <span className="rd-pick-ic" aria-hidden="true">{opt.emoji}</span>
            <span className="rd-pick-txt">
              <b>{opt.title}</b>
              <span>{opt.subtitle}</span>
            </span>
          </button>
        </div>
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
      options={options}
      onPick={(key) => (key === 'feedback' ? onPickFeedback() : onPick(key))}
    />
  );
};

// Формат события (решение PO 2026-08-31) — ответ на один вопрос «сколько человек нужно».
// Числа на этом шаге ещё нет, поэтому карточки названы самим правилом, а не «не меньше 6»:
// конкретика появляется в форме и на бейджах, где лимит уже выбран.
const EVENT_FORMAT_OPTIONS: { key: EventFormat; emoji: string; title: string; subtitle: string }[] = [
  {
    key: 'min',
    emoji: '🎯',
    title: 'Минимум участников',
    subtitle: 'Собираемся, если наберётся нужное число. Не наберём — встреча отменится',
  },
  {
    key: 'max',
    emoji: '🎟',
    title: 'Максимум участников',
    subtitle: 'Встреча будет в любом случае, но мест ограниченное число',
  },
  {
    key: 'any',
    emoji: '🌊',
    title: 'Сколько придёт',
    subtitle: 'Без ограничений и без обязательств — приходят все',
  },
];

interface EventFormatOptionsProps {
  onPick: (format: EventFormat) => void;
  /**
   * Сколько сохранённых шаблонов доступно вызывающему. 0 — пункт «Готовые шаблоны» не
   * показывается вовсе: пустой список хуже отсутствующего пункта.
   */
  templateCount: number;
  onPickTemplates: () => void;
  onBack: () => void;
}

// Ключ пункта «Готовые шаблоны» на шаге формата. Формат встречи шаблон несёт сам, поэтому
// пункт не выбирает формат, а уводит на список — отсюда отдельный ключ вне EventFormat.
type FormatStepKey = EventFormat | 'templates';

/** Выбор формата, показывается после «Событие» в flow создания (зеркалит шаг «Тип сбора»). */
export const EventFormatOptions: FC<EventFormatOptionsProps> = ({
  onPick,
  templateCount,
  onPickTemplates,
  onBack,
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
      options={options}
      onBack={onBack}
      onPick={(key) => (key === 'templates' ? onPickTemplates() : onPick(key))}
    />
  );
};

interface EventTemplateOptionsProps {
  templates: EventTemplateDto[];
  onPick: (template: EventTemplateDto) => void;
  /** Карандаш и тап по строке в режиме правки ведут на полную правку шаблона. */
  onEdit: (template: EventTemplateDto) => void;
  onDelete: (template: EventTemplateDto) => void;
  onBack: () => void;
  isDeleting: boolean;
}

// Подпись формата в строке шаблона — СЛОВАМИ, без эмодзи: цветной 🎟 внутри приглушённой
// строки метаданных рисовался платформенным шрифтом (ярко-красный «ADMIT ONE») и выбивался
// из строки (правка PO 2026-08-11). Эмодзи-ярлыки форматов остались там, где они и были
// задуманы, — на карточках лент и в шапке страницы встречи.
function formatLabel(template: EventTemplateDto): string {
  switch (template.format) {
    case 'min': return `не меньше ${template.participantLimit}`;
    case 'max': return `не больше ${template.participantLimit}`;
    case 'any': return 'без лимита';
  }
}

const WEEKDAY_SHORT = ['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс'];

/** «вт 19:00» — что именно подставится в дату; пусто, если день недели у шаблона не задан. */
function scheduleLabel(template: EventTemplateDto): string {
  if (template.defaultWeekday === null) return '';
  const day = WEEKDAY_SHORT[template.defaultWeekday - 1] ?? '';
  const time = template.defaultTime?.slice(0, 5) ?? '';
  return time ? `${day} ${time}` : day;
}

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
  onEdit,
  onDelete,
  onBack,
  isDeleting,
}) => {
  const [editing, setEditing] = useState(false);
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null);

  return (
    <div className="rd-pick">
      <PickerStepHeader
        onBack={onBack}
        action={
          templates.length > 0 ? (
            <button
              type="button"
              className={editing ? 'rd-pick-act rd-active' : 'rd-pick-act'}
              onClick={() => {
                setPendingDeleteId(null);
                setEditing((v) => !v);
              }}
            >
              {editing ? 'Готово' : 'Изменить'}
            </button>
          ) : undefined
        }
      />

      {templates.length === 0 && (
        <div className="rd-pick-empty">Шаблонов не осталось — «Назад», чтобы создать встречу с нуля.</div>
      )}

      {templates.map((template) => {
        if (pendingDeleteId === template.id) {
          return (
            <div className="rd-pick-item" key={template.id}>
              <div className="rd-pick-confirm">
                <span className="rd-pick-txt">
                  <b>Удалить «{template.name}»?</b>
                  <span>Созданные по нему встречи останутся</span>
                </span>
                <span className="rd-pick-confirm-actions">
                  <button
                    type="button"
                    className="rd-pick-confirm-btn rd-danger"
                    disabled={isDeleting}
                    onClick={() => onDelete(template)}
                  >
                    Удалить
                  </button>
                  <button
                    type="button"
                    className="rd-pick-confirm-btn"
                    onClick={() => setPendingDeleteId(null)}
                  >
                    Отмена
                  </button>
                </span>
              </div>
            </div>
          );
        }

        const meta = [template.clubName, formatLabel(template), scheduleLabel(template)]
          .filter(Boolean)
          .join(' · ');

        return (
          <div className="rd-pick-item" key={template.id}>
            <button
              type="button"
              className="rd-pick-row"
              onClick={() => (editing ? onEdit(template) : onPick(template))}
            >
              <span className="rd-pick-ic rd-pick-ic-accent" aria-hidden="true"><TemplateIcon /></span>
              <span className="rd-pick-txt">
                <b>{template.name}</b>
                <span className="rd-pick-meta">{meta}</span>
              </span>
            </button>
            {editing && (
              <>
                <button
                  type="button"
                  className="rd-pick-iconbtn"
                  aria-label={`Изменить ${template.name}`}
                  onClick={() => onEdit(template)}
                >
                  <PencilIcon />
                </button>
                <button
                  type="button"
                  className="rd-pick-iconbtn rd-danger"
                  aria-label={`Удалить ${template.name}`}
                  onClick={() => setPendingDeleteId(template.id)}
                >
                  <TrashIcon />
                </button>
              </>
            )}
          </div>
        );
      })}
    </div>
  );
};

// Показываются только уже реализованные шаблоны. По мере появления gear/booking/birthday — добавлять сюда.
export type SkladchinaTemplateKey = 'split_bill' | 'custom';

interface SkladchinaTemplateOptionsProps {
  onPick: (template: SkladchinaTemplateKey) => void;
  onBack: () => void;
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
export const SkladchinaTemplateOptions: FC<SkladchinaTemplateOptionsProps> = ({ onPick, onBack }) => (
  <PickerOptionList options={SKLADCHINA_OPTIONS} onPick={onPick} onBack={onBack} />
);

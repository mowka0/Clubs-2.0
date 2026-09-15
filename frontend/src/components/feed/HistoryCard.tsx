import { FC } from 'react';

interface HistoryCardProps {
  dateISO: string;
  title: string;
  subtitle: string;
  /** Что это заактивность — маркер справа, тем же словарём, что в ленте клуба. */
  kind: ActivityKind;
  onClick: () => void;
}

type ActivityKind = 'event' | 'skladchina';

// Две иконки на всю историю, как в «Прошедших» клуба (PO 2026-09-14): встреча и сбор.
const KIND_ICON: Record<ActivityKind, string> = { event: '📅', skladchina: '💰' };
const KIND_LABEL: Record<ActivityKind, string> = { event: 'Встреча', skladchina: 'Сбор' };

// Дата-плитка: день числом + месяц в родительном падеже («15» + «июля»). Родительный
// падеж отдаёт ru-RU только когда день и месяц форматируются вместе, поэтому берём один
// форматтер «день + месяц» и разбиваем результат на части через formatToParts —
// отдельный { month: 'long' } дал бы именительный «июль».
const HIST_DATE_FMT = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long' });

function formatHistDate(iso: string): { day: string; month: string } {
  const parts = HIST_DATE_FMT.formatToParts(new Date(iso));
  const day = parts.find((p) => p.type === 'day')?.value ?? '';
  const month = parts.find((p) => p.type === 'month')?.value ?? '';
  return { day, month };
}

/**
 * Компактная строка истории (вариант B из мокапа history-visual-variants.html):
 * узкая дата-плитка слева, название и подстрока справа. Презентационный компонент —
 * ничего не знает про события/сборы, только рендерит переданные дату, заголовок и подпись.
 */
export const HistoryCard: FC<HistoryCardProps> = ({ dateISO, title, subtitle, kind, onClick }) => {
  const { day, month } = formatHistDate(dateISO);

  return (
    <button type="button" className="rd-hist-row" onClick={onClick}>
      <div className="rd-hist-date">
        <div className="rd-hist-day">{day}</div>
        <div className="rd-hist-month">{month}</div>
      </div>
      <div className="rd-hist-body">
        <div className="rd-hist-title">{title}</div>
        <div className="rd-hist-sub">{subtitle}</div>
      </div>
      {/* Эмодзи вне доступного имени кнопки: его читает title рядом, а не «земля-шар-глобус». */}
      <span className="rd-hist-kind" title={KIND_LABEL[kind]}>
        <span aria-hidden="true">{KIND_ICON[kind]}</span>
        <span className="rd-sr-only">{KIND_LABEL[kind]}</span>
      </span>
    </button>
  );
};

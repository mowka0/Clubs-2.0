import { FC } from 'react';

interface HistoryCardProps {
  dateISO: string;
  title: string;
  subtitle: string;
  onClick: () => void;
}

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
export const HistoryCard: FC<HistoryCardProps> = ({ dateISO, title, subtitle, onClick }) => {
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
    </button>
  );
};

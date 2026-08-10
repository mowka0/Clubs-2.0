import { FC, ReactNode } from 'react';
import type { PlaceSuggestion, SuggestKind } from '../../utils/yandexMaps';

// Иконка строки подсказки по типу объекта (заведение / дом / улица / прочее). Рубрику
// («кальян-бар», «гостиница») Геосаджест отдаёт только текстом в subtitle — своя иконка
// на каждую рубрику потребовала бы словаря на сотни значений, поэтому иконок ровно четыре.
const KIND_ICON: Record<SuggestKind, string> = {
  business: '🏢',
  house: '🏠',
  street: '🛣',
  other: '📍',
};

// Минимальная длина слова запроса, которое подсвечиваем в заголовке подсказки: предлоги
// и одиночные буквы совпадают почти со всем и превращают подсветку в шум.
const MIN_HIGHLIGHT_LENGTH = 3;

/** Экранирование строки для вставки в регулярное выражение (запрос вводит пользователь). */
function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Подсветка слов запроса в заголовке подсказки — акцентом, как в мокапе. */
function highlightQuery(text: string, query: string): ReactNode {
  const words = query
    .toLowerCase()
    .split(/[\s,]+/)
    .filter((word) => word.length >= MIN_HIGHLIGHT_LENGTH)
    .map(escapeRegExp);
  if (words.length === 0) return text;
  // Группа захвата в split() оставляет совпадения в результате — они встают на нечётные позиции.
  const parts = text.split(new RegExp(`(${words.join('|')})`, 'ig'));
  return parts.map((part, index) => (index % 2 === 1 ? <i key={index}>{part}</i> : part));
}

interface PlaceSuggestionListProps {
  items: PlaceSuggestion[];
  /** Запрос, которому отвечает список — по нему подсвечиваются совпадения. */
  query: string;
  /** uri строки, по которой сейчас едет запрос координат: список на это время заблокирован. */
  resolvingUri: string | null;
  onPick: (item: PlaceSuggestion) => void;
}

/**
 * Список подсказок мест (venue-search, вариант C): шторка поверх карты. Заведения и адреса
 * идут вперемешку одним списком — без вкладок и переключателей (решение PO 2026-08-10).
 */
export const PlaceSuggestionList: FC<PlaceSuggestionListProps> = ({
  items,
  query,
  resolvingUri,
  onPick,
}) => (
  <div className="rd-geo-sugg" aria-label="Найденные места">
    {items.map((item) => (
      <button
        key={item.uri}
        type="button"
        className="rd-geo-sugg-row"
        disabled={resolvingUri !== null}
        onClick={() => onPick(item)}
      >
        <span
          className={item.kind === 'business' ? 'rd-geo-sugg-ic biz' : 'rd-geo-sugg-ic'}
          aria-hidden="true"
        >
          {resolvingUri === item.uri ? '…' : (KIND_ICON[item.kind] ?? KIND_ICON.other)}
        </span>
        <span className="rd-geo-sugg-txt">
          <b>{highlightQuery(item.title, query)}</b>
          <span>{item.subtitle}</span>
        </span>
      </button>
    ))}
  </div>
);

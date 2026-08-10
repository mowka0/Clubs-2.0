import { useState } from 'react';
import { useHaptic } from '../../hooks/useHaptic';
import {
  GeocoderHttpError,
  resolveSuggestion,
  suggestPlaces,
  type GeoPoint,
  type PlaceSuggestion,
} from '../../utils/yandexMaps';

// Минимальная длина запроса — ровно та, что принимает `/api/geo/suggest` (q: 3..200).
// Короче отсекаем на клиенте: иначе 400 превратился бы в «поиск временно недоступен».
const MIN_QUERY_LENGTH = 3;

/** Выбранное место: точка на карте + готовая строка для location_text. */
export interface ChosenPlace {
  point: GeoPoint;
  text: string;
}

/**
 * Что уходит в location_text. У заведения смысл несёт название, а адрес — контекст; у дома
 * и улицы название и есть адрес. Собираем из ответа саджеста, а НЕ из геокодера: геокодер
 * по uri организации возвращает адресом её же название («Россия, …, Иваново, Old Friends»).
 */
function buildLocationText(item: PlaceSuggestion): string {
  if (item.kind === 'business' && item.address) return `${item.title}, ${item.address}`;
  return item.address || item.title;
}

/**
 * HTTP-ошибка (503 «саджест недоступен», исчерпанный лимит, ненастроенный ключ) — это не
 * проблема связи, и звать пользователя проверять сеть незачем.
 */
function describeError(e: unknown, retryText: string): string {
  return e instanceof GeocoderHttpError ? 'Поиск временно недоступен, попробуйте позже.' : retryText;
}

interface UsePlaceSearchOptions {
  /** Клуб встречи: по его городу бэкенд сужает выдачу подсказок. */
  clubId?: string;
  /** Координаты выбранной подсказки готовы — пикеру пора перелететь к точке. */
  onPicked: (point: GeoPoint) => void;
}

/**
 * Поиск места по подсказкам Геосаджеста (venue-search): один запрос на нажатие лупы,
 * второй — на выбор строки (координат в подсказках нет). Результат живёт в состоянии:
 * пока текст запроса не изменился, повторная лупа открывает тот же список без запроса.
 */
export function usePlaceSearch({ clubId, onPicked }: UsePlaceSearchOptions) {
  const haptic = useHaptic();
  const [query, setQuery] = useState('');
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // null = ещё не искали, пустой массив = искали и не нашли (это разные экраны).
  const [suggestions, setSuggestions] = useState<PlaceSuggestion[] | null>(null);
  const [suggestedFor, setSuggestedFor] = useState('');
  const [listOpen, setListOpen] = useState(false);
  const [resolvingUri, setResolvingUri] = useState<string | null>(null);
  const [chosen, setChosen] = useState<ChosenPlace | null>(null);

  const search = async () => {
    const trimmed = query.trim();
    if (!trimmed || searching) return;
    haptic.impact('light');
    if (trimmed.length < MIN_QUERY_LENGTH) {
      setError(`Введите хотя бы ${MIN_QUERY_LENGTH} символа.`);
      return;
    }
    if (suggestions && trimmed === suggestedFor) {
      setListOpen(suggestions.length > 0);
      return;
    }
    setSearching(true);
    setError(null);
    try {
      const found = await suggestPlaces(trimmed, clubId);
      setSuggestions(found);
      setSuggestedFor(trimmed);
      setListOpen(found.length > 0);
    } catch (e) {
      setSuggestions(null);
      setListOpen(false);
      setError(describeError(e, 'Не получилось найти место — проверьте соединение и попробуйте ещё раз.'));
    } finally {
      setSearching(false);
    }
  };

  const pick = async (item: PlaceSuggestion) => {
    if (resolvingUri) return;
    haptic.impact('light');
    setResolvingUri(item.uri);
    setError(null);
    try {
      const result = await resolveSuggestion(item.uri);
      if (!result) {
        setError('Не удалось определить координаты места — выберите другое или поставьте точку вручную.');
        return;
      }
      setChosen({ point: result.point, text: buildLocationText(item) });
      setListOpen(false);
      onPicked(result.point);
    } catch (e) {
      setError(describeError(e, 'Не получилось открыть место — проверьте соединение и попробуйте ещё раз.'));
    } finally {
      setResolvingUri(null);
    }
  };

  return {
    query,
    setQuery,
    searching,
    error,
    suggestions,
    suggestedFor,
    listOpen,
    closeList: () => setListOpen(false),
    resolvingUri,
    chosen,
    /** Искали и не нашли — показываем строку «ничего не нашли» вместо шторки. */
    nothingFound: suggestions !== null && suggestions.length === 0,
    search,
    pick,
  };
}

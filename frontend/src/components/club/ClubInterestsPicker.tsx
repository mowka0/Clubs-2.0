import { FC, useEffect, useState } from 'react';
import { useCategoryInterestsQuery, useInterestSuggestQuery } from '../../queries/interests';
import { useHaptic } from '../../hooks/useHaptic';
import { MAX_CLUB_INTERESTS, normalizeInterest } from '../../utils/interests';

interface ClubInterestsPickerProps {
  /** Выбранная полка — по ней приходят чипы. Пустая строка = категория ещё не выбрана. */
  category: string;
  value: string[];
  onChange: (next: string[]) => void;
}

/**
 * Разметка клуба темами: чипы полки → поиск по словарю → своя тема последним шагом.
 *
 * Порядок неслучаен. По темам клуба ищут ДРУГИЕ люди, поэтому свободный ввод здесь —
 * запасной выход, а не главный путь: иначе словарь зарастает синонимами («настолки» /
 * «настольные игры» / «боардгеймы» тремя строками), и клуб теряется в выдаче. В профиле
 * наоборот — там свободный ввод основной, цена ошибки ниже (club-interests.md).
 */
export const ClubInterestsPicker: FC<ClubInterestsPickerProps> = ({ category, value, onChange }) => {
  const haptic = useHaptic();
  const [search, setSearch] = useState('');
  const [debounced, setDebounced] = useState('');

  useEffect(() => {
    const t = setTimeout(() => setDebounced(search), 250);
    return () => clearTimeout(t);
  }, [search]);

  const chipsQuery = useCategoryInterestsQuery(category);
  const suggestQuery = useInterestSuggestQuery(debounced);

  const atMax = value.length >= MAX_CLUB_INTERESTS;
  const query = normalizeInterest(debounced);

  const add = (raw: string) => {
    const name = normalizeInterest(raw);
    if (!name || atMax || value.includes(name)) return;
    haptic.select();
    onChange([...value, name]);
    setSearch('');
  };

  const remove = (name: string) => {
    haptic.select();
    onChange(value.filter((i) => i !== name));
  };

  // Чипы полки за вычетом уже выбранных — выбранные показаны отдельной строкой выше.
  const shelfChips = (chipsQuery.data ?? []).filter((name) => !value.includes(name));
  const found = (suggestQuery.data ?? [])
    .filter((name) => !value.includes(name) && !shelfChips.includes(name))
    .slice(0, 8);
  // «Добавить своё» — только когда точного совпадения в словаре нет: иначе человек создавал бы
  // дубль темы, которая уже существует строкой ниже.
  const canAddOwn =
    query.length >= 2 && !value.includes(query) && ![...shelfChips, ...found].includes(query);

  return (
    <div className="ci-picker">
      <span className="rd-label">Темы клуба (необязательно)</span>
      <p className="ci-hint">По ним вас найдут в поиске. Чем точнее, тем лучше.</p>

      {value.length > 0 && (
        <div className="ci-selected">
          {value.map((name) => (
            <button
              key={name}
              type="button"
              className="ci-chip ci-chip--on"
              onClick={() => remove(name)}
              aria-label={`Убрать тему ${name}`}
            >
              {name}
              <span className="ci-x" aria-hidden="true">×</span>
            </button>
          ))}
        </div>
      )}

      {!atMax && (
        <>
          {shelfChips.length > 0 && (
            <div className="ci-shelf">
              {shelfChips.map((name) => (
                <button
                  key={name}
                  type="button"
                  className="ci-chip"
                  onClick={() => add(name)}
                  aria-label={`Добавить тему ${name}`}
                >
                  {name}
                </button>
              ))}
            </div>
          )}

          <input
            className="rd-input ci-search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            // Найденное рисуется ПОД полем, а на телефоне снизу выезжает клавиатура и закрывает
            // его целиком — со стороны выглядит как «подсказки не работают». Подтягиваем поле к
            // середине экрана, когда клавиатура уже поднялась (её анимация ~300 мс).
            onFocus={(e) => {
              const field = e.currentTarget;
              setTimeout(() => field.scrollIntoView({ block: 'center', behavior: 'smooth' }), 350);
            }}
            placeholder="Своей темы нет в списке? Найдите её"
            aria-label="Поиск темы"
            // Темы канонично строчные — глушим клавиатурную заглавную, чтобы ввод выглядел
            // так же, как сохранится (тот же приём, что в интересах профиля).
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
          />

          {(found.length > 0 || canAddOwn) && (
            <div className="ci-found" role="listbox">
              {found.map((name) => (
                <button key={name} type="button" className="ci-found-item" onClick={() => add(name)}>
                  {name}
                </button>
              ))}
              {canAddOwn && (
                <button type="button" className="ci-found-item ci-own" onClick={() => add(query)}>
                  Добавить «{query}»
                </button>
              )}
            </div>
          )}
        </>
      )}

      <div className="ci-counter">
        {atMax ? `Максимум ${MAX_CLUB_INTERESTS} тем` : `${value.length}/${MAX_CLUB_INTERESTS}`}
      </div>
    </div>
  );
};

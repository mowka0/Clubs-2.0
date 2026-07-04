import { FC, KeyboardEvent, useEffect, useState } from 'react';
import { useInterestSuggestQuery } from '../../queries/profile';

// Максимум интересов в профиле.
const MAX_INTERESTS = 15;
// Максимальная длина одного интереса (символов).
const MAX_LEN = 40;
// Обрамляющие кавычки — срезаются с краёв строки при нормализации.
const QUOTES = /^["'«»“”‘’`]+|["'«»“”‘’`]+$/g;

/** Зеркало серверного InterestNormalizer — чипы показывают каноничную форму. */
export function normalizeInterest(raw: string): string {
  return raw
    .normalize('NFC')
    .trim()
    .replace(QUOTES, '')
    .trim()
    .replace(/\s+/g, ' ')
    .toLowerCase()
    .replace(/ё/g, 'е')
    .slice(0, MAX_LEN);
}

interface InterestsInputProps {
  value: string[];
  onChange: (next: string[]) => void;
}

/**
 * Чипы интересов через запятую с префиксным автодополнением. Чип фиксируется по
 * запятой/Enter, Backspace в пустом поле удаляет последний. Тап по подсказке
 * (дедуплицированной против текущего выбора) добавляет её. Нормализует к той же
 * каноничной форме, которую хранит бэкенд, — что видишь, то и сохранится.
 */
export const InterestsInput: FC<InterestsInputProps> = ({ value, onChange }) => {
  const [input, setInput] = useState('');
  const [debounced, setDebounced] = useState('');

  useEffect(() => {
    const t = setTimeout(() => setDebounced(input), 250);
    return () => clearTimeout(t);
  }, [input]);

  const suggestQuery = useInterestSuggestQuery(debounced);
  const atMax = value.length >= MAX_INTERESTS;

  const addMany = (raw: string[]) => {
    const next = [...value];
    for (const part of raw) {
      const normalized = normalizeInterest(part);
      if (!normalized) continue;
      if (next.length >= MAX_INTERESTS) break;
      if (!next.includes(normalized)) next.push(normalized);
    }
    if (next.length !== value.length) onChange(next);
  };

  const handleChange = (raw: string) => {
    if (raw.includes(',')) {
      const parts = raw.split(',');
      const remainder = parts.pop() ?? '';
      addMany(parts);
      setInput(remainder);
    } else {
      setInput(raw);
    }
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if ((e.key === 'Enter' || e.key === ',') && input.trim()) {
      e.preventDefault();
      addMany([input]);
      setInput('');
    } else if (e.key === 'Backspace' && !input && value.length > 0) {
      onChange(value.slice(0, -1));
    }
  };

  const pickSuggestion = (name: string) => {
    addMany([name]);
    setInput('');
  };

  const remove = (interest: string) => onChange(value.filter((i) => i !== interest));

  const suggestions = (suggestQuery.data ?? []).filter((s) => !value.includes(s)).slice(0, 8);
  const showDropdown = debounced.trim().length >= 2 && suggestions.length > 0;

  return (
    <div className="pf-interests">
      <div className="pf-chips">
        {value.map((interest) => (
          <span key={interest} className="pf-chip">
            {interest}
            <button type="button" className="x" onClick={() => remove(interest)} aria-label={`Удалить ${interest}`}>
              ×
            </button>
          </span>
        ))}
        {!atMax && (
          <input
            className="pf-chip-input"
            value={input}
            onChange={(e) => handleChange(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={value.length === 0 ? 'Введите через запятую' : ''}
            aria-label="Добавить интерес"
          />
        )}
      </div>

      {showDropdown && (
        <div className="pf-suggest" role="listbox">
          {suggestions.map((s) => (
            <button key={s} type="button" className="pf-suggest-item" onClick={() => pickSuggestion(s)}>
              {s}
            </button>
          ))}
        </div>
      )}

      <div className="pf-interests-hint">
        {atMax ? `Максимум ${MAX_INTERESTS} интересов` : `${value.length}/${MAX_INTERESTS} · через запятую`}
      </div>
    </div>
  );
};

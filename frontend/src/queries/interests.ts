import { useQuery } from '@tanstack/react-query';
import { getCategoryInterests, suggestInterests } from '../api/interests';

/**
 * Автодополнение интересов по префиксу. Вызывающий передаёт уже debounced-запрос;
 * отключено при длине < 2 символов, чтобы не слать запрос на каждое нажатие клавиши.
 */
export function useInterestSuggestQuery(query: string) {
  const trimmed = query.trim();
  return useQuery({
    queryKey: ['interests', 'suggest', trimmed.toLowerCase()],
    queryFn: () => suggestInterests(trimmed),
    enabled: trimmed.length >= 2,
    staleTime: 60_000,
  });
}

/**
 * Чипы тем выбранной полки при разметке клуба. Словарь меняется медленно, поэтому кэш
 * живёт долго — переключение категорий туда-обратно не должно бить в сеть каждый раз.
 */
export function useCategoryInterestsQuery(category: string) {
  return useQuery({
    queryKey: ['interests', 'category', category],
    queryFn: () => getCategoryInterests(category),
    enabled: category.length > 0,
    staleTime: 10 * 60_000,
  });
}

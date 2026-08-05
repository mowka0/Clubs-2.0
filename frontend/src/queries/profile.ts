import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { completeTour, getMyInterests, updateMyProfile } from '../api/profile';
import { useAuthStore } from '../store/useAuthStore';
import type { OnboardingTour, UpdateProfileBody } from '../types/api';
import { queryKeys } from './queryKeys';

export function useMyInterestsQuery() {
  return useQuery({
    queryKey: queryKeys.clubs.myInterests(),
    queryFn: getMyInterests,
  });
}

// Автодополнение интересов переехало в ./interests — рядом с темами клуба, словарь общий.

export function useUpdateProfileMutation() {
  const qc = useQueryClient();
  const setUser = useAuthStore((s) => s.setUser);
  return useMutation({
    mutationFn: (body: UpdateProfileBody) => updateMyProfile(body),
    onSuccess: (user) => {
      setUser(user);
      qc.invalidateQueries({ queryKey: queryKeys.clubs.myInterests() });
      // Профиль-квест: сохранение полей может закрыть вехи (+XP/бейдж) — панель «Уровень»
      // и карточка-квеста должны увидеть свежие done-флаги без перезагрузки.
      qc.invalidateQueries({ queryKey: queryKeys.clubs.myGamification() });
    },
  });
}

/**
 * Отметка тура пройденным. Возвращает свежий профиль, но **сама в стор его не кладёт** —
 * это делает вызывающий, и делает НАМЕРЕННО ПОЗЖЕ навигации.
 *
 * Почему так: список туров в сторе — это и есть гейт в `Layout`. Положи мы профиль здесь,
 * в `onSuccess` мутации, гейт открылся бы сразу и размонтировал интро — а колбэки,
 * переданные в `mutate(...)`, TanStack уже не вызывает у наблюдателя без слушателей
 * (`hasListeners()`). Навигация просто терялась, и человек оставался там, где стоял.
 * Поэтому порядок обязан быть: дождались сервера → ушли на страницу → открыли гейт.
 *
 * Обработки 409 больше нет: эндпоинт стал идемпотентным, повтор отдаёт 200 (V72).
 */
export function useCompleteTourMutation() {
  return useMutation({
    mutationFn: (tour: OnboardingTour) => completeTour(tour),
  });
}

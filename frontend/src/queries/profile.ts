import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../api/apiClient';
import { completeOnboarding, getMe, getMyInterests, suggestInterests, updateMyProfile } from '../api/profile';
import { useAuthStore } from '../store/useAuthStore';
import type { OnboardingDoor, UpdateProfileBody } from '../types/api';
import { queryKeys } from './queryKeys';

/** «Онбординг уже пройден» — для нас не ошибка, а подтверждение цели (см. useCompleteOnboardingMutation). */
const HTTP_CONFLICT = 409;

export function useMyInterestsQuery() {
  return useQuery({
    queryKey: queryKeys.clubs.myInterests(),
    queryFn: getMyInterests,
  });
}

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

export function useUpdateProfileMutation() {
  const qc = useQueryClient();
  const setUser = useAuthStore((s) => s.setUser);
  return useMutation({
    mutationFn: (body: UpdateProfileBody) => updateMyProfile(body),
    onSuccess: (user) => {
      setUser(user);
      qc.invalidateQueries({ queryKey: queryKeys.clubs.myInterests() });
    },
  });
}

/**
 * Завершение онбординга. Возвращает свежий профиль, но **сама в стор его не кладёт** —
 * это делает вызывающий, и делает НАМЕРЕННО ПОЗЖЕ навигации.
 *
 * Почему так: `onboardedAt` в сторе — это и есть гейт в `Layout`. Положи мы профиль здесь,
 * в `onSuccess` мутации, гейт открылся бы сразу и размонтировал карусель — а колбэки,
 * переданные в `mutate(...)`, TanStack уже не вызывает у наблюдателя без слушателей
 * (`hasListeners()`). Навигация с подсветкой просто терялась, и человек оставался там,
 * где стоял. Поэтому порядок обязан быть: дождались сервера → ушли на страницу → открыли гейт.
 */
export function useCompleteOnboardingMutation() {
  return useMutation({
    mutationFn: async (door: OnboardingDoor) => {
      try {
        return await completeOnboarding(door);
      } catch (e) {
        // 409 — «онбординг уже пройден»: наш профиль в сторе просто устарел (прошли с другого
        // устройства, пока эта сессия висела открытой). Это не отказ, цель достигнута — забираем
        // свежий профиль и идём дальше. Иначе человек остался бы заперт в карусели до перезапуска.
        if (e instanceof ApiError && e.status === HTTP_CONFLICT) return await getMe();
        throw e;
      }
    },
  });
}

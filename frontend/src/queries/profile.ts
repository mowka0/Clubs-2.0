import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getMyInterests, suggestInterests, updateMyProfile } from '../api/profile';
import { useAuthStore } from '../store/useAuthStore';
import type { UpdateProfileBody } from '../types/api';
import { queryKeys } from './queryKeys';

export function useMyInterestsQuery() {
  return useQuery({
    queryKey: queryKeys.clubs.myInterests(),
    queryFn: getMyInterests,
  });
}

/**
 * Prefix autocomplete for interests. Caller passes an already-debounced query;
 * disabled below 2 chars so we don't fan out a request per keystroke.
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

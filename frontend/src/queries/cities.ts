import { useQuery } from '@tanstack/react-query';
import { getCities } from '../api/cities';
import { queryKeys } from './queryKeys';

// Справочник меняется только вместе с релизом миграции — перезапрашивать его в течение
// сессии незачем, поэтому он живёт в кэше без протухания.
const CITIES_STALE_TIME_MS = Infinity;

export function useCities() {
  return useQuery({
    queryKey: queryKeys.cities.all,
    queryFn: getCities,
    staleTime: CITIES_STALE_TIME_MS,
  });
}

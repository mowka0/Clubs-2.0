import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createEventTemplate,
  deleteEventTemplate,
  getClubEventTemplates,
  getMyEventTemplates,
  updateEventTemplate,
} from '../api/eventTemplates';
import { queryKeys } from './queryKeys';
import type { EventTemplateDto, SaveEventTemplateBody } from '../api/eventTemplates';

/**
 * Шаблоны всех управляемых клубов — питает список в пикере «+». Одним запросом, а не по
 * запросу на клуб: пикер открывается часто, а шаблонов у человека единицы.
 */
export function useMyEventTemplatesQuery(enabled = true) {
  return useQuery({
    queryKey: queryKeys.eventTemplates.mine,
    queryFn: getMyEventTemplates,
    enabled,
  });
}

/** Шаблоны одного клуба — форма создания читает отсюда применяемый шаблон. */
export function useClubEventTemplatesQuery(clubId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.eventTemplates.byClub(clubId ?? ''),
    queryFn: () => getClubEventTemplates(clubId!),
    enabled: Boolean(clubId),
  });
}

/**
 * Оба списка (кросс-клубовый и по клубу) держат одни и те же строки, поэтому любая запись
 * инвалидирует весь префикс — иначе пикер показывал бы удалённый шаблон до перезагрузки.
 */
function useInvalidateTemplates() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: queryKeys.eventTemplates.all });
}

interface SaveTemplateArgs {
  clubId: string;
  body: SaveEventTemplateBody;
  /** Задан — перезаписываем существующий шаблон, иначе создаём новый. */
  templateId?: string;
}

export function useSaveEventTemplateMutation() {
  const invalidate = useInvalidateTemplates();
  return useMutation<EventTemplateDto, Error, SaveTemplateArgs>({
    mutationFn: ({ clubId, body, templateId }) =>
      templateId
        ? updateEventTemplate(clubId, templateId, body)
        : createEventTemplate(clubId, body),
    onSuccess: invalidate,
  });
}

export function useDeleteEventTemplateMutation() {
  const invalidate = useInvalidateTemplates();
  return useMutation<void, Error, { clubId: string; templateId: string }>({
    mutationFn: ({ clubId, templateId }) => deleteEventTemplate(clubId, templateId),
    onSuccess: invalidate,
  });
}

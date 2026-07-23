import { useMutation } from '@tanstack/react-query';
import { submitFeedback, type SubmitFeedbackBody } from '../api/feedback';

/** Отправка обратной связи. Кэш не трогает — на клиенте ничего не сохраняется. */
export function useSubmitFeedbackMutation() {
  return useMutation({
    mutationFn: (body: SubmitFeedbackBody) => submitFeedback(body),
  });
}

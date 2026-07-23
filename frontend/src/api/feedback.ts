import { apiClient } from './apiClient';

export interface SubmitFeedbackBody {
  /** Текст баг-репорта/пожелания, 1..2000 символов. */
  message: string;
  /** Route, с которого открыта форма — контекст «где воспроизвелось». */
  page?: string;
}

/** Отправляет обратную связь в поддержку. Бэкенд отвечает 204 только после реальной доставки DM. */
export function submitFeedback(body: SubmitFeedbackBody): Promise<void> {
  return apiClient.post<void>('/api/feedback', body);
}

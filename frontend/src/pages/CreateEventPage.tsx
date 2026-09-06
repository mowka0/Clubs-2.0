import { FC } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { useBackButton } from '../hooks/useBackButton';
import { EventForm } from '../components/event/EventForm';
import type { EventFormat } from '../types/api';
import { useClubEventTemplatesQuery } from '../queries/eventTemplates';

/**
 * Страница создания встречи. Тонкая обёртка: её единственная работа — дождаться шаблона,
 * если форма открыта по `?template=<id>`, и только потом смонтировать форму. Так начальные
 * значения приходят в `useState`-инициализаторы, а не досылаются `useEffect`'ом в уже
 * смонтированную форму (правило «You Might Not Need an Effect»).
 *
 * Сами поля живут в [EventForm] — общем компоненте с правкой шаблона.
 */
export const CreateEventPage: FC = () => {
  useBackButton(true);
  const { id: clubId } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const templateId = searchParams.get('template');
  // Формат из шага пикера «+». Неизвестное значение (старая ссылка `?format=min|max|any`,
  // опечатка) схлопывается в обычную встречу — это смысл лимита у всех встреч до V86.
  // При входе по шаблону формат несёт сам шаблон, и ?format не читается.
  const formatParam = searchParams.get('format');
  const initialFormat: EventFormat = formatParam === 'open' ? 'open' : 'normal';

  const templatesQuery = useClubEventTemplatesQuery(templateId ? clubId : undefined);

  if (!clubId) {
    return (
      <div className="rd-page">
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--text-dim)' }}>
          Клуб не найден
        </div>
      </div>
    );
  }

  if (templateId && templatesQuery.isLoading) {
    return (
      <div className="rd-page">
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--text-dim)' }}>Загружаем шаблон…</div>
      </div>
    );
  }

  const template = templateId
    ? (templatesQuery.data ?? []).find((t) => t.id === templateId) ?? null
    : null;

  return (
    <EventForm
      // Ключ гарантирует, что переход между «с нуля» и «по шаблону» пересоздаёт форму с новыми
      // начальными значениями: без него React переиспользовал бы состояние прошлого прохода.
      key={templateId ?? initialFormat}
      mode="event"
      clubId={clubId}
      template={template}
      initialFormat={template ? template.format : initialFormat}
      // Шаблон могли удалить с другого устройства, пока пользователь шёл по ссылке.
      templateMissing={Boolean(templateId) && template === null}
    />
  );
};

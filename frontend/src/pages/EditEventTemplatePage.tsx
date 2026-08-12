import { FC } from 'react';
import { useParams } from 'react-router-dom';
import { useBackButton } from '../hooks/useBackButton';
import { EventForm } from '../components/event/EventForm';
import { useClubEventTemplatesQuery } from '../queries/eventTemplates';
import { templateFormat } from '../utils/eventTemplate';

/**
 * Правка шаблона встречи. Тонкая обёртка, как и страница создания: дожидается шаблона и
 * монтирует ту же [EventForm] в режиме `template` — поля общие, третьей копии формы нет.
 *
 * Отдельный экран нужен ровно потому, что раньше поправить шаблон можно было только заодно
 * с созданием встречи (галочка «Обновить шаблон» в форме создания): чтобы сменить в шаблоне
 * место, приходилось заводить ненужную встречу.
 */
export const EditEventTemplatePage: FC = () => {
  useBackButton(true);
  const { id: clubId, templateId } = useParams<{ id: string; templateId: string }>();
  const templatesQuery = useClubEventTemplatesQuery(clubId);

  if (!clubId || !templateId) {
    return (
      <div className="rd-page">
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--text-dim)' }}>
          Шаблон не найден
        </div>
      </div>
    );
  }

  if (templatesQuery.isLoading) {
    return (
      <div className="rd-page">
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--text-dim)' }}>Загружаем шаблон…</div>
      </div>
    );
  }

  const template = (templatesQuery.data ?? []).find((t) => t.id === templateId) ?? null;

  // Шаблон могли удалить с другого устройства, пока пользователь шёл по ссылке. Пустая форма
  // тут была бы враньём: править нечего, поэтому честное «не найден».
  if (!template) {
    return (
      <div className="rd-page">
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--text-dim)' }}>
          Шаблон не найден — возможно, его удалили.
        </div>
      </div>
    );
  }

  return (
    <EventForm
      mode="template"
      clubId={clubId}
      template={template}
      initialFormat={templateFormat(template)}
    />
  );
};

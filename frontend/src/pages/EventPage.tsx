import { FC } from 'react';
import { useParams } from 'react-router-dom';
import { Placeholder } from '@telegram-apps/telegram-ui';

export const EventPage: FC = () => {
  const { id } = useParams<{ id: string }>();

  return (
    <Placeholder
      header="Событие"
      description={`Страница события ${id ?? ''} в разработке`}
    />
  );
};

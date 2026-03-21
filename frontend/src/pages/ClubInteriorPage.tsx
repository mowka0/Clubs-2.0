import { FC } from 'react';
import { useParams } from 'react-router-dom';
import { Placeholder } from '@telegram-apps/telegram-ui';

export const ClubInteriorPage: FC = () => {
  const { id } = useParams<{ id: string }>();

  return (
    <Placeholder
      header="Внутри клуба"
      description={`Интерьер клуба ${id ?? ''} в разработке`}
    />
  );
};

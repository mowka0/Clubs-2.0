import { FC } from 'react';
import { useParams } from 'react-router-dom';
import { Placeholder } from '@telegram-apps/telegram-ui';

export const InvitePage: FC = () => {
  const { code } = useParams<{ code: string }>();

  return (
    <Placeholder
      header="Приглашение"
      description={`Приглашение ${code ?? ''} — страница в разработке`}
    />
  );
};

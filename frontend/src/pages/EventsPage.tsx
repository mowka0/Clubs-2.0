import { FC } from 'react';
import { List, Placeholder } from '@telegram-apps/telegram-ui';

/**
 * Placeholder page for the upcoming "События" tab.
 * Real feed (upcoming events from clubs the user is a member of) will be
 * implemented in a follow-up PR — see docs/modules/my-clubs-unified.md.
 */
export const EventsPage: FC = () => (
  <List>
    <Placeholder
      header="События"
      description="Здесь скоро появится лента ваших ближайших событий из всех клубов, где вы состоите."
    >
      <span style={{ fontSize: 64 }} role="img" aria-label="calendar">
        📅
      </span>
    </Placeholder>
  </List>
);

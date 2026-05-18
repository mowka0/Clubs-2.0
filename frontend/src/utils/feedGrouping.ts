import type { MyEventListItemDto } from '../types/api';

export type FeedSectionKey = 'action_required' | 'this_week' | 'later';

export interface FeedSectionData {
  key: FeedSectionKey;
  title: string;
  events: MyEventListItemDto[];
}

const MS_PER_DAY = 24 * 60 * 60 * 1000;
const WEEK_HORIZON_DAYS = 7;

export function groupMyEvents(
  events: readonly MyEventListItemDto[],
  now: Date = new Date(),
): FeedSectionData[] {
  const actionRequired: MyEventListItemDto[] = [];
  const thisWeek: MyEventListItemDto[] = [];
  const later: MyEventListItemDto[] = [];

  const weekCutoffMs = now.getTime() + WEEK_HORIZON_DAYS * MS_PER_DAY;

  for (const e of events) {
    if (e.actionRequired) {
      actionRequired.push(e);
      continue;
    }
    const eventMs = new Date(e.eventDatetime).getTime();
    if (eventMs <= weekCutoffMs) {
      thisWeek.push(e);
    } else {
      later.push(e);
    }
  }

  const sections: FeedSectionData[] = [];
  if (actionRequired.length > 0) {
    sections.push({ key: 'action_required', title: 'Требует действия', events: actionRequired });
  }
  if (thisWeek.length > 0) {
    sections.push({ key: 'this_week', title: 'Эта неделя', events: thisWeek });
  }
  if (later.length > 0) {
    sections.push({ key: 'later', title: 'Позже', events: later });
  }
  return sections;
}

import type { MyEventListItemDto } from '../types/api';

export type FeedSectionKey = 'action_required' | 'this_week' | 'later' | 'history';

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
  const history: MyEventListItemDto[] = [];

  const weekCutoffMs = now.getTime() + WEEK_HORIZON_DAYS * MS_PER_DAY;

  for (const e of events) {
    // История — ПЕРВАЯ проверка, до actionRequired и недельного горизонта. Историчность —
    // только по isHistory: status флипается кроном с лагом до ~7ч, поэтому событие с уже
    // отмеченной явкой может ещё висеть в stage_2 и без этой ветки провалилось бы в «Эта
    // неделя» (регресс AC-H14). Порядок внутри истории (недавние первыми) задан бэкендом —
    // раскладываем по секциям в порядке прихода, НЕ пересортировываем.
    if (e.isHistory) {
      history.push(e);
      continue;
    }
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
  // «История» — всегда последняя секция ленты.
  if (history.length > 0) {
    sections.push({ key: 'history', title: 'История', events: history });
  }
  return sections;
}

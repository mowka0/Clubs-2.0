import { pluralRu } from '../../utils/formatters';
import type { ClubStatsDto, TrendDto } from '../../types/api';

/** Визуальный тон значения метрики. */
export type Tone = 'bad' | 'ok' | 'neutral';

export interface LeverVM {
  key: string;
  label: string;
  value: string;
  tone: Tone;
  trend: TrendDto | null;
}

export interface NudgeVM {
  key: string;
  icon: string;
  severity: 'normal' | 'red';
  /** Жирный call-to-action; [rest] — продолжение обычным начертанием. */
  lead: string;
  rest: string;
}

export interface AttentionVM {
  key: string;
  label: string;
  value: string;
  tone: Tone;
}

// Процент, от которого метрика читается зелёной (tone 'ok').
const GOOD_PCT = 75;
// Процент, ниже которого метрика читается красной (tone 'bad'); между порогами — нейтрально.
const BAD_PCT = 50;
// Порог вовлечённости: ниже него срабатывает nudge «напомните о встрече».
const ENGAGEMENT_NUDGE_THRESHOLD = 70;

function pctTone(value: number): Tone {
  if (value >= GOOD_PCT) return 'ok';
  if (value < BAD_PCT) return 'bad';
  return 'neutral';
}

/**
 * «Клуб ещё не жил»: ни одной встречи и никого, кроме владельца (владелец сам состоит
 * в memberships, поэтому граница — 1). Нулевая вовлечённость тут — не тревога, а отсутствие
 * истории: красный тон и подсказка «напомните о встрече» только пугали бы нового организатора.
 */
export function isPristineClub(s: ClubStatsDto, memberCount: number): boolean {
  return s.totalMeetings === 0 && memberCount <= 1;
}

/** Рычаги роста, адаптированные под тип клуба (§9.3). Возвращаются только применимые. */
export function buildLevers(s: ClubStatsDto, isPristine: boolean = false): LeverVM[] {
  const levers: LeverVM[] = [];

  if (s.clubType === 'paid' && s.retentionPercent !== null) {
    levers.push({
      key: 'retention',
      label: 'Удержание (продлевают)',
      value: `${s.retentionPercent}%`,
      tone: pctTone(s.retentionPercent),
      trend: s.retentionTrend,
    });
  }

  levers.push({
    key: 'churned',
    label: s.clubType === 'paid' ? 'Не продлили за месяц' : 'Ушли за месяц',
    value: String(s.churnedThisPeriod),
    tone: s.churnedThisPeriod > 0 ? 'bad' : 'neutral',
    trend: null,
  });

  if (s.clubType === 'free') {
    levers.push({
      key: 'rejoined',
      label: 'Вернулись за месяц',
      value: String(s.rejoinedThisPeriod),
      tone: s.rejoinedThisPeriod > 0 ? 'ok' : 'neutral',
      trend: null,
    });
  }

  levers.push({
    key: 'engagement',
    label: 'Вовлечённость (голосуют/идут)',
    value: `${s.engagementPercent}%`,
    // У не жившего клуба 0% — это «ещё нечего мерить», а не провал: не красим.
    tone: isPristine ? 'neutral' : pctTone(s.engagementPercent),
    trend: s.engagementTrend,
  });

  if (s.skladchinaPaidPercent !== null) {
    levers.push({
      key: 'skladchina',
      label: 'Оплата складчин',
      value: `${s.skladchinaPaidPercent}%`,
      tone: pctTone(s.skladchinaPaidPercent),
      trend: s.skladchinaPaidTrend,
    });
  }

  if (s.pendingApplications !== null) {
    levers.push({
      key: 'pending',
      label: 'Заявки ждут ответа',
      value: String(s.pendingApplications),
      tone: s.pendingApplications > 0 ? 'bad' : 'neutral',
      trend: null,
    });
  }

  return levers;
}

/** Действенные nudge'и, привязанные к рычагам — фиксированный whitelist (§9.5); возвращаются только сработавшие. */
export function buildNudges(s: ClubStatsDto, isPristine: boolean = false): NudgeVM[] {
  const nudges: NudgeVM[] = [];

  const pending = s.pendingApplications ?? 0;
  if (pending > 0) {
    const stale = s.stalePendingApplications ?? 0;
    nudges.push({
      key: 'answer_applications',
      icon: '!',
      severity: stale > 0 ? 'red' : 'normal',
      lead: `Ответьте на ${pending} ${pluralRu(pending, ['заявку', 'заявки', 'заявок'])}`,
      rest:
        stale > 0
          ? ` — ${stale} ${pluralRu(stale, ['висит', 'висят', 'висят'])} больше суток, скоро авто-отклонение.`
          : ' — не задерживайте ответ, иначе уйдут в авто-отклонение.',
    });
  }

  if (s.churnedThisPeriod > 0) {
    nudges.push({
      key: 'win_back',
      icon: '👋',
      severity: 'normal',
      lead: `Верните ${s.churnedThisPeriod} ${pluralRu(s.churnedThisPeriod, ['ушедшего', 'ушедших', 'ушедших'])}`,
      rest: ' — позовите на ближайшую встречу.',
    });
  }

  // Пока клуб не жил, «напомните о встрече» бессмысленно — напоминать не о чем и некому.
  if (!isPristine && s.engagementPercent < ENGAGEMENT_NUDGE_THRESHOLD) {
    nudges.push({
      key: 'remind_engagement',
      icon: '📣',
      severity: 'normal',
      lead: `Вовлечённость ${s.engagementPercent}%`,
      rest: ' — напомните о встрече тем, кто давно не голосовал.',
    });
  }

  return nudges;
}

/** Owner-only негативы «Надёжности организатора» (§9.5). 0 читается зелёным (чисто), любое число — нейтрально. */
export function buildAttention(s: ClubStatsDto): AttentionVM[] {
  const items: AttentionVM[] = [
    {
      key: 'disputes',
      label: 'Споры по явке',
      value: s.totalMeetings > 0 ? `${s.attendanceDisputes} из ${s.totalMeetings}` : String(s.attendanceDisputes),
      tone: s.attendanceDisputes > 0 ? 'neutral' : 'ok',
    },
  ];

  if (s.autoRejectedApplications !== null) {
    items.push({
      key: 'auto_rejected',
      label: 'Авто-отклонений заявок',
      value: String(s.autoRejectedApplications),
      tone: s.autoRejectedApplications > 0 ? 'neutral' : 'ok',
    });
  }

  items.push({
    key: 'cancelled',
    label: 'Отменённых встреч',
    value: String(s.cancelledMeetings),
    tone: s.cancelledMeetings > 0 ? 'neutral' : 'ok',
  });

  return items;
}

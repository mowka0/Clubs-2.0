/**
 * Русские названия категорий клубов (ключи — значения enum с бэка).
 * Вынесено из ClubCard, чтобы переиспользовать в WeekShelf. В MyClubsPage /
 * InvitePage / OrganizerClubManage живут свои локальные копии — консолидация
 * отложена в backlog рефакторинга (не в скоупе discovery-card-v2).
 */
export const CATEGORY_LABELS: Record<string, string> = {
  sport: 'Спорт',
  creative: 'Творчество',
  food: 'Еда',
  board_games: 'Настолки',
  cinema: 'Кино',
  education: 'Образование',
  travel: 'Путешествия',
  other: 'Другое',
};

/** Категории с собственным градиентом обложки (data-cat); неизвестные падают в other. */
export const KNOWN_CATEGORIES = new Set(Object.keys(CATEGORY_LABELS));

export function categoryLabel(category: string): string {
  return CATEGORY_LABELS[category] ?? 'Другое';
}

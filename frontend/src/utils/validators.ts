export interface ClubFormData {
  name: string;
  city: string;
  district: string;
  category: string;
  accessType: 'open' | 'closed';
  memberLimit: string;
  subscriptionPrice: string;
  description: string;
  rules: string;
  applicationQuestion: string;
}

export function validateStep(step: number, form: ClubFormData): string | null {
  if (step === 0) {
    if (form.name.trim().length < 3) return 'Название: минимум 3 символа';
    if (form.name.trim().length > 60) return 'Название: максимум 60 символов';
    if (!form.city.trim()) return 'Укажите город';
  }
  if (step === 2) {
    const limit = Number(form.memberLimit);
    if (!limit || limit < 1 || limit > 200) return 'Лимит участников: 1–200';
    const price = Number(form.subscriptionPrice);
    if (isNaN(price) || price < 0) return 'Укажите корректную цену';
    if (!Number.isInteger(price)) return 'Цена должна быть целым числом';
  }
  if (step === 3) {
    if (form.description.trim().length < 10) return 'Описание: минимум 10 символов';
    if (form.description.trim().length > 500) return 'Описание: максимум 500 символов';
  }
  return null;
}

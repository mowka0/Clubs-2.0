import { describe, it, expect } from 'vitest';
import { validateStep, ClubFormData } from '../../utils/validators';

function makeForm(overrides: Partial<ClubFormData> = {}): ClubFormData {
  return {
    name: 'Test Club Name',
    city: 'Москва',
    district: '',
    category: 'other',
    accessType: 'open',
    memberLimit: '30',
    subscriptionPrice: '0',
    description: 'This is a valid description for testing purposes.',
    rules: '',
    applicationQuestion: '',
    ...overrides,
  };
}

describe('validateStep', () => {
  // ---- Step 0: Основное (name, city) ----
  describe('step 0 — basic info', () => {
    it('returns error when name is shorter than 3 characters', () => {
      const form = makeForm({ name: 'ab' });
      expect(validateStep(0, form)).toBe('Название: минимум 3 символа');
    });

    it('returns error when name consists of only whitespace below 3 chars', () => {
      const form = makeForm({ name: '  a ' });
      expect(validateStep(0, form)).toBe('Название: минимум 3 символа');
    });

    it('returns error when name exceeds 60 characters', () => {
      const form = makeForm({ name: 'A'.repeat(61) });
      expect(validateStep(0, form)).toBe('Название: максимум 60 символов');
    });

    it('returns error when city is empty', () => {
      const form = makeForm({ city: '' });
      expect(validateStep(0, form)).toBe('Укажите город');
    });

    it('returns error when city is only whitespace', () => {
      const form = makeForm({ city: '   ' });
      expect(validateStep(0, form)).toBe('Укажите город');
    });

    it('returns null for valid step 0 data', () => {
      const form = makeForm({ name: 'Valid Name', city: 'Москва' });
      expect(validateStep(0, form)).toBeNull();
    });

    it('accepts name with exactly 3 characters', () => {
      const form = makeForm({ name: 'Abc' });
      expect(validateStep(0, form)).toBeNull();
    });

    it('accepts name with exactly 60 characters', () => {
      const form = makeForm({ name: 'A'.repeat(60) });
      expect(validateStep(0, form)).toBeNull();
    });
  });

  // ---- Step 1: Category (no validation) ----
  describe('step 1 — category', () => {
    it('returns null (no validation on step 1)', () => {
      const form = makeForm();
      expect(validateStep(1, form)).toBeNull();
    });
  });

  // ---- Step 2: Участники (memberLimit, subscriptionPrice) ----
  describe('step 2 — members and pricing', () => {
    it('returns error when memberLimit is 0', () => {
      const form = makeForm({ memberLimit: '0' });
      expect(validateStep(2, form)).toBe('Лимит участников: 1–200');
    });

    it('returns error when memberLimit is less than 1', () => {
      const form = makeForm({ memberLimit: '-5' });
      expect(validateStep(2, form)).toBe('Лимит участников: 1–200');
    });

    it('returns error when memberLimit exceeds 200', () => {
      const form = makeForm({ memberLimit: '201' });
      expect(validateStep(2, form)).toBe('Лимит участников: 1–200');
    });

    it('returns error when memberLimit is not a number', () => {
      const form = makeForm({ memberLimit: 'abc' });
      expect(validateStep(2, form)).toBe('Лимит участников: 1–200');
    });

    it('returns error when price is negative', () => {
      const form = makeForm({ subscriptionPrice: '-1' });
      expect(validateStep(2, form)).toBe('Укажите корректную цену');
    });

    it('returns null when price is 0 (free)', () => {
      const form = makeForm({ subscriptionPrice: '0' });
      expect(validateStep(2, form)).toBeNull();
    });

    it('returns null when price is 1 (minimum paid)', () => {
      const form = makeForm({ subscriptionPrice: '1' });
      expect(validateStep(2, form)).toBeNull();
    });

    it('returns null when price is above 1', () => {
      const form = makeForm({ subscriptionPrice: '500' });
      expect(validateStep(2, form)).toBeNull();
    });

    it('returns error for fractional price', () => {
      const form = makeForm({ subscriptionPrice: '1.5' });
      expect(validateStep(2, form)).toBe('Цена должна быть целым числом');
    });

    it('accepts memberLimit of 1', () => {
      const form = makeForm({ memberLimit: '1' });
      expect(validateStep(2, form)).toBeNull();
    });

    it('accepts memberLimit of 200', () => {
      const form = makeForm({ memberLimit: '200' });
      expect(validateStep(2, form)).toBeNull();
    });
  });

  // ---- Step 3: Описание ----
  describe('step 3 — description', () => {
    it('returns error when description is shorter than 10 characters', () => {
      const form = makeForm({ description: 'Short' });
      expect(validateStep(3, form)).toBe('Описание: минимум 10 символов');
    });

    it('returns error when description is only whitespace under 10 chars', () => {
      const form = makeForm({ description: '         ' });
      expect(validateStep(3, form)).toBe('Описание: минимум 10 символов');
    });

    it('returns error when description exceeds 500 characters', () => {
      const form = makeForm({ description: 'A'.repeat(501) });
      expect(validateStep(3, form)).toBe('Описание: максимум 500 символов');
    });

    it('returns null for valid description (exactly 10 chars)', () => {
      const form = makeForm({ description: '1234567890' });
      expect(validateStep(3, form)).toBeNull();
    });

    it('returns null for valid description (exactly 500 chars)', () => {
      const form = makeForm({ description: 'A'.repeat(500) });
      expect(validateStep(3, form)).toBeNull();
    });

    it('returns null for a normal description', () => {
      const form = makeForm({ description: 'Отличный клуб для книголюбов!' });
      expect(validateStep(3, form)).toBeNull();
    });
  });

  // ---- Step 4: Заявка (no validation in validateStep) ----
  describe('step 4 — application question', () => {
    it('returns null (no validation on step 4)', () => {
      const form = makeForm();
      expect(validateStep(4, form)).toBeNull();
    });
  });
});

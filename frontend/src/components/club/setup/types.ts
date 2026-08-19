import type { ReactNode } from 'react';
import type { CityDto, ClubDetailDto } from '../../../types/api';

/**
 * Черновик мастера наполнения: введённое, но ещё не сохранённое на сервере.
 *
 * Живёт в самом мастере, а не в шагах, по одной причине — шаг размонтируется при переходе.
 * Описание, набранное перед «Пропустить», должно дождаться человека, если он вернётся назад.
 * `null` в поле значит «не трогали», и шаг показывает то, что лежит в клубе.
 */
export interface ClubSetupDraft {
  name: string | null;
  city: CityDto | null;
  description: string | null;
  interests: string[] | null;
  /** Строкой, а не числом: поле ввода отдаёт текст, и пустое значение тоже нужно уметь показать. */
  memberLimit: string | null;
}

/** Правка черновика: шаг отдаёт только свои поля, остальное остаётся как было. */
export type ClubSetupDraftPatch = (patch: Partial<ClubSetupDraft>) => void;

/**
 * Всё, что мастер умеет дать шагу. Каждый шаг берёт отсюда `Pick`-ом ровно то, чем пользуется:
 * так видно его настоящую поверхность, а объект в мастере остаётся под проверкой типов —
 * опечатка в имени поля перестала бы быть невидимой при спреде.
 */
export interface ClubSetupStepProps {
  club: ClubDetailDto;
  draft: ClubSetupDraft;
  onDraftChange: ClubSetupDraftPatch;
  /** Идёт PATCH клуба — кнопки шага заблокированы, чтобы не отправить его дважды. */
  saving: boolean;
  /** Сообщение о неудавшемся сохранении; шаг ставит его под кнопкой «Дальше». */
  error: ReactNode;
  /** Сохранить введённое и уйти на шаг `next`. Запрос упал — остаёмся на месте. */
  onSaveAndNext: (body: Record<string, unknown>, next: number) => void;
  /** Уйти на шаг `next`, ничего не сохраняя: пропуск или «поле не меняли». */
  onGoToStep: (next: number) => void;
}

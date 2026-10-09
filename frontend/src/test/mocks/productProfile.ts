/**
 * Подменный профиль продукта для тестов спрятанных на этапе 1 функций (взносы, заявки, создание
 * клуба с нуля, категория). Сами функции не удалены — на этапе 2 они вернутся, — поэтому их тесты
 * гоняются под профилем этапа 2, а не выбрасываются (docs/design/stage-1-scope.md § «Тесты»).
 *
 * Подключение в файле теста — одной строкой, как у моков telegram-ui:
 *
 *   vi.mock('../../config/productProfile', () => import('../mocks/productProfile'));
 *
 * Проверки «этого на экране нет» на этапе 1 — в блоке `describe` с вызовом `withStage1Profile()`:
 * на время каждого теста блока флаги этапа выключаются. Работает, потому что экраны читают флаги
 * при рендере, а не при импорте модуля.
 */
import { afterEach, beforeEach, vi } from 'vitest';
import type { ProductProfile } from '../../config/productProfile';

type Mutable<T> = { -readonly [K in keyof T]: T[K] };

const actual = await vi.importActual<typeof import('../../config/productProfile')>(
  '../../config/productProfile',
);

/** Флаги, которые этап 1 выключает, — во включённом виде (этап 2). */
const STAGE_2_FEATURES = {
  showClubCreationFromScratch: true,
  showClubDues: true,
  showAccessTypeAndApplications: true,
  showClubCategory: true,
} as const satisfies Partial<ProductProfile>;

/** Те же флаги на этапе 1 — явно, а не «как в config»: тест этапа 1 не должен ехать вслед за конфигом. */
const STAGE_1_FEATURES = {
  showClubCreationFromScratch: false,
  showClubDues: false,
  showAccessTypeAndApplications: false,
  showClubCategory: false,
} as const satisfies Record<keyof typeof STAGE_2_FEATURES, boolean>;

/** Тот самый объект, который получают экраны вместо настоящего профиля. По умолчанию — этап 2. */
export const PRODUCT_PROFILE: Mutable<ProductProfile> = { ...actual.PRODUCT_PROFILE, ...STAGE_2_FEATURES };

/** Внутри `describe`: каждый тест блока идёт под профилем этапа 1, после — снова этап 2. */
export function withStage1Profile(): void {
  beforeEach(() => {
    Object.assign(PRODUCT_PROFILE, STAGE_1_FEATURES);
  });
  afterEach(() => {
    Object.assign(PRODUCT_PROFILE, STAGE_2_FEATURES);
  });
}

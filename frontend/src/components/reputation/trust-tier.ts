export type TrustTier = 'high' | 'mid' | 'low';

/**
 * Trust 0-100 → tier для цвета кольца «Надёжность». Зелёная зона начинается с 70 — в согласии
 * с бэкендовым RELIABLE_THRESHOLD (балл, с которого клуб считается «надёжным»); accent —
 * средняя зона; danger помечает явно низкий trust. Чисто презентационная логика.
 */
export function trustTier(trust: number): TrustTier {
  if (trust >= 70) return 'high';
  if (trust >= 45) return 'mid';
  return 'low';
}

// Цвет кольца по tier: high — зелёный (--live), mid — акцентный, low — цвет опасности
export const TRUST_TIER_COLOR: Record<TrustTier, string> = {
  high: 'var(--live)',
  mid: 'var(--accent)',
  low: 'var(--danger)',
};

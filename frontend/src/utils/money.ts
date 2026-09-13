/** «1 000 ₽», «833,33 ₽» — копейки показываются только когда они есть (доли поровну редко круглые). */
export function formatRub(kopecks: number): string {
  const sign = kopecks < 0 ? '−' : '';
  const abs = Math.abs(kopecks);
  const rub = Math.floor(abs / 100).toLocaleString('ru-RU');
  const kop = abs % 100;
  return kop === 0 ? `${sign}${rub} ₽` : `${sign}${rub},${String(kop).padStart(2, '0')} ₽`;
}

/** Ввод рублей из формы → копейки; null, если не число или ≤ 0. */
export function rubToKopecks(rub: string): number | null {
  const v = Number(rub.replace(',', '.').trim());
  if (!Number.isFinite(v) || v <= 0) return null;
  return Math.round(v * 100);
}

import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Каждая CSS-переменная, которую читают стили, должна быть где-то определена. Неопределённая
 * `var(--x)` без запасного значения молча даёт пустоту: полоска биллинга «Подписка закончилась»
 * рисовалась без подложки, потому что `--warn-soft` не было ни в одной теме (баг PO 2026-10-07).
 *
 * Переменные Telegram (`--tg-…`, `--tgui-…`) задаёт клиент, а `var(--x, запасное)` безопасна сама.
 */
const SRC = join(__dirname, '..', '..');

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === 'test' ? [] : sourceFiles(path);
    return /\.(css|tsx?)$/.test(name) ? [path] : [];
  });
}

describe('CSS-переменные', () => {
  it('нет ни одной var(--x) без определения и без запасного значения', () => {
    const texts = sourceFiles(SRC).map((file) => readFileSync(file, 'utf-8'));
    const defined = new Set(texts.flatMap((t) => [...t.matchAll(/(--[\w-]+)\s*:/g)].map((m) => m[1])));
    const usedBare = new Set(texts.flatMap((t) => [...t.matchAll(/var\(\s*(--[\w-]+)\s*\)/g)].map((m) => m[1])));

    const undefinedVars = [...usedBare]
      .filter((name) => !name.startsWith('--tg-') && !name.startsWith('--tgui'))
      .filter((name) => !defined.has(name));

    expect(undefinedVars).toEqual([]);
  });
});

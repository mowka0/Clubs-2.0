import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

import { SkladchinaCard } from '../../components/feed/SkladchinaCard';
import type { MySkladchinaListItemDto } from '../../types/api';

function buildItem(overrides: Partial<MySkladchinaListItemDto> = {}): MySkladchinaListItemDto {
  return {
    id: 's-1',
    title: 'Ужин после игры',
    clubId: 'club-1',
    clubName: 'Партия',
    clubAvatarUrl: null,
    kind: 'shared',
    amountKopecks: 600000,
    targetKopecks: 600000,
    receivedKopecks: 100000,
    debtCount: 6,
    receivedCount: 1,
    deadline: new Date(Date.now() + 86_400_000).toISOString(),
    status: 'active',
    isCreator: false,
    myDebtStatus: 'waiting',
    actionRequired: true,
    photoUrl: null,
    ...overrides,
  };
}

describe('SkladchinaCard — сборы v3', () => {
  it('показывает «Оплатили N из M», деньги «X из Y» и бейдж «Ждёт вас»', () => {
    render(<SkladchinaCard skladchina={buildItem()} onClick={vi.fn()} />);
    expect(screen.getByText('Оплатили 1 из 6')).toBeInTheDocument();
    expect(screen.getByText(/1\s?000 ₽ из 6\s?000 ₽/)).toBeInTheDocument();
    expect(screen.getByText('Ждёт вас')).toBeInTheDocument();
    expect(screen.getByText('СКИНУТЬСЯ')).toBeInTheDocument();
  });

  it('у закрытого сбора бейдж — итог, у «По желанию» без срока — «без срока»', () => {
    render(<SkladchinaCard skladchina={buildItem({ status: 'collected', actionRequired: false })} onClick={vi.fn()} />);
    expect(screen.getByText('Собран')).toBeInTheDocument();

    render(<SkladchinaCard skladchina={buildItem({ id: 's-2', kind: 'voluntary', deadline: null, targetKopecks: null, amountKopecks: null, actionRequired: false, myDebtStatus: null })} onClick={vi.fn()} />);
    expect(screen.getByText(/без срока/)).toBeInTheDocument();
    expect(screen.getByText(/1\s?000 ₽ получено/)).toBeInTheDocument();
  });
});

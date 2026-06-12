import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

import { SkladchinaCard } from '../../components/feed/SkladchinaCard';
import type { MySkladchinaListItemDto } from '../../types/api';

function buildItem(overrides: Partial<MySkladchinaListItemDto> = {}): MySkladchinaListItemDto {
  return {
    id: 's-1',
    title: 'Сбор на баню',
    clubId: 'club-1',
    clubName: 'Клуб',
    clubAvatarUrl: null,
    paymentMode: 'fixed_equal',
    totalGoalKopecks: 500000,
    collectedKopecks: 100000,
    participantCount: 5,
    paidCount: 1,
    deadline: new Date(Date.now() + 86_400_000).toISOString(),
    status: 'active',
    isOrganizerView: false,
    myStatus: 'pending',
    actionRequired: true,
    affectsReputation: false,
    ...overrides,
  };
}

describe('SkladchinaCard — «Важный сбор» badge', () => {
  it('показывает «⚠️ Важный сбор» когда affectsReputation = true', () => {
    render(<SkladchinaCard skladchina={buildItem({ affectsReputation: true })} onClick={vi.fn()} />);
    expect(screen.getByText('⚠️ Важный сбор')).toBeInTheDocument();
    expect(screen.queryByText('⚠️ Репутация')).not.toBeInTheDocument();
  });

  it('не показывает бейдж когда affectsReputation = false', () => {
    render(<SkladchinaCard skladchina={buildItem({ affectsReputation: false })} onClick={vi.fn()} />);
    expect(screen.queryByText('⚠️ Важный сбор')).not.toBeInTheDocument();
  });
});

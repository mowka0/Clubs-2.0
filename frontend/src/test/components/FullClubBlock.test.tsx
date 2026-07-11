import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

import { FullClubBlock } from '../../components/applications/FullClubBlock';
import { useExpandAndApproveMutation } from '../../queries/applications';
import type { PendingApplicationDto } from '../../types/api';

vi.mock('../../queries/applications', () => ({ useExpandAndApproveMutation: vi.fn() }));
const mockedMutation = vi.mocked(useExpandAndApproveMutation);

function pendingApp(o: { applicationId: string; firstName: string }): PendingApplicationDto {
  return {
    applicationId: o.applicationId,
    answerText: null,
    createdAt: new Date().toISOString(),
    hoursUntilAutoReject: 24,
    applicant: {
      userId: `u-${o.applicationId}`,
      firstName: o.firstName,
      lastName: null,
      telegramUsername: null,
      avatarUrl: null,
      country: null,
      city: null,
      bio: null,
      interests: [],
    },
    peerStats: {
      memberClubCount: 1, totalConfirmations: 0, totalAttendances: 0,
      reliableClubs: 0, trackRecordClubs: 0, level: 1, levelName: 'Новичок', levelTier: 'base',
    },
    // Полный клуб: 20 занятых из 20 мест.
    club: { id: 'club-1', name: 'Партия', avatarUrl: null, memberCount: 20, memberLimit: 20 },
  };
}

describe('FullClubBlock (заявки полного клуба, club-invites)', () => {
  const mutate = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    mockedMutation.mockReturnValue(
      { mutate, isPending: false } as unknown as ReturnType<typeof useExpandAndApproveMutation>,
    );
  });

  const apps = [
    pendingApp({ applicationId: 'a1', firstName: 'Дмитрий' }),
    pendingApp({ applicationId: 'a2', firstName: 'Мария' }),
  ];

  it('renders header badge, applicant rows and a disabled expand button', () => {
    render(<FullClubBlock applications={apps} onReview={vi.fn()} onExpanded={vi.fn()} />);

    expect(screen.getByText('Клуб полон · 20/20')).toBeInTheDocument();
    expect(screen.getByText('Дмитрий')).toBeInTheDocument();
    expect(screen.getByText('Мария')).toBeInTheDocument();
    // Лимит не введён — кнопка задизейблена; подсказка считает минимум (20 + 2 заявки).
    expect(screen.getByRole('button', { name: 'Расширить клуб и принять всех (2)' })).toBeDisabled();
    expect(screen.getByText(/не меньше 22/)).toBeInTheDocument();
    // Подсказка в поле — текущий лимит числом (решение PO).
    expect(screen.getByPlaceholderText('20')).toBeInTheDocument();
  });

  it('keeps the button disabled for a limit below member count + applications', () => {
    render(<FullClubBlock applications={apps} onReview={vi.fn()} onExpanded={vi.fn()} />);
    fireEvent.change(screen.getByPlaceholderText('20'), { target: { value: '21' } });
    expect(screen.getByRole('button', { name: 'Расширить клуб и принять всех (2)' })).toBeDisabled();
  });

  it('submits expand-and-approve with the entered limit and all application ids', () => {
    render(<FullClubBlock applications={apps} onReview={vi.fn()} onExpanded={vi.fn()} />);
    fireEvent.change(screen.getByPlaceholderText('20'), { target: { value: '22' } });
    const button = screen.getByRole('button', { name: 'Расширить клуб и принять всех (2)' });
    expect(button).toBeEnabled();
    fireEvent.click(button);

    expect(mutate).toHaveBeenCalledWith(
      { clubId: 'club-1', newMemberLimit: 22, applicationIds: ['a1', 'a2'] },
      expect.anything(),
    );
  });

  it('routes row and ✕ taps into the existing review flow (отказ с причиной)', () => {
    const onReview = vi.fn();
    render(<FullClubBlock applications={apps} onReview={onReview} onExpanded={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Отклонить заявку: Дмитрий' }));
    expect(onReview).toHaveBeenCalledWith(apps[0]);
  });
});

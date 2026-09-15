import { describe, it, expect } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../utils/renderWithProviders';
import { PayReturnPage } from '../../pages/PayReturnPage';

const CLUB_ID = '7c2e1d2a-0000-4000-8000-000000000001';

describe('PayReturnPage', () => {
  it('ведёт в бота из бандла и не слушает параметр bot из адреса', () => {
    // `?bot=<чужой>` на нашей странице «Оплата принята» был бы фишингом (ревью биллинга 2026-09-07).
    renderWithProviders(<PayReturnPage kind="success" />, {
      routerEntries: [`/pay/return?club=${CLUB_ID}&bot=evil_bot`],
    });

    expect(screen.getByText('Оплата принята')).toBeInTheDocument();
    const back = screen.getByRole('link', { name: 'Открыть Clubs в Telegram' });
    expect(back).toHaveAttribute('href', `https://t.me/clubs_v2_bot?startapp=billing_${CLUB_ID}`);
  });

  it('клуб не в формате UUID в deep link не подставляется', () => {
    renderWithProviders(<PayReturnPage kind="fail" />, {
      routerEntries: ['/pay/fail?club=../../evil'],
    });

    expect(screen.getByText('Оплата не прошла')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Открыть Clubs в Telegram' }))
      .toHaveAttribute('href', 'https://t.me/clubs_v2_bot');
  });
});

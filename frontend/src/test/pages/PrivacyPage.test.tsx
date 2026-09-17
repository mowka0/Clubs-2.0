import { describe, it, expect } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../utils/renderWithProviders';
import { PrivacyPage } from '../../pages/PrivacyPage';
import { LandingPage } from '../../pages/LandingPage';
import { SELLER, SUPPORT } from '../../pages/landingContent';
import { PUBLIC_WEB_PATHS } from '../../entry';

describe('PrivacyPage', () => {
  it('называет оператора, состав данных, срок удаления и контакт поддержки', () => {
    renderWithProviders(<PrivacyPage />, { routerEntries: ['/privacy'] });

    expect(screen.getByRole('heading', { level: 1, name: /Политика обработки персональных данных/ })).toBeInTheDocument();
    expect(screen.getByText(new RegExp(`самозанятый ${SELLER.name}, ИНН ${SELLER.inn}`))).toBeInTheDocument();
    expect(screen.getByText(/реквизиты карты вводятся на стороне платёжного сервиса Robokassa/)).toBeInTheDocument();
    expect(screen.getByText(/в течение 30 дней/)).toBeInTheDocument();
    // Контакт для обращений — тот же, что на лендинге.
    expect(screen.getAllByText(new RegExp(SUPPORT.email)).length).toBeGreaterThan(0);
  });

  it('лендинг ведёт на политику, а сам адрес открыт как публичная страница', () => {
    renderWithProviders(<LandingPage />, { routerEntries: ['/about'] });
    const links = screen.getAllByRole('link', { name: /политик/i });
    expect(links.length).toBeGreaterThanOrEqual(1);
    links.forEach((a) => expect(a).toHaveAttribute('href', '/privacy'));
    expect(PUBLIC_WEB_PATHS).toContain('/privacy');
  });
});

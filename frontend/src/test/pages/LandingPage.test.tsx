import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { LandingPage } from '../../pages/LandingPage';
import { CHAT_PRICE_LINE } from '../../api/billing';
import { SELLER } from '../../pages/landingContent';

describe('LandingPage', () => {
  it('показывает всё, что нужно модерации: цену, условия, оферту, продавца и вход в Telegram', () => {
    render(<LandingPage />);

    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.getByText(CHAT_PRICE_LINE)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Сколько стоит' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Возврат' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /публичная оферта/ })).toBeInTheDocument();
    // Оферта на странице — тот же текст, что в шите оплаты, с ФИО продавца.
    expect(screen.getByText(new RegExp(`Исполнитель \\(самозанятый ${SELLER.name}\\)`))).toBeInTheDocument();
    expect(screen.getByText(new RegExp(`ИНН ${SELLER.inn}`))).toBeInTheDocument();
    // Обе кнопки ведут в бота из бандла, не из адреса страницы.
    const links = screen.getAllByRole('link', { name: /Telegram|Подключить/ });
    expect(links.length).toBeGreaterThanOrEqual(2);
    links.forEach((a) => expect(a).toHaveAttribute('href', 'https://t.me/clubs_v2_bot'));
  });
});

import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { WelcomeScene, memberCountCaption } from '../../components/onboarding/WelcomeScene';

function renderScene(overrides: Partial<Parameters<typeof WelcomeScene>[0]> = {}) {
  const onCta = vi.fn();
  render(
    <WelcomeScene
      variant="free"
      clubName="Бег по субботам"
      clubCaption="Москва · 14 участников"
      clubAvatarUrl={null}
      ctaPending={false}
      onCta={onCta}
      {...overrides}
    />,
  );
  return { onCta };
}

describe('WelcomeScene — велком-сцена новичка в клубе (срез 3)', () => {
  it('free: праздник, три шага, факт о надёжности, CTA «Перейти в клуб» без подсказки', () => {
    const { onCta } = renderScene();

    expect(screen.getByText('Ты в клубе!')).toBeInTheDocument();
    expect(screen.getByText('Бег по субботам')).toBeInTheDocument();
    expect(screen.getByText('Москва · 14 участников')).toBeInTheDocument();
    expect(screen.getByText('Голосуй — пойдёшь или нет')).toBeInTheDocument();
    expect(screen.getByText('Надёжность')).toBeInTheDocument();
    expect(screen.queryByText(/Оплатить взнос можно/)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Перейти в клуб' }));
    expect(onCta).toHaveBeenCalledTimes(1);
  });

  it('paid: плашка про взнос организатору и подсказка под кнопкой', () => {
    renderScene({ variant: 'paid', clubCaption: 'Москва · 900 ₽' });

    expect(screen.getByText('Ты в клубе!')).toBeInTheDocument();
    expect(screen.getByText('Взнос — напрямую организатору')).toBeInTheDocument();
    expect(screen.getByText('Оплатить взнос можно на странице клуба')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Перейти в клуб' })).toBeInTheDocument();
  });

  it('applied: заявка у организатора, рассказ о продукте, CTA в каталог', () => {
    const { onCta } = renderScene({ variant: 'applied', clubCaption: 'Москва · мест пока нет' });

    expect(screen.getByText('Заявка у организатора')).toBeInTheDocument();
    expect(screen.getByText('А пока — оглядись')).toBeInTheDocument();
    expect(screen.getByText('Заявка отправлена.')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Посмотреть другие клубы' }));
    expect(onCta).toHaveBeenCalledTimes(1);
  });

  it('кнопка заблокирована, пока онбординг помечается на сервере', () => {
    const { onCta } = renderScene({ ctaPending: true });

    const button = screen.getByRole('button');
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(onCta).not.toHaveBeenCalled();
  });
});

describe('memberCountCaption — русская плюрализация', () => {
  it.each([
    [1, '1 участник'],
    [2, '2 участника'],
    [5, '5 участников'],
    [11, '11 участников'],
    [12, '12 участников'],
    [14, '14 участников'],
    [21, '21 участник'],
    [22, '22 участника'],
    [111, '111 участников'],
  ])('%i → %s', (count, expected) => {
    expect(memberCountCaption(count)).toBe(expected);
  });
});

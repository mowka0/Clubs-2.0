import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';

import { ProfileQuestCard, ProfileQuestCongrats } from '../../components/profile/ProfileQuestCard';
import type { ProfileQuestDto } from '../../types/api';

function quest(o: Partial<ProfileQuestDto> = {}): ProfileQuestDto {
  return { cityDone: false, interestsDone: false, bioDone: false, completed: false, ...o };
}

const DONE_VALUES = { city: 'Москва', bio: 'Бегаю и играю в шахматы', interests: ['бег', 'шахматы'] };

function renderCard(q: ProfileQuestDto, overrides: Partial<Parameters<typeof ProfileQuestCard>[0]> = {}) {
  const onFill = vi.fn();
  const onToggleFold = vi.fn();
  render(
    <ProfileQuestCard
      quest={q}
      doneValues={DONE_VALUES}
      folded={false}
      onToggleFold={onToggleFold}
      onFill={onFill}
      {...overrides}
    />,
  );
  return { onFill, onToggleFold };
}

describe('ProfileQuestCard v2 — карусель «один экран = один шаг»', () => {
  it('пустой квест: виден ТОЛЬКО первый шаг (город), другие шаги не пугают с порога', () => {
    const { onFill } = renderCard(quest());

    expect(screen.getByText('Прокачай профиль')).toBeInTheDocument();
    expect(screen.getByText('чтобы лучше подбирать клубы')).toBeInTheDocument();
    expect(screen.getByText(/0 \/ 50 XP/)).toBeInTheDocument();
    // Один слайд: город. Био и интересы НЕ отрендерены — суть редизайна.
    expect(screen.getByText('Укажи город')).toBeInTheDocument();
    expect(screen.getByText('Найдём клубы рядом с тобой')).toBeInTheDocument();
    expect(screen.queryByText('Пару слов о себе')).not.toBeInTheDocument();
    expect(screen.queryByText('Добавь интересы')).not.toBeInTheDocument();

    // Тап по слайду (клик по тексту всплывает до кнопки-слайда) — редактор с подсветкой города.
    fireEvent.click(screen.getByText('Укажи город'));
    expect(onFill).toHaveBeenCalledWith('city');
  });

  it('город заполнен: автопозиция на «О себе» (новый порядок), первая точка жёлтая', () => {
    renderCard(quest({ cityDone: true }));

    expect(screen.getByText(/10 \/ 50 XP/)).toBeInTheDocument();
    // Порядок v2: город → о себе → интересы (PO 2026-07-25).
    expect(screen.getByText('Пару слов о себе')).toBeInTheDocument();
    expect(screen.getByText('Чем увлекаешься?)')).toBeInTheDocument();

    const cityDot = screen.getByRole('button', { name: 'Шаг «Укажи город» — заполнен' });
    expect(cityDot.className).toContain('rd-gold');
    const bioDot = screen.getByRole('button', { name: 'Шаг «Пару слов о себе»' });
    expect(bioDot.className).toContain('rd-on');
  });

  it('свайп назад: заполненный шаг показывает «готово» и значение вместо кнопки', () => {
    renderCard(quest({ cityDone: true }));

    // Листаем со слайда «О себе» назад стрелкой.
    fireEvent.click(screen.getByRole('button', { name: 'Предыдущий шаг' }));

    expect(screen.getByText('Город — готово')).toBeInTheDocument();
    expect(screen.getByText('Москва')).toBeInTheDocument();
    expect(screen.getByText('✓ +10 XP')).toBeInTheDocument();
    expect(screen.queryByText('Заполнить')).not.toBeInTheDocument();
  });

  it('точки кликабельны: тап по третьей ведёт на «Интересы»', () => {
    renderCard(quest());

    fireEvent.click(screen.getByRole('button', { name: 'Шаг «Добавь интересы»' }));

    expect(screen.getByText('Добавь интересы')).toBeInTheDocument();
    expect(screen.getByText('Подберём интересные клубы')).toBeInTheDocument();
  });

  it('folded: пилюля с XP и следующим шагом, тап зовёт onToggleFold', () => {
    const { onToggleFold } = renderCard(quest({ cityDone: true }), { folded: true });

    const pill = screen.getByRole('button', { name: /Развернуть квест профиля/ });
    expect(pill).toHaveTextContent('10 / 50 XP');
    expect(pill).toHaveTextContent('Дальше: пару слов о себе +15');
    expect(screen.queryByText('Прокачай профиль')).not.toBeInTheDocument();

    fireEvent.click(pill);
    expect(onToggleFold).toHaveBeenCalledOnce();
  });

  it('кнопка сворачивания на развёрнутой карточке зовёт onToggleFold', () => {
    const { onToggleFold } = renderCard(quest());

    fireEvent.click(screen.getByRole('button', { name: 'Свернуть квест профиля' }));
    expect(onToggleFold).toHaveBeenCalledOnce();
  });
});

describe('ProfileQuestCongrats — поздравление с уровнем 2', () => {
  it('рендерит титул, бейдж «Визитка», +50 XP; «Отлично!» вызывает onAck', () => {
    const onAck = vi.fn();
    render(<ProfileQuestCongrats title="Уровень 2 — «Свой»!" onAck={onAck} />);

    expect(screen.getByText('Уровень 2 — «Свой»!')).toBeInTheDocument();
    expect(screen.getByText('Бейдж «Визитка»')).toBeInTheDocument();
    expect(screen.getByText('+50 XP')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Отлично!' }));
    expect(onAck).toHaveBeenCalledOnce();
  });
});

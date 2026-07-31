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

describe('ProfileQuestCard — три поля списком, заполнение в один заход', () => {
  it('пустой квест: все три поля видны сразу, галочек нет, кнопка ведёт в редактор целиком', () => {
    const { onFill } = renderCard(quest());

    expect(screen.getByText('Прокачай профиль')).toBeInTheDocument();
    expect(screen.getByText('чтобы лучше подбирать клубы')).toBeInTheDocument();
    expect(screen.getByText(/0 \/ 50 XP/)).toBeInTheDocument();

    // Карусель снята: человек видит весь объём работы разом, а не по одному шагу.
    expect(screen.getByText('Город')).toBeInTheDocument();
    expect(screen.getByText('О себе')).toBeInTheDocument();
    expect(screen.getByText('Интересы')).toBeInTheDocument();
    expect(screen.getByText('Найдём клубы рядом с тобой')).toBeInTheDocument();
    expect(screen.getAllByLabelText('не заполнено')).toHaveLength(3);
    expect(screen.queryByLabelText('заполнено')).not.toBeInTheDocument();

    // Кнопка не выбирает поле — редактор открывается целиком.
    fireEvent.click(screen.getByRole('button', { name: 'Заполнить профиль' }));
    expect(onFill).toHaveBeenCalledOnce();
  });

  it('часть заполнена: галочка и сохранённое значение у готового поля, XP пересчитаны', () => {
    renderCard(quest({ cityDone: true }));

    expect(screen.getByText(/10 \/ 50 XP/)).toBeInTheDocument();
    expect(screen.getAllByLabelText('заполнено')).toHaveLength(1);
    expect(screen.getAllByLabelText('не заполнено')).toHaveLength(2);
    // У заполненного поля вместо мотивации — что именно сохранено.
    expect(screen.getByText('Москва')).toBeInTheDocument();
    expect(screen.queryByText('Найдём клубы рядом с тобой')).not.toBeInTheDocument();
    // Начатый профиль зовёт дозаполнить, а не «заполнить» с нуля.
    expect(screen.getByRole('button', { name: 'Дозаполнить профиль' })).toBeInTheDocument();
  });

  it('folded: пилюля с XP и остатком, тап зовёт onToggleFold', () => {
    const { onToggleFold } = renderCard(quest({ cityDone: true }), { folded: true });

    const pill = screen.getByRole('button', { name: /Развернуть квест профиля/ });
    expect(pill).toHaveTextContent('10 / 50 XP');
    expect(pill).toHaveTextContent('Осталось: 2 из 3');
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
  it('рендерит титул, бейдж «Визитка», +50 XP; «Забрать!» вызывает onAck', () => {
    const onAck = vi.fn();
    render(<ProfileQuestCongrats title="Уровень 2 — «Свой»!" onAck={onAck} />);

    expect(screen.getByText('Уровень 2 — «Свой»!')).toBeInTheDocument();
    expect(screen.getByText('Бейдж «Визитка»')).toBeInTheDocument();
    expect(screen.getByText('+50 XP')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Забрать!' }));
    expect(onAck).toHaveBeenCalledOnce();
  });
});

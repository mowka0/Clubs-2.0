import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';

import { ProfileQuestCard, ProfileQuestCongrats } from '../../components/profile/ProfileQuestCard';
import type { ProfileQuestDto } from '../../types/api';

function quest(o: Partial<ProfileQuestDto> = {}): ProfileQuestDto {
  return { cityDone: false, interestsDone: false, bioDone: false, completed: false, ...o };
}

describe('ProfileQuestCard — карточка «Прокачай профиль»', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('пустой квест: три шага по порядку с «зачем» и XP, первый подсвечен с кнопкой', () => {
    const onFill = vi.fn();
    render(<ProfileQuestCard quest={quest()} onFill={onFill} />);

    expect(screen.getByText('Прокачай профиль')).toBeInTheDocument();
    expect(screen.getByText(/0 \/ 50 XP/)).toBeInTheDocument();
    // Шаги и их «зачем»
    expect(screen.getByText('Город')).toBeInTheDocument();
    expect(screen.getByText('Найдём клубы рядом с тобой')).toBeInTheDocument();
    expect(screen.getByText('Интересы')).toBeInTheDocument();
    expect(screen.getByText('По ним подберём клубы, которые твои')).toBeInTheDocument();
    expect(screen.getByText('О себе')).toBeInTheDocument();
    expect(screen.getByText('Организаторы поймут, кто к ним идёт')).toBeInTheDocument();
    // XP шагов (сумма = 50, порог «Свой»)
    expect(screen.getByText('+10 XP')).toBeInTheDocument();
    expect(screen.getByText('+25 XP')).toBeInTheDocument();
    expect(screen.getByText('+15 XP')).toBeInTheDocument();
    // Кнопка «Заполнить» одна — на следующем (первом незавершённом) шаге
    fireEvent.click(screen.getByRole('button', { name: 'Заполнить' }));
    expect(onFill).toHaveBeenCalledOnce();
  });

  it('город сделан: 10/50, шаг помечен, следующий — интересы', () => {
    render(<ProfileQuestCard quest={quest({ cityDone: true })} onFill={vi.fn()} />);

    expect(screen.getByText(/10 \/ 50 XP/)).toBeInTheDocument();
    expect(screen.getByText('20%')).toBeInTheDocument();
    const citystep = screen.getByText('Город').closest('.rd-q-step');
    expect(citystep?.className).toContain('rd-done');
    const interestsStep = screen.getByText('Интересы').closest('.rd-q-step');
    expect(interestsStep?.className).toContain('rd-next');
  });

  it('сворачивается в пилюлю с подсказкой следующего шага и разворачивается обратно', () => {
    render(<ProfileQuestCard quest={quest({ cityDone: true })} onFill={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: 'Свернуть квест профиля' }));
    // Пилюля: счёт XP + «Дальше: интересы +25»
    const pill = screen.getByRole('button', { name: /Развернуть квест профиля/ });
    expect(pill).toHaveTextContent('10 / 50 XP');
    expect(pill).toHaveTextContent('Дальше: интересы +25');
    expect(screen.queryByText('Прокачай профиль')).not.toBeInTheDocument();
    // Fold переживает перезаход (localStorage)
    expect(localStorage.getItem('profileQuestFolded')).toBe('1');

    fireEvent.click(pill);
    expect(screen.getByText('Прокачай профиль')).toBeInTheDocument();
    expect(localStorage.getItem('profileQuestFolded')).toBe('0');
  });
});

describe('ProfileQuestCongrats — поздравление с уровнем 2', () => {
  it('рендерит титул, бейдж «Визитка», +50 XP; «Отлично» вызывает onAck', () => {
    const onAck = vi.fn();
    render(<ProfileQuestCongrats title="Уровень 2 — «Свой»!" onAck={onAck} />);

    expect(screen.getByText('Уровень 2 — «Свой»!')).toBeInTheDocument();
    expect(screen.getByText('Бейдж «Визитка»')).toBeInTheDocument();
    expect(screen.getByText('+50 XP')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Отлично' }));
    expect(onAck).toHaveBeenCalledOnce();
  });
});
